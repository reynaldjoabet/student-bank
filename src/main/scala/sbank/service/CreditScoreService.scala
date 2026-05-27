package sbank.service

import sbank.domain.*
import sbank.db.Users
import sbank.external.CreditBureau

import cats.effect.*
import cats.syntax.all.*
import java.time.{Duration, Instant}

/** Caches the user's FICO score with a configurable TTL so each "what's my score" tap doesn't trigger a real Experian
  * pull.
  */
trait CreditScoreService[F[_]] {
  def current(userId: UserId): F[Option[CreditScore]]
  def refresh(userId: UserId): F[Option[CreditScore]]
}

object CreditScoreService {

  /** Real production policy might be longer (24h) — fresh enough to feel dynamic, gentle on the upstream rate limit.
    */
  val CacheTtl: Duration = Duration.ofHours(6)

  def make[F[_]: Async](
      users: Users[F],
      bureau: CreditBureau[F]
  ): CreditScoreService[F] =
    new CreditScoreService[F] {

      def current(userId: UserId): F[Option[CreditScore]] =
        users.find(userId).flatMap {
          case None    => Async[F].pure(None)
          case Some(u) =>
            u.cachedCreditScoreAt match {
              case Some(at) if !expired(at) =>
                Async[F].pure(u.cachedCreditScore)
              case _ => refresh(userId)
            }
        }

      def refresh(userId: UserId): F[Option[CreditScore]] =
        users.find(userId).flatMap {
          case Some(u) if u.ssnHash.isDefined =>
            for {
              score <- bureau.fico(u.ssnHash.get)
              now <- Async[F].delay(Instant.now())
              _ <- users.cacheCreditScore(userId, score, now)
            } yield Some(score)
          case _ => Async[F].pure(None)
        }

      private def expired(at: Instant): Boolean =
        Duration.between(at, Instant.now()).compareTo(CacheTtl) > 0
    }
}
