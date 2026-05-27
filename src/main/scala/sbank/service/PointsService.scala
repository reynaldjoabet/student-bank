package sbank.service

import sbank.domain.*
import sbank.db.{Cards, Education, Points as PointsRepo}

import cats.effect.*
import cats.syntax.all.*
import java.time.Instant
import java.util.UUID

/** Points engine.
  *
  *   - **Cashback on purchases**: 1 point per dollar spent (`Earn`).
  *   - **Education rewards**: when a video is marked complete, the user receives `EducationVideo.pointsReward` points
  *     (idempotent — second completion yields nothing).
  *   - **Redemption**: convert points to a card credit at a configurable rate (e.g. 100 points = $1.00).
  */
trait PointsService[F[_]] {
  def awardForPurchase(
      userId: UserId,
      txId: TransactionId,
      purchaseMinor: Long
  ): F[Unit]
  def awardForVideo(
      userId: UserId,
      videoId: EducationVideoId
  ): F[Either[PointsService.Error, Points]]
  def redeem(
      userId: UserId,
      points: Points,
      ontoCard: CardId
  ): F[Either[PointsService.Error, Long]]
  def balance(userId: UserId): F[Long]
}

object PointsService {

  sealed trait Error
  object Error {
    case object VideoNotFound extends Error
    case object AlreadyRewarded extends Error
    case object ProgressIncomplete extends Error
    case object InsufficientPoints extends Error
    case object CardNotFound extends Error
  }

  /** 100 points = $1.00. Tune by region / promo. */
  private val PointsPerCent = 100

  def make[F[_]: Async](
      points: PointsRepo[F],
      cards: Cards[F],
      education: Education[F]
  ): PointsService[F] = new PointsService[F] {

    def awardForPurchase(
        userId: UserId,
        txId: TransactionId,
        purchaseMinor: Long
    ): F[Unit] = {
      val pts =
        (purchaseMinor.abs / 100).max(0) // 1 pt per dollar, ignoring cents
      if (pts == 0) Async[F].unit
      else
        Async[F]
          .delay(Instant.now())
          .flatMap(now =>
            points
              .append(
                PointsLedger(
                  id = PointsLedgerId.assume(UUID.randomUUID()),
                  userId = userId,
                  kind = PointsEventKind.Earn,
                  amount = sbank.domain.Points.assume(pts),
                  centsValue = pts * 100 / PointsPerCent,
                  relatedTransactionId = Some(txId),
                  relatedVideoId = None,
                  note = Some(Title.assume(s"Cashback on tx ${txId.value}")),
                  createdAt = now
                )
              )
              .void
          )
    }

    def awardForVideo(
        userId: UserId,
        videoId: EducationVideoId
    ): F[Either[Error, Points]] =
      education.find(videoId).flatMap {
        case None    => Async[F].pure(Left(Error.VideoNotFound))
        case Some(v) =>
          education.progress(userId, videoId).flatMap {
            case None                    => Async[F].pure(Left(Error.ProgressIncomplete))
            case Some(p) if !p.completed =>
              Async[F].pure(Left(Error.ProgressIncomplete))
            case Some(p) if p.rewardedAt.isDefined =>
              Async[F].pure(Left(Error.AlreadyRewarded))
            case Some(p) =>
              for {
                now <- Async[F].delay(Instant.now())
                _ <- points.append(
                  PointsLedger(
                    id = PointsLedgerId.assume(UUID.randomUUID()),
                    userId = userId,
                    kind = PointsEventKind.Earn,
                    amount = v.pointsReward,
                    centsValue = v.pointsReward.value * 100 / PointsPerCent,
                    relatedTransactionId = None,
                    relatedVideoId = Some(videoId),
                    note = Some(Title.assume(s"Education reward: ${v.title.value}")),
                    createdAt = now
                  )
                )
                _ <- education.saveProgress(p.copy(rewardedAt = Some(now)))
              } yield Right(v.pointsReward)
          }
      }

    def redeem(
        userId: UserId,
        ptsAmount: Points,
        ontoCard: CardId
    ): F[Either[Error, Long]] =
      for {
        bal <- points.balanceFor(userId)
        out <-
          if (bal < ptsAmount.value)
            Async[F].pure(Left(Error.InsufficientPoints))
          else
            cards.find(ontoCard).flatMap {
              case None    => Async[F].pure(Left(Error.CardNotFound))
              case Some(_) =>
                for {
                  now <- Async[F].delay(Instant.now())
                  cents = ptsAmount.value * 100 / PointsPerCent
                  _ <- points.append(
                    PointsLedger(
                      id = PointsLedgerId.assume(UUID.randomUUID()),
                      userId = userId,
                      kind = PointsEventKind.Redeem,
                      amount = ptsAmount,
                      centsValue = cents,
                      relatedTransactionId = None,
                      relatedVideoId = None,
                      note = Some(Title.assume(s"Redeem to card $ontoCard")),
                      createdAt = now
                    )
                  )
                  _ <- cards.adjustBalance(
                    ontoCard,
                    -cents
                  ) // credit reduces outstanding balance
                } yield Right(cents)
            }
      } yield out

    def balance(userId: UserId): F[Long] = points.balanceFor(userId)
  }
}
