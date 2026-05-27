package sbank.service

import sbank.domain.*
import sbank.db.Users

import cats.effect.*
import cats.syntax.all.*
import java.time.Instant
import java.util.UUID
import com.password4j.Password

trait Auth[F[_]] {
  def signup(
      email: Email,
      phone: PhoneE164,
      password: String,
      fullName: FullName
  ): F[Either[Auth.Error, User]]
  def login(email: Email, password: String): F[Either[Auth.Error, User]]
}

object Auth {

  sealed trait Error
  object Error {
    case object EmailTaken extends Error
    case object PasswordTooWeak extends Error
    case object InvalidCredentials extends Error
  }

  def make[F[_]: Sync](users: Users[F]): Auth[F] = new Auth[F] {

    def signup(
        email: Email,
        phone: PhoneE164,
        password: String,
        fullName: FullName
    ): F[Either[Error, User]] =
      validatePassword(password) match {
        case Left(err) => Sync[F].pure(Left(err))
        case Right(_)  =>
          users.findByEmail(email).flatMap {
            case Some(_) => Sync[F].pure(Left(Error.EmailTaken))
            case None    =>
              for {
                hash <- Sync[F].delay(
                  Password
                    .hash(password)
                    .`with`(com.password4j.AlgorithmFinder.getBcryptInstance)
                    .getResult
                )
                now <- Sync[F].delay(Instant.now())
                u = User(
                  id = UserId.assume(UUID.randomUUID()),
                  email = email,
                  phone = phone,
                  passwordHash = PasswordHash.assume(hash),
                  fullName = fullName,
                  ssnHash = None,
                  ssnLast4 = None,
                  dateOfBirth = None,
                  kycStatus = KycStatus.NotStarted,
                  cachedCreditScore = None,
                  cachedCreditScoreAt = None,
                  createdAt = now
                )
                saved <- users.create(u)
              } yield Right(saved)
          }
      }

    def login(email: Email, password: String): F[Either[Error, User]] =
      users.findByEmail(email).map {
        case Some(u)
            if Password
              .check(password, u.passwordHash.value)
              .`with`(com.password4j.AlgorithmFinder.getBcryptInstance) =>
          Right(u)
        case _ => Left(Error.InvalidCredentials)
      }
  }

  private def validatePassword(p: String): Either[Error, Unit] =
    if (p.length < 8 || !p.exists(_.isDigit) || !p.exists(_.isLetter))
      Left(Error.PasswordTooWeak)
    else Right(())
}
