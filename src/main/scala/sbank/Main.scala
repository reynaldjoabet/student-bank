package sbank

import cats.effect.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.metrics.Meter.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import sbank.config.*
import sbank.db.*
import sbank.external.*
import sbank.http.Routes
import sbank.service.*
import skunk.Session

object Main extends IOApp.Simple {

  def run: IO[Unit] = {
    val logger = Slf4jLogger.getLogger[IO]

    val server: Resource[IO, Unit] =
      for {
        cfg <- Resource.eval(AppConfig.load[IO])

        pool <- Session
          .Builder[IO]
          .pooled(
            max = cfg.db.poolMax.value
          )

        // ---- repositories ----
        usersR = Users.make[IO](pool)
        cardsR = Cards.make[IO](pool)
        cardTxsR = CardTransactions.make[IO](pool)
        loansR = Loans.make[IO](pool)
        linkedR = LinkedAccounts.make[IO](pool)
        autoPaysR = AutoPays.make[IO](pool)
        pointsR = Points.make[IO](pool)
        educationR = Education.make[IO](pool)
        taxDocsR = TaxDocs.make[IO](pool)
        notifR = Notifications.make[IO](pool)

        // ---- external sandboxes ----
        galileo <- Resource.eval(Galileo.sandbox[IO])
        kyc <- Resource.eval(Kyc.sandbox[IO])
        bureau <- Resource.eval(CreditBureau.sandbox[IO])
        msg <- Resource.eval(Messaging.sandbox[IO](m => logger.info(m)))
        crm <- Resource.eval(Crm.sandbox[IO])
        videos <- Resource.eval(VideoCatalog.sandbox[IO])
        blob <- Resource.eval(BlobStorage.sandbox[IO])

        // ---- application services ----
        pepper = Onboarding.derivePepper(cfg.security.ssnPepperSeed.value)
        authSvc = Auth.make[IO](usersR)
        onboardSvc = Onboarding.make[IO](usersR, kyc, bureau, pepper)
        cardSvc = CardService.make[IO](usersR, cardsR, galileo)
        pointsSvc = PointsService.make[IO](pointsR, cardsR, educationR)
        creditSvc = CreditScoreService.make[IO](usersR, bureau)
        taxDocSvc = TaxDocService.make[IO](taxDocsR, blob)

        routes = new Routes[IO](
          authSvc,
          onboardSvc,
          cardSvc,
          pointsSvc,
          creditSvc,
          taxDocSvc,
          usersR,
          cardsR,
          cardTxsR,
          loansR,
          linkedR,
          educationR,
          notifR
        ).routes
        app = Logger.httpApp(logHeaders = true, logBody = false)(
          routes.orNotFound
        )

        host <- Resource.eval(
          IO.fromOption(Host.fromString(cfg.http.host.value))(
            new IllegalArgumentException("bad host")
          )
        )
        port <- Resource.eval(
          IO.fromOption(Port.fromInt(cfg.http.port.value))(
            new IllegalArgumentException("bad port")
          )
        )

        _ <- EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(app)
          .build
        _ <- Resource.eval(logger.info(s"Listening on http://$host:$port"))
        _ <- Resource.eval(
          IO.pure((autoPaysR, msg, crm, videos))
        ) // seams used by upcoming workers
      } yield ()

    server.useForever
  }
}
