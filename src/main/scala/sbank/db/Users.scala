package sbank.db

import sbank.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*

import java.time.Instant
trait Users[F[_]] {
  def create(u: User): F[User]
  def find(id: UserId): F[Option[User]]
  def findByEmail(email: Email): F[Option[User]]
  def findBySsnHash(hash: SsnHash): F[Option[User]]
  def updateKyc(
      id: UserId,
      status: KycStatus,
      ssnHash: Option[SsnHash],
      ssnLast4: Option[SsnLast4],
      dob: Option[java.time.LocalDate]
  ): F[Unit]
  def cacheCreditScore(id: UserId, score: CreditScore, at: Instant): F[Unit]
}

object Users {

  import Codecs.{user as userC, userId as userIdC, email as emailC, kycStatus, ssnHash, ssnLast4, creditScore}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Users[F] =
    new Users[F] {

      def create(u: User): F[User] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(u)))
      def find(id: UserId): F[Option[User]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
      def findByEmail(e: Email): F[Option[User]] =
        pool.use(_.prepare(Q.byEmail).flatMap(_.option(e)))
      def findBySsnHash(h: SsnHash): F[Option[User]] =
        pool.use(_.prepare(Q.bySsn).flatMap(_.option(h)))

      def updateKyc(
          id: UserId,
          status: KycStatus,
          ssnH: Option[SsnHash],
          ssnL: Option[SsnLast4],
          dob: Option[java.time.LocalDate]
      ): F[Unit] =
        pool
          .use(
            _.prepare(Q.setKyc)
              .flatMap(_.execute((status, ssnH, ssnL, dob, id)))
          )
          .void

      def cacheCreditScore(
          id: UserId,
          score: CreditScore,
          at: Instant
      ): F[Unit] =
        pool
          .use(
            _.prepare(Q.cacheScore).flatMap(
              _.execute((score, at.atOffset(java.time.ZoneOffset.UTC), id))
            )
          )
          .void
    }

  private object Q {
    val insert: Query[User, User] =
      sql"""INSERT INTO users (id, email, phone, password_hash, full_name, ssn_hash, ssn_last4,
                                date_of_birth, kyc_status, cached_credit_score, cached_credit_score_at, created_at)
            VALUES $userC
            RETURNING id, email, phone, password_hash, full_name, ssn_hash, ssn_last4,
                      date_of_birth, kyc_status, cached_credit_score, cached_credit_score_at, created_at"""
        .query(userC)

    val byId: Query[UserId, User] =
      sql"""SELECT id, email, phone, password_hash, full_name, ssn_hash, ssn_last4,
                   date_of_birth, kyc_status, cached_credit_score, cached_credit_score_at, created_at
            FROM users WHERE id = $userIdC""".query(userC)

    val byEmail: Query[Email, User] =
      sql"""SELECT id, email, phone, password_hash, full_name, ssn_hash, ssn_last4,
                   date_of_birth, kyc_status, cached_credit_score, cached_credit_score_at, created_at
            FROM users WHERE email = $emailC""".query(userC)

    val bySsn: Query[SsnHash, User] =
      sql"""SELECT id, email, phone, password_hash, full_name, ssn_hash, ssn_last4,
                   date_of_birth, kyc_status, cached_credit_score, cached_credit_score_at, created_at
            FROM users WHERE ssn_hash = $ssnHash""".query(userC)

    val setKyc: Command[
      (
          KycStatus,
          Option[SsnHash],
          Option[SsnLast4],
          Option[java.time.LocalDate],
          UserId
      )
    ] =
      sql"""UPDATE users
            SET kyc_status = $kycStatus,
                ssn_hash   = ${ssnHash.opt},
                ssn_last4  = ${ssnLast4.opt},
                date_of_birth = ${date.opt}
            WHERE id = $userIdC""".command

    val cacheScore: Command[(CreditScore, java.time.OffsetDateTime, UserId)] =
      sql"""UPDATE users
            SET cached_credit_score = $creditScore, cached_credit_score_at = $timestamptz
            WHERE id = $userIdC""".command
  }
}
