package sbank.http

import sbank.domain.*
import sbank.db.*
import sbank.service.*

import cats.effect.*
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

import java.time.Instant
import java.util.UUID

final class Routes[F[_]: Async](
    auth: Auth[F],
    onboarding: Onboarding[F],
    cardSvc: CardService[F],
    pointsSvc: PointsService[F],
    creditSvc: CreditScoreService[F],
    taxDocSvc: TaxDocService[F],
    users: Users[F],
    cards: Cards[F],
    cardTxs: CardTransactions[F],
    loans: Loans[F],
    linkedAccounts: LinkedAccounts[F],
    education: Education[F],
    notifications: Notifications[F]
) extends Http4sDsl[F] {

  import Json.given

  private object Principal {
    def unapply(req: Request[F]): Option[UserId] =
      req.headers
        .get(org.typelevel.ci.CIString("X-User-Id"))
        .flatMap(h =>
          scala.util
            .Try(UUID.fromString(h.head.value))
            .toOption
            .map(UserId.assume)
        )
  }

  private object CardVar {
    def unapply(s: String): Option[CardId] = uuidVar(s, CardId.assume)
  }
  private object TxVar {
    def unapply(s: String): Option[TransactionId] =
      uuidVar(s, TransactionId.assume)
  }
  private object VideoVar {
    def unapply(s: String): Option[EducationVideoId] =
      uuidVar(s, EducationVideoId.assume)
  }
  private object TaxDocVar {
    def unapply(s: String): Option[TaxDocumentId] =
      uuidVar(s, TaxDocumentId.assume)
  }
  private object NotificationVar {
    def unapply(s: String): Option[NotificationId] =
      uuidVar(s, NotificationId.assume)
  }

  private def uuidVar[A](s: String, f: UUID => A): Option[A] =
    scala.util.Try(UUID.fromString(s)).toOption.map(f)

  private def authed(
      req: Request[F]
  )(body: UserId => F[Response[F]]): F[Response[F]] =
    Principal.unapply(req) match {
      case None      => Forbidden()
      case Some(uid) => body(uid)
    }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case GET -> Root / "health" =>
      Ok(Map("status" -> "ok").asJson)

    // ---- auth ----

    case req @ POST -> Root / "auth" / "signup" =>
      req
        .as[Json.SignupBody]
        .flatMap(b =>
          auth.signup(b.email, b.phone, b.password, b.fullName).flatMap {
            case Right(u)                    => Created(u)
            case Left(Auth.Error.EmailTaken) =>
              Conflict(Map("error" -> "email_taken").asJson)
            case Left(Auth.Error.PasswordTooWeak) =>
              BadRequest(Map("error" -> "password_too_weak").asJson)
            case Left(other) =>
              BadRequest(Map("error" -> other.toString).asJson)
          }
        )

    case req @ POST -> Root / "auth" / "login" =>
      req
        .as[Json.LoginBody]
        .flatMap(b =>
          auth.login(b.email, b.password).flatMap {
            case Right(u) => Ok(u)
            case Left(_)  => Forbidden(Map("error" -> "invalid").asJson)
          }
        )

    // ---- me ----

    case req @ GET -> Root / "me" =>
      authed(req)(uid => users.find(uid).flatMap(_.fold(NotFound())(u => Ok(u))))

    // ---- KYC ----

    case req @ POST -> Root / "kyc" =>
      authed(req)(uid =>
        req
          .as[Json.KycSubmitBody]
          .flatMap(b =>
            onboarding
              .submitKyc(
                uid,
                b.fullName,
                b.dateOfBirth,
                b.ssn,
                b.documentImageUrl,
                b.selfieUrl
              )
              .flatMap(s => Accepted(Map("status" -> s.toString.toLowerCase).asJson))
          )
      )

    // ---- credit cards ----

    case req @ POST -> Root / "cards" / "apply" =>
      authed(req)(uid =>
        cardSvc.apply(uid).flatMap {
          case Right(c)  => Created(c)
          case Left(err) => BadRequest(Map("error" -> err.toString).asJson)
        }
      )

    case req @ GET -> Root / "cards" =>
      authed(req)(uid => cards.listFor(uid).flatMap(Ok(_)))

    case req @ POST -> Root / "cards" / CardVar(cid) / "activate" =>
      authed(req)(_ => cardSvc.activate(cid).flatMap(_ => NoContent()))

    case req @ POST -> Root / "cards" / CardVar(cid) / "freeze" =>
      authed(req)(_ => cardSvc.freeze(cid).flatMap(_ => NoContent()))

    case req @ GET -> Root / "cards" / CardVar(cid) / "transactions" :? Limit(
          limit
        ) =>
      authed(req)(_ => cardTxs.listFor(cid, limit.getOrElse(50)).flatMap(Ok(_)))

    case req @ POST -> Root / "cards" / "transactions" / TxVar(
          txId
        ) / "dispute" =>
      authed(req)(uid =>
        req.as[Json.DisputeBody].flatMap { b =>
          val d = Dispute(
            DisputeId.assume(UUID.randomUUID()),
            txId,
            uid,
            b.reason,
            DisputeStatus.Open,
            Instant.now(),
            None
          )
          cardTxs.openDispute(d).flatMap(Created(_))
        }
      )

    // ---- credit score ----

    case req @ GET -> Root / "credit-score" =>
      authed(req)(uid =>
        creditSvc.current(uid).flatMap {
          case Some(s) =>
            Ok(io.circe.Json.obj("score" -> io.circe.Json.fromInt(s.value)))
          case None => NotFound(Map("error" -> "no_score_yet").asJson)
        }
      )

    case req @ POST -> Root / "credit-score" / "refresh" =>
      authed(req)(uid =>
        creditSvc.refresh(uid).flatMap {
          case Some(s) =>
            Ok(io.circe.Json.obj("score" -> io.circe.Json.fromInt(s.value)))
          case None => BadRequest(Map("error" -> "kyc_or_ssn_required").asJson)
        }
      )

    // ---- loans ----

    case req @ GET -> Root / "loans" =>
      authed(req)(uid => loans.listFor(uid).flatMap(Ok(_)))

    case req @ POST -> Root / "loans" / "apply" =>
      authed(req)(uid =>
        req.as[Json.LoanApplyBody].flatMap { b =>
          val l = Loan(
            LoanId.assume(UUID.randomUUID()),
            uid,
            b.kind,
            b.principalMinor,
            AprBps.applyUnsafe(799),
            b.termMonths,
            LoanStatus.Applied,
            remainingMinor = b.principalMinor.value,
            appliedAt = Instant.now(),
            disbursedAt = None
          )
          loans.insert(l).flatMap(Created(_))
        }
      )

    // ---- linked accounts + payments ----

    case req @ POST -> Root / "linked-accounts" =>
      authed(req)(uid =>
        req.as[Json.AddLinkedAccountBody].flatMap { b =>
          val a = LinkedBankAccount(
            LinkedAccountId.assume(UUID.randomUUID()),
            uid,
            b.routingNumber,
            b.accountNumberLast4,
            b.holderName,
            b.plaidItemId,
            Instant.now()
          )
          linkedAccounts.add(a).flatMap(Created(_))
        }
      )

    case req @ GET -> Root / "linked-accounts" =>
      authed(req)(uid => linkedAccounts.listFor(uid).flatMap(Ok(_)))

    // ---- points ----

    case req @ GET -> Root / "points" / "balance" =>
      authed(req)(uid => pointsSvc.balance(uid).flatMap(b => Ok(Map("balance" -> b).asJson)))

    case req @ POST -> Root / "points" / "redeem" =>
      authed(req)(uid =>
        req
          .as[Json.RedeemBody]
          .flatMap(b =>
            pointsSvc.redeem(uid, b.points, b.ontoCard).flatMap {
              case Right(cents) => Ok(Map("creditedCents" -> cents).asJson)
              case Left(err)    => BadRequest(Map("error" -> err.toString).asJson)
            }
          )
      )

    // ---- education ----

    case GET -> Root / "education" / "videos" :? Limit(limit) =>
      education.listVideos(limit.getOrElse(20)).flatMap(Ok(_))

    case req @ POST -> Root / "education" / "videos" / VideoVar(
          vid
        ) / "progress" =>
      authed(req)(uid =>
        req.as[Json.ProgressBody].flatMap { b =>
          val wp = WatchProgress(
            WatchProgressId.assume(UUID.randomUUID()),
            uid,
            vid,
            b.secondsWatched,
            b.completed,
            None
          )
          for {
            saved <- education.saveProgress(wp)
            // Auto-award points on completion (idempotent via PointsService).
            _ <-
              if (b.completed) pointsSvc.awardForVideo(uid, vid).void
              else Sync[F].unit
            r <- Ok(saved)
          } yield r
        }
      )

    // ---- tax documents ----

    case req @ GET -> Root / "tax-documents" =>
      authed(req)(uid => taxDocSvc.list(uid).flatMap(Ok(_)))

    case req @ GET -> Root / "tax-documents" / TaxDocVar(did) / "download" =>
      authed(req)(_ =>
        taxDocSvc.signedUrlFor(did, ttlSeconds = 300).flatMap {
          case Some(url) => Ok(Map("url" -> url).asJson)
          case None      => NotFound()
        }
      )

    // ---- notifications ----

    case req @ GET -> Root / "notifications" :? Limit(limit) =>
      authed(req)(uid => notifications.listFor(uid, limit.getOrElse(50)).flatMap(Ok(_)))

    case req @ POST -> Root / "notifications" / NotificationVar(nid) / "read" =>
      authed(req)(_ => notifications.markRead(nid, Instant.now()).flatMap(_ => NoContent()))

    case req @ PUT -> Root / "notifications" / "prefs" =>
      authed(req)(uid =>
        req
          .as[Json.NotifPrefBody]
          .flatMap(b =>
            notifications
              .upsertPref(NotificationPref(uid, b.kind, b.channel, b.enabled))
              .flatMap(_ => NoContent())
          )
      )
  }

  private object Limit extends OptionalQueryParamDecoderMatcher[Int]("limit")
}
