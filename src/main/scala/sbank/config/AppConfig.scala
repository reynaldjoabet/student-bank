package sbank.config

import cats.effect.Sync
import cats.syntax.all.*
import com.typesafe.config.ConfigFactory
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.pureconfig.given
// `_root_.` bypasses the `pureconfig` sub-package iron brings into scope
import _root_.pureconfig.*
import _root_.pureconfig.error.{CannotConvert, ConfigReaderException}

import scala.concurrent.duration.FiniteDuration

// ---------- Refined config primitives ----------

type PortNumber = PortNumber.T
object PortNumber extends RefinedType[Int, GreaterEqual[1] & LessEqual[65535]]

type NonEmptyString = NonEmptyString.T
object NonEmptyString extends RefinedType[String, Not[Blank]]

type PositiveInt = PositiveInt.T
object PositiveInt extends RefinedType[Int, Positive]

type NonNegativeInt = NonNegativeInt.T
object NonNegativeInt extends RefinedType[Int, GreaterEqual[0]]

type PositiveLong = PositiveLong.T
object PositiveLong extends RefinedType[Long, Positive]

// ---------- Enums ----------

/** Postgres SSL modes; mirrors libpq's sslmode parameter. */
enum SslMode { case Disable, Allow, Prefer, Require, VerifyCa, VerifyFull }
object SslMode {
  given ConfigReader[SslMode] = ConfigReader[String].emap { raw =>
    raw.trim.toLowerCase.replace('_', '-') match {
      case "disable"     => Right(Disable)
      case "allow"       => Right(Allow)
      case "prefer"      => Right(Prefer)
      case "require"     => Right(Require)
      case "verify-ca"   => Right(VerifyCa)
      case "verify-full" => Right(VerifyFull)
      case o             =>
        Left(
          CannotConvert(
            o,
            "SslMode",
            "expected: disable | allow | prefer | require | verify-ca | verify-full"
          )
        )
    }
  }

  extension (m: SslMode) {
    def asPostgres: String = m match {
      case Disable    => "disable"
      case Allow      => "allow"
      case Prefer     => "prefer"
      case Require    => "require"
      case VerifyCa   => "verify-ca"
      case VerifyFull => "verify-full"
    }
  }
}

enum LogLevel { case Trace, Debug, Info, Warn, Error }
object LogLevel {
  given ConfigReader[LogLevel] = ConfigReader[String].emap { raw =>
    raw.trim.toLowerCase match {
      case "trace" => Right(Trace)
      case "debug" => Right(Debug)
      case "info"  => Right(Info)
      case "warn"  => Right(Warn)
      case "error" => Right(Error)
      case o       =>
        Left(
          CannotConvert(
            o,
            "LogLevel",
            "expected: trace | debug | info | warn | error"
          )
        )
    }
  }
}

enum SecretsBackend { case Env, Vault }
object SecretsBackend {
  given ConfigReader[SecretsBackend] = ConfigReader[String].emap { raw =>
    raw.trim.toLowerCase match {
      case "env"   => Right(Env)
      case "vault" => Right(Vault)
      case o       =>
        Left(CannotConvert(o, "SecretsBackend", "expected: env | vault"))
    }
  }
}

// ---------- Config case classes ----------

final case class DbConfig(
    host: NonEmptyString,
    port: PortNumber,
    user: NonEmptyString,
    /** Optional in dev; mandatory in any environment with `ssl != disable`. */
    password: Option[NonEmptyString],
    database: NonEmptyString,
    ssl: SslMode,
    poolMin: NonNegativeInt,
    poolMax: PositiveInt,
    connectTimeout: FiniteDuration,
    socketTimeout: FiniteDuration,
    statementTimeout: FiniteDuration,
    idleTimeout: FiniteDuration,
    maxLifetime: FiniteDuration,
    leakDetectionThreshold: FiniteDuration,
    applicationName: NonEmptyString
) derives ConfigReader

final case class HttpConfig(
    host: NonEmptyString,
    port: PortNumber,
    requestTimeout: FiniteDuration,
    idleTimeout: FiniteDuration,
    responseHeaderTimeout: FiniteDuration,
    shutdownTimeout: FiniteDuration,
    maxHeaderSize: PositiveInt,
    maxEntitySize: PositiveLong,
    maxConnections: PositiveInt
) derives ConfigReader

final case class ObservabilityConfig(
    serviceName: NonEmptyString,
    environment: NonEmptyString,
    /** Empty string means tracing export is disabled. */
    otelEndpoint: String,
    logLevel: LogLevel,
    metricsEnabled: Boolean,
    tracingEnabled: Boolean
) derives ConfigReader

final case class JwtConfig(
    issuer: NonEmptyString,
    audience: NonEmptyString,
    accessTokenTtl: FiniteDuration,
    refreshTokenTtl: FiniteDuration,
    /** HS256+ key; rotate via secrets manager. */
    signingKey: NonEmptyString
) derives ConfigReader

final case class SecurityConfig(
    ssnPepperSeed: NonEmptyString,
    passwordHashRounds: PositiveInt,
    jwt: JwtConfig,
    secretsBackend: SecretsBackend,
    allowedOrigins: List[String]
) derives ConfigReader

final case class AppConfig(
    db: DbConfig,
    http: HttpConfig,
    observability: ObservabilityConfig,
    security: SecurityConfig
) derives ConfigReader

object AppConfig {

  /** Default Lightbend Config (loads application.conf + reference.conf + env vars).
    */
  def load[F[_]: Sync]: F[AppConfig] =
    Sync[F].delay(ConfigFactory.load()).flatMap { raw =>
      Sync[F].fromEither(
        ConfigSource
          .fromConfig(raw)
          .at("app")
          .load[AppConfig]
          .leftMap(failures => ConfigReaderException[AppConfig](failures))
      )
    }

  /** Test/override entrypoint — load from a custom HOCON string. */
  def loadFromString[F[_]: Sync](hocon: String): F[AppConfig] =
    Sync[F].delay(ConfigFactory.parseString(hocon).resolve()).flatMap { raw =>
      Sync[F].fromEither(
        ConfigSource
          .fromConfig(raw)
          .at("app")
          .load[AppConfig]
          .leftMap(failures => ConfigReaderException[AppConfig](failures))
      )
    }
}
