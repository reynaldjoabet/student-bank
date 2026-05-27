package sbank.db

import sbank.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait Cards[F[_]] {
  def insert(c: Card): F[Card]
  def find(id: CardId): F[Option[Card]]
  def listFor(userId: UserId): F[List[Card]]
  def setStatus(id: CardId, status: CardStatus): F[Unit]
  def adjustBalance(id: CardId, deltaMinor: Long): F[Unit]
}

object Cards {

  import Codecs.{card as cardC, cardId as cardIdC, userId as userIdC, cardStatus as statusC}
  import skunk.codec.all.int8

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Cards[F] =
    new Cards[F] {

      def insert(c: Card): F[Card] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(c)))
      def find(id: CardId): F[Option[Card]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
      def listFor(userId: UserId): F[List[Card]] =
        pool.use(
          _.prepare(Q.listFor).flatMap(_.stream(userId, 16).compile.toList)
        )
      def setStatus(id: CardId, status: CardStatus): F[Unit] =
        pool.use(_.prepare(Q.setStatus).flatMap(_.execute((status, id)))).void
      def adjustBalance(id: CardId, deltaMinor: Long): F[Unit] =
        pool.use(_.prepare(Q.adjust).flatMap(_.execute((deltaMinor, id)))).void
    }

  private object Q {
    val insert: Query[Card, Card] =
      sql"""INSERT INTO cards (id, user_id, galileo_prn, token, brand, last4, expiry, status,
                                credit_limit, balance_minor, apr_bps, issued_at, created_at)
            VALUES $cardC
            RETURNING id, user_id, galileo_prn, token, brand, last4, expiry, status,
                      credit_limit, balance_minor, apr_bps, issued_at, created_at"""
        .query(cardC)

    val byId: Query[CardId, Card] =
      sql"""SELECT id, user_id, galileo_prn, token, brand, last4, expiry, status,
                   credit_limit, balance_minor, apr_bps, issued_at, created_at
            FROM cards WHERE id = $cardIdC""".query(cardC)

    val listFor: Query[UserId, Card] =
      sql"""SELECT id, user_id, galileo_prn, token, brand, last4, expiry, status,
                   credit_limit, balance_minor, apr_bps, issued_at, created_at
            FROM cards WHERE user_id = $userIdC ORDER BY created_at""".query(
        cardC
      )

    val setStatus: Command[(CardStatus, CardId)] =
      sql"UPDATE cards SET status = $statusC WHERE id = $cardIdC".command

    val adjust: Command[(Long, CardId)] =
      sql"UPDATE cards SET balance_minor = balance_minor + $int8 WHERE id = $cardIdC".command
  }
}
