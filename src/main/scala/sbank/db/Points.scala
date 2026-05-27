package sbank.db

import sbank.domain.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*
import cats.effect.{Concurrent, Resource}

trait Points[F[_]] {
  def append(entry: PointsLedger): F[PointsLedger]
  def listFor(userId: UserId, limit: Int): F[List[PointsLedger]]
  def balanceFor(userId: UserId): F[Long]
}

object Points {

  import Codecs.{pointsLedger as ledgerC, userId as userIdC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Points[F] =
    new Points[F] {

      def append(entry: PointsLedger): F[PointsLedger] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(entry)))

      def listFor(userId: UserId, limit: Int): F[List[PointsLedger]] =
        pool.use(
          _.prepare(Q.listFor).flatMap(
            _.stream((userId, limit), 64).compile.toList
          )
        )

      /** Balance is computed as the signed sum of the ledger:
        *   - `earn` and positive `adjustment` add
        *   - `redeem` subtracts
        *
        * Conservatively clamps below at 0 (a ledger that goes negative is a bug upstream — but we'd rather report 0
        * than a confusing negative balance).
        */
      def balanceFor(userId: UserId): F[Long] =
        pool
          .use(_.prepare(Q.balanceFor).flatMap(_.unique(userId)))
          .map(_.max(0L))
    }

  private object Q {
    val insert: Query[PointsLedger, PointsLedger] =
      sql"""INSERT INTO points_ledger (id, user_id, kind, amount, cents_value, related_transaction_id,
                                        related_video_id, note, created_at)
            VALUES $ledgerC
            RETURNING id, user_id, kind, amount, cents_value, related_transaction_id,
                      related_video_id, note, created_at""".query(ledgerC)

    val listFor: Query[(UserId, Int), PointsLedger] =
      sql"""SELECT id, user_id, kind, amount, cents_value, related_transaction_id,
                   related_video_id, note, created_at
            FROM points_ledger WHERE user_id = $userIdC
            ORDER BY created_at DESC LIMIT $int4""".query(ledgerC)

    val balanceFor: Query[UserId, Long] =
      sql"""SELECT COALESCE(SUM(CASE
                                  WHEN kind = 'redeem' THEN -amount
                                  ELSE amount
                                END), 0)::bigint
            FROM points_ledger WHERE user_id = $userIdC""".query(int8)
  }
}
