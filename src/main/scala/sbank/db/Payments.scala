package sbank.db

import sbank.domain.*
import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

import java.time.LocalDate

trait LinkedAccounts[F[_]] {
  def add(a: LinkedBankAccount): F[LinkedBankAccount]
  def listFor(userId: UserId): F[List[LinkedBankAccount]]
  def find(id: LinkedAccountId): F[Option[LinkedBankAccount]]
}

object LinkedAccounts {
  import Codecs.{linkedAccount as accountC, linkedAccountId as accountIdC, userId as userIdC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): LinkedAccounts[F] =
    new LinkedAccounts[F] {

      def add(a: LinkedBankAccount): F[LinkedBankAccount] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(a)))
      def listFor(userId: UserId): F[List[LinkedBankAccount]] =
        pool.use(
          _.prepare(Q.listFor).flatMap(_.stream(userId, 8).compile.toList)
        )
      def find(id: LinkedAccountId): F[Option[LinkedBankAccount]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
    }

  private object Q {
    val insert: Query[LinkedBankAccount, LinkedBankAccount] =
      sql"""INSERT INTO linked_bank_accounts (id, user_id, routing_number, account_number_last4,
                                                holder_name, plaid_item_id, added_at)
            VALUES $accountC
            RETURNING id, user_id, routing_number, account_number_last4,
                      holder_name, plaid_item_id, added_at""".query(accountC)
    val listFor: Query[UserId, LinkedBankAccount] =
      sql"""SELECT id, user_id, routing_number, account_number_last4,
                   holder_name, plaid_item_id, added_at
            FROM linked_bank_accounts WHERE user_id = $userIdC ORDER BY added_at"""
        .query(accountC)
    val byId: Query[LinkedAccountId, LinkedBankAccount] =
      sql"""SELECT id, user_id, routing_number, account_number_last4,
                   holder_name, plaid_item_id, added_at
            FROM linked_bank_accounts WHERE id = $accountIdC""".query(accountC)
  }
}

trait AutoPays[F[_]] {
  def insert(ap: AutoPay): F[AutoPay]
  def listFor(userId: UserId): F[List[AutoPay]]
  def dueOn(date: LocalDate): F[List[AutoPay]]
  def deactivate(id: AutoPayId): F[Unit]
  def advanceNextRun(id: AutoPayId, next: LocalDate): F[Unit]
}

object AutoPays {
  import Codecs.{autoPay as autoPayC, autoPayId as autoPayIdC, userId as userIdC}
  import skunk.codec.all.date

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): AutoPays[F] =
    new AutoPays[F] {

      def insert(ap: AutoPay): F[AutoPay] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(ap)))
      def listFor(userId: UserId): F[List[AutoPay]] =
        pool.use(
          _.prepare(Q.listFor).flatMap(_.stream(userId, 16).compile.toList)
        )
      def dueOn(d: LocalDate): F[List[AutoPay]] =
        pool.use(_.prepare(Q.dueOn).flatMap(_.stream(d, 256).compile.toList))
      def deactivate(id: AutoPayId): F[Unit] =
        pool.use(_.prepare(Q.deactivate).flatMap(_.execute(id))).void
      def advanceNextRun(id: AutoPayId, next: LocalDate): F[Unit] =
        pool.use(_.prepare(Q.advance).flatMap(_.execute((next, id)))).void
    }

  private object Q {
    val insert: Query[AutoPay, AutoPay] =
      sql"""INSERT INTO auto_pays (id, user_id, card_id, source_account_id,
                                    cadence, amount_minor, next_run_on, active)
            VALUES $autoPayC
            RETURNING id, user_id, card_id, source_account_id, cadence, amount_minor, next_run_on, active"""
        .query(autoPayC)

    val listFor: Query[UserId, AutoPay] =
      sql"""SELECT id, user_id, card_id, source_account_id, cadence, amount_minor, next_run_on, active
            FROM auto_pays WHERE user_id = $userIdC""".query(autoPayC)

    val dueOn: Query[LocalDate, AutoPay] =
      sql"""SELECT id, user_id, card_id, source_account_id, cadence, amount_minor, next_run_on, active
            FROM auto_pays WHERE active AND next_run_on <= $date""".query(
        autoPayC
      )

    val deactivate: Command[AutoPayId] =
      sql"UPDATE auto_pays SET active = false WHERE id = $autoPayIdC".command

    val advance: Command[(LocalDate, AutoPayId)] =
      sql"UPDATE auto_pays SET next_run_on = $date WHERE id = $autoPayIdC".command
  }
}
