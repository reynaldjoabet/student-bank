package sbank.db

import sbank.domain.*
import skunk.syntax.all.sql
import cats.effect.*
import cats.syntax.all.*
import skunk.*

trait CardTransactions[F[_]] {
  def insert(t: CardTransaction): F[CardTransaction]
  def listFor(cardId: CardId, limit: Int): F[List[CardTransaction]]
  def setStatus(id: TransactionId, status: TransactionStatus): F[Unit]
  def openDispute(d: Dispute): F[Dispute]
  def listDisputes(userId: UserId): F[List[Dispute]]
}

object CardTransactions {

  import Codecs.{
    transaction as txC,
    transactionId as txIdC,
    cardId as cardIdC,
    transactionStatus as statusC,
    dispute as disputeC,
    userId as userIdC
  }
  import skunk.codec.all.int4

  def make[F[_]: Concurrent](
      pool: Resource[F, Session[F]]
  ): CardTransactions[F] =
    new CardTransactions[F] {

      def insert(t: CardTransaction): F[CardTransaction] =
        pool.use(_.prepare(Q.insertTx).flatMap(_.unique(t)))

      def listFor(cardId: CardId, limit: Int): F[List[CardTransaction]] =
        pool.use(
          _.prepare(Q.listFor).flatMap(
            _.stream((cardId, limit), 256).compile.toList
          )
        )

      def setStatus(id: TransactionId, status: TransactionStatus): F[Unit] =
        pool.use(_.prepare(Q.setStatus).flatMap(_.execute((status, id)))).void

      def openDispute(d: Dispute): F[Dispute] =
        pool.use(_.prepare(Q.insertDispute).flatMap(_.unique(d)))

      def listDisputes(userId: UserId): F[List[Dispute]] =
        pool.use(
          _.prepare(Q.listDisputes).flatMap(_.stream(userId, 64).compile.toList)
        )
    }

  private object Q {
    val insertTx: Query[CardTransaction, CardTransaction] =
      sql"""INSERT INTO card_transactions (id, card_id, kind, amount_minor, currency, merchant,
                                            occurred_at, status, galileo_ref)
            VALUES $txC
            RETURNING id, card_id, kind, amount_minor, currency, merchant,
                      occurred_at, status, galileo_ref""".query(txC)

    val listFor: Query[(CardId, Int), CardTransaction] =
      sql"""SELECT id, card_id, kind, amount_minor, currency, merchant,
                   occurred_at, status, galileo_ref
            FROM card_transactions WHERE card_id = $cardIdC
            ORDER BY occurred_at DESC LIMIT $int4""".query(txC)

    val setStatus: Command[(TransactionStatus, TransactionId)] =
      sql"UPDATE card_transactions SET status = $statusC WHERE id = $txIdC".command

    val insertDispute: Query[Dispute, Dispute] =
      sql"""INSERT INTO disputes (id, transaction_id, user_id, reason, status, opened_at, resolved_at)
            VALUES $disputeC
            RETURNING id, transaction_id, user_id, reason, status, opened_at, resolved_at"""
        .query(disputeC)

    val listDisputes: Query[UserId, Dispute] =
      sql"""SELECT id, transaction_id, user_id, reason, status, opened_at, resolved_at
            FROM disputes WHERE user_id = $userIdC ORDER BY opened_at DESC"""
        .query(disputeC)
  }
}
