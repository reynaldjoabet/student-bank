package sbank.external

import sbank.domain.*

import cats.effect.*
import cats.syntax.all.*
import java.time.LocalDate

// ---------- Galileo (card program) ----------

trait Galileo[F[_]] {

  /** Create a new account + card for the given user. Returns the PRN and tokenised card.
    */
  def issueCard(
      userId: UserId,
      fullName: FullName,
      address: String
  ): F[Galileo.Issued]

  def activate(prn: GalileoPrn): F[Unit]
  def freeze(prn: GalileoPrn): F[Unit]
  def setCreditLimit(prn: GalileoPrn, limit: CreditLimit): F[Unit]

  /** Authorise (or settle) a transaction. Returns the rail reference. */
  def chargeFromLinkedAccount(
      prn: GalileoPrn,
      amount: PositiveAmount,
      sourceLast4: Last4
  ): F[String]
}

object Galileo {
  final case class Issued(
      prn: GalileoPrn,
      token: CardToken,
      brand: CardBrand,
      last4: Last4,
      expiry: CardExpiry
  )

  def sandbox[F[_]: Sync]: F[Galileo[F]] = Sync[F].pure(new Galileo[F] {
    private def randId = Sync[F].delay(
      java.util.UUID.randomUUID().toString.replaceAll("-", "").take(12)
    )

    def issueCard(userId: UserId, name: FullName, address: String): F[Issued] =
      for {
        prnStr <- Sync[F].delay(
          f"${scala.util.Random.nextLong().abs}%012d".take(12)
        )
        tokStr <- randId
      } yield Issued(
        prn = GalileoPrn.assume(prnStr),
        token = CardToken.assume(s"tok_$tokStr"),
        brand = CardBrand.assume("VISA"),
        last4 = Last4.assume(f"${scala.util.Random.nextInt(10_000)}%04d"),
        expiry = CardExpiry.assume("12/30")
      )

    def activate(prn: GalileoPrn): F[Unit] = Sync[F].unit
    def freeze(prn: GalileoPrn): F[Unit] = Sync[F].unit
    def setCreditLimit(prn: GalileoPrn, limit: CreditLimit): F[Unit] =
      Sync[F].unit
    def chargeFromLinkedAccount(
        prn: GalileoPrn,
        amount: PositiveAmount,
        sourceLast4: Last4
    ): F[String] =
      Sync[F].delay("gal_" + java.util.UUID.randomUUID().toString.take(16))
  })
}

// ---------- Jumio (KYC) + Experian (credit + scoring) ----------

trait Kyc[F[_]] {
  def submit(
      userId: UserId,
      fullName: FullName,
      dob: LocalDate,
      ssn: Ssn,
      documentImageUrl: String,
      selfieUrl: String
  ): F[Kyc.Decision]
  def refresh(ref: String): F[Kyc.Decision]
}

object Kyc {
  enum Decision {
    case Pending(ref: String); case Approved; case Rejected(reason: String)
  }

  def sandbox[F[_]: Sync]: F[Kyc[F]] = Sync[F].pure(new Kyc[F] {
    def submit(
        userId: UserId,
        fullName: FullName,
        dob: LocalDate,
        ssn: Ssn,
        documentImageUrl: String,
        selfieUrl: String
    ): F[Decision] =
      Sync[F].pure(Decision.Approved)
    def refresh(ref: String): F[Decision] = Sync[F].pure(Decision.Approved)
  })
}

trait CreditBureau[F[_]] {

  /** Snapshot FICO score. Synthetic and capped for student onboarding. */
  def fico(ssnHash: SsnHash): F[CreditScore]

  /** Triggers a soft pull for underwriting; returns a synthetic ID for tracking.
    */
  def softPull(ssnHash: SsnHash): F[String]
}

object CreditBureau {
  def sandbox[F[_]: Sync]: F[CreditBureau[F]] =
    Sync[F].pure(new CreditBureau[F] {
      def fico(ssnHash: SsnHash): F[CreditScore] =
        Sync[F].pure(
          CreditScore.assume(620 + scala.util.Random.nextInt(140))
        ) // 620..759
      def softPull(ssnHash: SsnHash): F[String] =
        Sync[F].delay("exp_" + java.util.UUID.randomUUID().toString.take(12))
    })
}

// ---------- SendGrid + push ----------

trait Messaging[F[_]] {
  def sendEmail(to: Email, subject: Title, body: Body): F[Unit]
  def sendSms(to: PhoneE164, body: String): F[Unit]
  def sendPush(userId: UserId, title: Title, body: Body): F[Unit]
}

object Messaging {
  def sandbox[F[_]: Sync](log: String => F[Unit]): F[Messaging[F]] =
    Sync[F].pure(new Messaging[F] {
      def sendEmail(to: Email, subject: Title, body: Body): F[Unit] =
        log(s"email to=${to.value} subj='${subject.value}'")
      def sendSms(to: PhoneE164, body: String): F[Unit] =
        log(s"sms to=${to.value} '$body'")
      def sendPush(userId: UserId, title: Title, body: Body): F[Unit] =
        log(s"push user=${userId.value} '${title.value}'")
    })
}

// ---------- HubSpot CRM ----------

trait Crm[F[_]] {
  def upsertContact(user: User): F[Unit]
  def recordCardApplication(userId: UserId): F[Unit]
  def recordLoanApplication(userId: UserId, loan: Loan): F[Unit]
}

object Crm {
  def sandbox[F[_]: Sync]: F[Crm[F]] = Sync[F].pure(new Crm[F] {
    def upsertContact(user: User): F[Unit] = Sync[F].unit
    def recordCardApplication(userId: UserId): F[Unit] = Sync[F].unit
    def recordLoanApplication(userId: UserId, loan: Loan): F[Unit] =
      Sync[F].unit
  })
}

// ---------- YouTube catalogue ----------

trait VideoCatalog[F[_]] {

  /** Discover videos from a curated playlist; converts each into our domain shape.
    */
  def syncPlaylist(playlistId: String): F[List[EducationVideo]]
}

object VideoCatalog {
  def sandbox[F[_]: Sync]: F[VideoCatalog[F]] =
    Sync[F].pure(new VideoCatalog[F] {
      def syncPlaylist(playlistId: String): F[List[EducationVideo]] =
        Sync[F].pure(Nil)
    })
}

// ---------- Blob storage (Azure-shaped) ----------

trait BlobStorage[F[_]] {

  /** Upload bytes and return the storage ref. */
  def put(key: BlobRef, bytes: Array[Byte], contentType: String): F[Unit]

  /** Generate a short-lived URL the mobile app can download from. */
  def signedUrl(key: BlobRef, ttlSeconds: Int): F[String]
  def delete(key: BlobRef): F[Unit]
}

object BlobStorage {
  def sandbox[F[_]: Sync]: F[BlobStorage[F]] = Sync[F].pure(new BlobStorage[F] {
    def put(key: BlobRef, bytes: Array[Byte], contentType: String): F[Unit] =
      Sync[F].unit
    def signedUrl(key: BlobRef, ttlSeconds: Int): F[String] =
      Sync[F].pure(
        s"https://blob.sandbox.invalid/${key.value}?sig=stub&ttl=$ttlSeconds"
      )
    def delete(key: BlobRef): F[Unit] = Sync[F].unit
  })
}
