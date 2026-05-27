package sbank.service

import sbank.domain.*
import sbank.db.Users
import sbank.external.{CreditBureau, Kyc}

import cats.effect.*
import cats.syntax.all.*
import java.security.MessageDigest
import java.time.{Instant, LocalDate}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** KYC + credit-pull onboarding.
  *
  * Critical privacy property: the raw SSN passes through this service exactly once (in `submitKyc`). We compute
  * `SsnHash` (HMAC-SHA256 with the server pepper) and `SsnLast4` (display only), then discard the raw value. The DB
  * never receives plaintext.
  */
trait Onboarding[F[_]] {
  def submitKyc(
      userId: UserId,
      fullName: FullName,
      dob: LocalDate,
      ssn: Ssn,
      documentImageUrl: String,
      selfieUrl: String
  ): F[KycStatus]
}

object Onboarding {

  /** SSN pepper. In production this comes from a secret manager. */
  final case class SsnPepper(value: Array[Byte])

  def make[F[_]: Async](
      users: Users[F],
      kyc: Kyc[F],
      bureau: CreditBureau[F],
      pepper: SsnPepper
  ): Onboarding[F] = new Onboarding[F] {

    def submitKyc(
        userId: UserId,
        fullName: FullName,
        dob: LocalDate,
        ssn: Ssn,
        documentImageUrl: String,
        selfieUrl: String
    ): F[KycStatus] =
      for {
        // Compute SSN hash before any external call so we never log the raw SSN.
        hash <- Async[F].delay(hmacHex(pepper.value, ssn.value))
        ssnHash = SsnHash.assume(hash)
        ssnL4 = SsnLast4.assume(ssn.value.takeRight(4))

        decision <- kyc.submit(
          userId,
          fullName,
          dob,
          ssn,
          documentImageUrl,
          selfieUrl
        )
        status = decision match {
          case Kyc.Decision.Approved    => KycStatus.Approved
          case Kyc.Decision.Rejected(_) => KycStatus.Rejected
          case Kyc.Decision.Pending(_)  => KycStatus.Pending
        }
        _ <- users.updateKyc(
          userId,
          status,
          Some(ssnHash),
          Some(ssnL4),
          Some(dob)
        )

        // If approved, do the initial soft pull and cache the score.
        _ <- status match {
          case KycStatus.Approved =>
            for {
              score <- bureau.fico(ssnHash)
              now <- Async[F].delay(Instant.now())
              _ <- users.cacheCreditScore(userId, score, now)
            } yield ()
          case _ => Async[F].unit
        }
      } yield status
  }

  /** HMAC-SHA256 hex digest. The pepper is server-side only — without it the hash is useless for SSN lookup, even with
    * a leaked database.
    */
  private def hmacHex(key: Array[Byte], data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    val raw = mac.doFinal(data.getBytes("UTF-8"))
    raw.map(b => f"$b%02x").mkString
  }

  /** Convenience: derive a deterministic pepper from an env-var seed using SHA-256. Production: pull the real pepper
    * from KMS / Key Vault.
    */
  def derivePepper(seed: String): SsnPepper =
    SsnPepper(
      MessageDigest.getInstance("SHA-256").digest(seed.getBytes("UTF-8"))
    )
}
