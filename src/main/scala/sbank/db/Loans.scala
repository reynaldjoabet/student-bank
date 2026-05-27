package sbank.db

import sbank.domain.*
import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait Loans[F[_]] {
  def insert(l: Loan): F[Loan]
  def listFor(userId: UserId): F[List[Loan]]
  def find(id: LoanId): F[Option[Loan]]
  def setStatus(id: LoanId, status: LoanStatus): F[Unit]
}

object Loans {

  import Codecs.{loan as loanC, loanId as loanIdC, loanStatus as statusC, userId as userIdC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Loans[F] =
    new Loans[F] {

      def insert(l: Loan): F[Loan] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(l)))
      def find(id: LoanId): F[Option[Loan]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
      def listFor(userId: UserId): F[List[Loan]] =
        pool.use(
          _.prepare(Q.listFor).flatMap(_.stream(userId, 32).compile.toList)
        )
      def setStatus(id: LoanId, status: LoanStatus): F[Unit] =
        pool.use(_.prepare(Q.setStatus).flatMap(_.execute((status, id)))).void
    }

  private object Q {
    val insert: Query[Loan, Loan] =
      sql"""INSERT INTO loans (id, user_id, kind, principal_minor, apr_bps, term_months,
                                status, remaining_minor, applied_at, disbursed_at)
            VALUES $loanC
            RETURNING id, user_id, kind, principal_minor, apr_bps, term_months,
                      status, remaining_minor, applied_at, disbursed_at"""
        .query(loanC)

    val byId: Query[LoanId, Loan] =
      sql"""SELECT id, user_id, kind, principal_minor, apr_bps, term_months,
                   status, remaining_minor, applied_at, disbursed_at
            FROM loans WHERE id = $loanIdC""".query(loanC)

    val listFor: Query[UserId, Loan] =
      sql"""SELECT id, user_id, kind, principal_minor, apr_bps, term_months,
                   status, remaining_minor, applied_at, disbursed_at
            FROM loans WHERE user_id = $userIdC ORDER BY applied_at DESC"""
        .query(loanC)

    val setStatus: Command[(LoanStatus, LoanId)] =
      sql"UPDATE loans SET status = $statusC WHERE id = $loanIdC".command
  }
}
