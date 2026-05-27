package sbank.http

import sbank.domain.*

import io.circe.{Json as CirceJson, *}
import io.circe.generic.semiauto.*
import io.github.iltotore.iron.circe.given

object Json {

  // ---- enums ----
  given Encoder[KycStatus] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[CardStatus] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[TransactionKind] = Encoder.encodeString.contramap {
    case TransactionKind.Purchase        => "purchase"
    case TransactionKind.Refund          => "refund"
    case TransactionKind.Fee             => "fee"
    case TransactionKind.Interest        => "interest"
    case TransactionKind.Payment         => "payment"
    case TransactionKind.CashbackAccrual => "cashback_accrual"
    case TransactionKind.Adjustment      => "adjustment"
  }
  given Encoder[TransactionStatus] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[DisputeStatus] = Encoder.encodeString.contramap {
    case DisputeStatus.Open             => "open"
    case DisputeStatus.ResolvedCustomer => "resolved_customer"
    case DisputeStatus.ResolvedMerchant => "resolved_merchant"
    case DisputeStatus.Withdrawn        => "withdrawn"
  }
  given Encoder[LoanKind] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Decoder[LoanKind] = Decoder.decodeString.emap {
    case "student"  => Right(LoanKind.Student)
    case "personal" => Right(LoanKind.Personal)
    case o          => Left(s"unknown loan kind: $o")
  }
  given Encoder[LoanStatus] = Encoder.encodeString.contramap {
    case LoanStatus.Applied   => "applied"
    case LoanStatus.Approved  => "approved"
    case LoanStatus.Disbursed => "disbursed"
    case LoanStatus.Repaying  => "repaying"
    case LoanStatus.PaidOff   => "paid_off"
    case LoanStatus.Defaulted => "defaulted"
    case LoanStatus.Rejected  => "rejected"
  }
  given Encoder[PointsEventKind] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[NotificationKind] = Encoder.encodeString.contramap {
    case NotificationKind.Transaction  => "transaction"
    case NotificationKind.Marketing    => "marketing"
    case NotificationKind.EducationNew => "education_new"
    case NotificationKind.ScoreUpdate  => "score_update"
    case NotificationKind.Support      => "support"
  }
  given Decoder[NotificationKind] = Decoder.decodeString.emap {
    case "transaction"   => Right(NotificationKind.Transaction)
    case "marketing"     => Right(NotificationKind.Marketing)
    case "education_new" => Right(NotificationKind.EducationNew)
    case "score_update"  => Right(NotificationKind.ScoreUpdate)
    case "support"       => Right(NotificationKind.Support)
    case o               => Left(s"unknown notification kind: $o")
  }
  given Encoder[NotificationChannel] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Decoder[NotificationChannel] = Decoder.decodeString.emap {
    case "push"  => Right(NotificationChannel.Push)
    case "email" => Right(NotificationChannel.Email)
    case "sms"   => Right(NotificationChannel.Sms)
    case o       => Left(s"unknown channel: $o")
  }
  given Encoder[AutoPayCadence] = Encoder.encodeString.contramap {
    case AutoPayCadence.Weekly   => "weekly"
    case AutoPayCadence.BiWeekly => "bi_weekly"
    case AutoPayCadence.Monthly  => "monthly"
  }
  given Decoder[AutoPayCadence] = Decoder.decodeString.emap {
    case "weekly"    => Right(AutoPayCadence.Weekly)
    case "bi_weekly" => Right(AutoPayCadence.BiWeekly)
    case "monthly"   => Right(AutoPayCadence.Monthly)
    case o           => Left(s"unknown cadence: $o")
  }

  // ---- user (PII-sanitised) ----
  given Encoder[User] = Encoder.instance(u =>
    CirceJson.obj(
      "id" -> Encoder.encodeString.apply(u.id.value.toString),
      "email" -> Encoder.encodeString.apply(u.email.value),
      "phone" -> Encoder.encodeString.apply(u.phone.value),
      "fullName" -> Encoder.encodeString.apply(u.fullName.value),
      "ssnLast4" -> u.ssnLast4.fold(CirceJson.Null)(s => Encoder.encodeString.apply(s.value)),
      "kycStatus" -> Encoder[KycStatus].apply(u.kycStatus),
      "cachedCreditScore" -> u.cachedCreditScore.fold(CirceJson.Null)(s => Encoder.encodeInt.apply(s.value)),
      "createdAt" -> Encoder[java.time.Instant].apply(u.createdAt)
    )
  )
  val userEncoder: Encoder[User] = summon[Encoder[User]]

  given Encoder[Card] = deriveEncoder
  given Encoder[CardTransaction] = deriveEncoder
  given Encoder[Dispute] = deriveEncoder
  given Encoder[Loan] = deriveEncoder
  given Encoder[LinkedBankAccount] = deriveEncoder
  given Encoder[AutoPay] = deriveEncoder
  given Encoder[PointsLedger] = deriveEncoder
  given Encoder[EducationVideo] = deriveEncoder
  given Encoder[WatchProgress] = deriveEncoder
  given Encoder[TaxDocument] = deriveEncoder
  given Encoder[NotificationPref] = deriveEncoder
  given Encoder[NotificationEvent] = deriveEncoder

  // ---- request bodies ----
  final case class SignupBody(
      email: Email,
      phone: PhoneE164,
      password: String,
      fullName: FullName
  )
  given Decoder[SignupBody] = deriveDecoder

  final case class LoginBody(email: Email, password: String)
  given Decoder[LoginBody] = deriveDecoder

  final case class KycSubmitBody(
      fullName: FullName,
      dateOfBirth: java.time.LocalDate,
      ssn: Ssn,
      documentImageUrl: String,
      selfieUrl: String
  )
  given Decoder[KycSubmitBody] = deriveDecoder

  final case class DisputeBody(transactionId: TransactionId, reason: Body)
  given Decoder[DisputeBody] = deriveDecoder

  final case class LoanApplyBody(
      kind: LoanKind,
      principalMinor: PositiveAmount,
      termMonths: Int
  )
  given Decoder[LoanApplyBody] = deriveDecoder

  final case class RedeemBody(points: Points, ontoCard: CardId)
  given Decoder[RedeemBody] = deriveDecoder

  final case class ProgressBody(secondsWatched: Int, completed: Boolean)
  given Decoder[ProgressBody] = deriveDecoder

  final case class NotifPrefBody(
      kind: NotificationKind,
      channel: NotificationChannel,
      enabled: Boolean
  )
  given Decoder[NotifPrefBody] = deriveDecoder

  final case class AddLinkedAccountBody(
      routingNumber: RoutingNumber,
      accountNumberLast4: Last4,
      holderName: FullName,
      plaidItemId: Option[String]
  )
  given Decoder[AddLinkedAccountBody] = deriveDecoder
}
