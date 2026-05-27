package sbank.service

import sbank.domain.*
import sbank.db.{Cards, Users}
import sbank.external.Galileo
import cats.effect.*
import cats.syntax.all.*

import java.time.Instant
import java.util.UUID

/** Card application + lifecycle.
  *
  * Credit-line decisioning is driven by the cached FICO score. Anything under 580 is rejected; up to 720 gets a starter
  * $500 line; 720+ gets $2_000. The thresholds live in one place so the policy can be tuned without changes to the
  * Galileo wiring.
  */
trait CardService[F[_]] {
  def apply(userId: UserId): F[Either[CardService.Error, Card]]
  def activate(cardId: CardId): F[Unit]
  def freeze(cardId: CardId): F[Unit]
}

object CardService {

  sealed trait Error
  object Error {
    case object UserNotFound extends Error
    case object KycRequired extends Error
    case object NoCreditScore extends Error
    case object Underwriting extends Error
  }

  def make[F[_]: Async](
      users: Users[F],
      cards: Cards[F],
      galileo: Galileo[F]
  ): CardService[F] = new CardService[F] {

    def apply(userId: UserId): F[Either[Error, Card]] =
      users.find(userId).flatMap {
        case None                                         => Async[F].pure(Left(Error.UserNotFound))
        case Some(u) if u.kycStatus != KycStatus.Approved =>
          Async[F].pure(Left(Error.KycRequired))
        case Some(u) =>
          u.cachedCreditScore match {
            case None        => Async[F].pure(Left(Error.NoCreditScore))
            case Some(score) =>
              underwrite(score) match {
                case None        => Async[F].pure(Left(Error.Underwriting))
                case Some(terms) =>
                  for {
                    issued <- galileo.issueCard(
                      userId,
                      u.fullName,
                      "(student address)"
                    )
                    now <- Async[F].delay(Instant.now())
                    card = Card(
                      id = CardId.assume(UUID.randomUUID()),
                      userId = userId,
                      galileoPrn = issued.prn,
                      token = issued.token,
                      brand = issued.brand,
                      last4 = issued.last4,
                      expiry = issued.expiry,
                      status = CardStatus.Issued,
                      creditLimit = terms.limit,
                      balanceMinor = 0L,
                      aprBps = terms.apr,
                      issuedAt = Some(now),
                      createdAt = now
                    )
                    saved <- cards.insert(card)
                    _ <- galileo.setCreditLimit(issued.prn, terms.limit)
                  } yield Right(saved)
              }
          }
      }

    def activate(cardId: CardId): F[Unit] =
      cards.find(cardId).flatMap {
        case Some(c) =>
          galileo.activate(c.galileoPrn) *> cards.setStatus(
            cardId,
            CardStatus.Active
          )
        case None =>
          Async[F].raiseError(new NoSuchElementException(s"card $cardId"))
      }

    def freeze(cardId: CardId): F[Unit] =
      cards.find(cardId).flatMap {
        case Some(c) =>
          galileo
            .freeze(c.galileoPrn) *> cards.setStatus(cardId, CardStatus.Frozen)
        case None =>
          Async[F].raiseError(new NoSuchElementException(s"card $cardId"))
      }
  }

  /** Underwriting policy. Centralised so it can be A/B'd or replaced wholesale.
    */
  private final case class Terms(limit: CreditLimit, apr: AprBps)
  private def underwrite(score: CreditScore): Option[Terms] =
    score.value match {
      case s if s < 580 => None
      case s if s < 660 =>
        Some(
          Terms(CreditLimit.applyUnsafe(50_000), AprBps.applyUnsafe(2_999))
        ) // $500 limit, 29.99% APR
      case s if s < 720 =>
        Some(
          Terms(CreditLimit.applyUnsafe(100_000), AprBps.applyUnsafe(2_499))
        ) // $1,000 / 24.99%
      case _ =>
        Some(
          Terms(CreditLimit.applyUnsafe(200_000), AprBps.applyUnsafe(1_999))
        ) // $2,000 / 19.99%
    }
}
