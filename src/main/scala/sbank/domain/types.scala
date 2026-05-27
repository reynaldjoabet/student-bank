package sbank.domain

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.util.UUID
import java.time.Instant
import scala.util.control.Exception.allCatch
// ---------- Identifiers ----------

type UserId = UserId.T
object UserId extends RefinedType[UUID, Pure] {}

type CardId = CardId.T
object CardId extends RefinedType[UUID, Pure] {}

type TransactionId = TransactionId.T
object TransactionId extends RefinedType[UUID, Pure] {}

type DisputeId = DisputeId.T
object DisputeId extends RefinedType[UUID, Pure] {}

type LoanId = LoanId.T
object LoanId extends RefinedType[UUID, Pure] {}

type LinkedAccountId = LinkedAccountId.T
object LinkedAccountId extends RefinedType[UUID, Pure] {}

type AutoPayId = AutoPayId.T
object AutoPayId extends RefinedType[UUID, Pure] {}

type PointsLedgerId = PointsLedgerId.T
object PointsLedgerId extends RefinedType[UUID, Pure] {}

type EducationVideoId = EducationVideoId.T
object EducationVideoId extends RefinedType[UUID, Pure] {}

type WatchProgressId = WatchProgressId.T
object WatchProgressId extends RefinedType[UUID, Pure] {}

type TaxDocumentId = TaxDocumentId.T
object TaxDocumentId extends RefinedType[UUID, Pure] {}

type NotificationId = NotificationId.T
object NotificationId extends RefinedType[UUID, Pure] {}

type SupportTicketId = SupportTicketId.T
object SupportTicketId extends RefinedType[UUID, Pure] {}

// ---------- Contact ----------

type EmailConstraint = Not[Blank] & MaxLength[254] & Match["""^[^@\s]+@[^@\s]+\.[^@\s]+$"""]
type Email = Email.T
object Email extends RefinedType[String, EmailConstraint] {}

type FullName = FullName.T
object FullName extends RefinedType[String, Not[Blank] & MaxLength[200]] {}

/** E.164 phone number. */
type PhoneE164 = PhoneE164.T
object PhoneE164 extends RefinedType[String, Match["""^\+[1-9][0-9]{7,14}$"""]] {}

type PasswordHash = PasswordHash.T
object PasswordHash extends RefinedType[String, Not[Blank] & MaxLength[120]] {}

// ---------- Sensitive PII handling ----------

/** SSN is *never* stored in plaintext. We keep an HMAC of the SSN (deterministic so we can look up by SSN at the
  * boundary) plus the last 4 for display. The raw SSN passes through the boundary briefly, validated by this type and
  * then discarded.
  */
type Ssn = Ssn.T
object Ssn extends RefinedType[String, FixedLength[9] & Match["^[0-9]{9}$"]] {}

/** Hex-encoded HMAC-SHA256 of the SSN. 64 hex chars. */
type SsnHash = SsnHash.T
object SsnHash extends RefinedType[String, FixedLength[64] & Match["^[0-9a-f]{64}$"]] {}

/** Last 4 digits of SSN, for "verify your identity" UX flows. */
type SsnLast4 = SsnLast4.T
object SsnLast4 extends RefinedType[String, FixedLength[4] & Match["^[0-9]{4}$"]] {}

// ---------- Card data (PCI-safe) ----------

/** Tokenised card reference issued by Galileo. */
type CardToken = CardToken.T
object CardToken extends RefinedType[String, Not[Blank] & MaxLength[128]] {}

/** Galileo "PRN" — the human-readable account-relationship number. */
type GalileoPrn = GalileoPrn.T
object GalileoPrn extends RefinedType[String, Match["^[0-9]{9,16}$"]] {}

type Last4 = Last4.T
object Last4 extends RefinedType[String, FixedLength[4] & Match["^[0-9]{4}$"]] {}

type CardExpiry = CardExpiry.T
object CardExpiry extends RefinedType[String, Match["""^(0[1-9]|1[0-2])/[0-9]{2}$"""]] {}

type CardBrand = CardBrand.T
object CardBrand extends RefinedType[String, Not[Blank] & MaxLength[32]] {}

// ---------- Money ----------

/** Amount in minor units (cents). Signed: positive = inflow / refund, negative = outflow / purchase.
  */
type AmountMinor = AmountMinor.T
object AmountMinor extends RefinedType[Long, Pure] {}

/** Strictly-positive amount — payments, credit limits, loan principal. */
type PositiveAmount = PositiveAmount.T
object PositiveAmount extends RefinedType[Long, Positive] {}

type CurrencyCode = CurrencyCode.T
object CurrencyCode extends RefinedType[String, Match["^[A-Z]{3}$"]] {
  val USD: CurrencyCode = CurrencyCode.applyUnsafe("USD")
}

// ---------- Credit scoring ----------

/** FICO credit score range: 300..850. Iron rejects anything outside the band so neither the model nor the UI can render
  * a synthetic 0 or 999.
  */
type CreditScore = CreditScore.T
object CreditScore extends RefinedType[Int, GreaterEqual[300] & LessEqual[850]] {}

/** Annual percentage rate in basis points (0..100_000 = 0%..1000% — caps usurious values).
  */
type AprBps = AprBps.T
object AprBps extends RefinedType[Int, GreaterEqual[0] & LessEqual[100_000]] {}

/** Credit limit in cents — strictly positive. */
type CreditLimit = CreditLimit.T
object CreditLimit extends RefinedType[Long, Positive] {}

// ---------- Points ----------

/** Earned/balance points — non-negative integer. */
type Points = Points.T
object Points extends RefinedType[Long, GreaterEqual[0]] {}

/** Cents-per-point conversion rate. 100 = 1 USD per point; 10 = $0.10 per point.
  */
type PointsPerCent = PointsPerCent.T
object PointsPerCent extends RefinedType[Int, Positive] {}

// ---------- Identifiers & text ----------

/** YouTube video id: 11 URL-safe characters. */
type YoutubeVideoId = YoutubeVideoId.T
object YoutubeVideoId
    extends RefinedType[
      String,
      FixedLength[11] & Match["^[A-Za-z0-9_-]{11}$"]
    ] {}

type Title = Title.T
object Title extends RefinedType[String, Not[Blank] & MaxLength[200]] {}

type Body = Body.T
object Body extends RefinedType[String, Not[Blank] & MaxLength[8000]] {}

/** Linked-account routing + account, similar shape to remittance project. */
type RoutingNumber = RoutingNumber.T
object RoutingNumber extends RefinedType[String, FixedLength[9] & Match["^[0-9]{9}$"]] {}

type AccountNumber = AccountNumber.T
object AccountNumber
    extends RefinedType[String, MinLength[
      6
    ] & MaxLength[34] & Match["^[A-Z0-9]+$"]] {}

// ---------- Storage references ----------

/** Object key into the blob store (Azure-flavoured). */
type BlobRef = BlobRef.T
object BlobRef
    extends RefinedType[String, Not[
      Blank
    ] & MaxLength[512] & Match["^[A-Za-z0-9._/-]+$"]] {}

// ---------- Enums ----------

enum KycStatus { case NotStarted, Pending, Approved, Rejected }

enum CardStatus { case Applied, Issued, Active, Frozen, Cancelled }

enum TransactionKind {
  case Purchase, Refund, Fee, Interest, Payment, CashbackAccrual, Adjustment
}

enum TransactionStatus { case Pending, Posted, Disputed, Reversed, Declined }

enum DisputeStatus { case Open, ResolvedCustomer, ResolvedMerchant, Withdrawn }

enum LoanKind { case Student, Personal }

enum LoanStatus {
  case Applied, Approved, Disbursed, Repaying, PaidOff, Defaulted, Rejected
}

enum PointsEventKind { case Earn, Redeem, Adjustment }

enum NotificationKind {
  case Transaction, Marketing, EducationNew, ScoreUpdate, Support
}

enum NotificationChannel { case Push, Email, Sms }

enum AutoPayCadence { case Weekly, BiWeekly, Monthly }

type NonEmpty = MinLength[1]
type FederationId = FederationId.T
object FederationId extends RefinedType[String, NonEmpty]

type Identifier = Identifier.T
object Identifier extends RefinedType[String, Not[Blank]]

given CanEqual[Identifier, Identifier] = CanEqual.derived
given CanEqual[Identifier, String] = CanEqual.derived
given CanEqual[String, Identifier] = CanEqual.derived

type GivenName = GivenName.T
object GivenName extends RefinedType[String, MinLength[1] & MaxLength[32]]

type Percentage = GreaterEqual[1] & LessEqual[100]

type ValidCode = Trimmed & Not[Blank] & MinLength[3] & MaxLength[64]

type ValidName = Trimmed & Not[Blank] & MinLength[3] & MaxLength[64]

final case class Coupon(code: Coupon.Code, discount: Coupon.Discount)

object Coupon {
  type Code = Code.T
  object Code extends RefinedType[String, ValidCode]

  type Discount = Discount.T
  object Discount extends RefinedType[Int, Percentage]
}

object Product {
  type Name = Name.T
  object Name extends RefinedType[String, ValidName]

  type Cost = Cost.T
  object Cost extends RefinedType[Double, Positive]

  type Revenue = Revenue.T
  object Revenue extends RefinedType[Int, Percentage]

  type Tax = Tax.T
  object Tax extends RefinedType[Int, Percentage]
}

final case class Product(
    name: Product.Name,
    cost: Product.Cost,
    revenue: Product.Revenue,
    tax: Product.Tax
)

/** Weekly budgeted hours constrained to the inclusive range `0` to `168`. */
type WeeklyHours = WeeklyHours.T

/** Refined type companion for `WeeklyHours`. */
object WeeklyHours extends RefinedType[Int, GreaterEqual[0] & LessEqual[168]]

/** Daily working hours constrained to the inclusive range `0` to `24`. */
type DailyHours = DailyHours.T

/** Refined type companion for `DailyHours`. */
object DailyHours extends RefinedType[Int, GreaterEqual[0] & LessEqual[24]]

type NonEmptyTrimmedLowerCase = Trimmed & LettersLowerCase & MinLength[1] & MaxLength[255]
type NonEmptyTrimmed = Trimmed & MinLength[1] & MaxLength[255]
type TokenPredicate = Trimmed & MinLength[1]
type NonEmpty2 = MinLength[1] & MaxLength[1000]

type PasswordPredicate =
  Match[
    "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%#*^,?)(&._-])[A-Za-z\\d@$!%#*^,?)(&._-]{8,72}$"
  ]
type EmailPredicate = Match["^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"] & MaxLength[255]
type OtpPredicate = Match["^[A-Z0-9]{6}$"]
type WahaIDPredicate = NonEmptyTrimmedLowerCase & EndWith["@c.us"]
type WahaGroupIDPredicate = NonEmptyTrimmedLowerCase & EndWith["@g.us"]
type WahaUserIDPredicate = NonEmptyTrimmedLowerCase & EndWith["@lid"]
type WhatsappIDPredicate = NonEmptyTrimmedLowerCase & EndWith["@s.whatsapp.net"]

trait RefinedTypeUUID extends RefinedType[UUID, Pure] {
  def eitherFromString(s: String): Either[String, T] =
    allCatch
      .either(apply(UUID.fromString(s)))
      .left
      .map(error => s"Invalid UUID format error: [${error.getMessage}]")
}

object AppName extends RefinedType[String, Pure]
type AppName = AppName.T

object UserID2 extends RefinedTypeUUID
type UserID2 = UserID2.T

object ActionAttemptID extends RefinedTypeUUID
type ActionAttemptID = ActionAttemptID.T

object TokenID extends RefinedTypeUUID
type TokenID = TokenID.T

object Email2 extends RefinedType[String, EmailPredicate]
type Emails = Email2.T

object FullName2 extends RefinedType[String, NonEmptyTrimmed]
type FullName2 = FullName2.T

object Password extends RefinedType[String, PasswordPredicate]
type Password = Password.T

object PasswordHash2 extends RefinedType[String, NonEmptyTrimmed]
type PasswordHash2 = PasswordHash2.T

object PhoneRegion extends RefinedType[String, NonEmptyTrimmed]
type PhoneRegion = PhoneRegion.T

object PhoneCountryCode extends RefinedType[String, NonEmptyTrimmed]
type PhoneCountryCode = PhoneCountryCode.T

object PhoneNationalNumber extends RefinedType[String, NonEmptyTrimmed]
type PhoneNationalNumber = PhoneNationalNumber.T

object PhoneNumberE164 extends RefinedType[String, NonEmptyTrimmed]
type PhoneNumberE164 = PhoneNumberE164.T

object Message extends RefinedType[String, NonEmpty]
type Message = Message.T

object Otp extends RefinedType[String, OtpPredicate]
type Otp = Otp.T

object OtpID extends RefinedTypeUUID
type OtpID = OtpID.T

object CreatedAt extends RefinedType[Instant, Pure]
type CreatedAt = CreatedAt.T

object UpdatedAt extends RefinedType[Instant, Pure]
type UpdatedAt = UpdatedAt.T

object ExpiresAt extends RefinedType[Instant, Pure]
type ExpiresAt = ExpiresAt.T

object RefreshToken extends RefinedType[String, TokenPredicate]
type RefreshToken = RefreshToken.T

object ResetPasswordToken extends RefinedType[String, TokenPredicate]
type ResetPasswordToken = ResetPasswordToken.T

object AccessToken extends RefinedType[String, TokenPredicate]
type AccessToken = AccessToken.T

object Attempts extends RefinedType[Int, Positive]
type Attempts = Attempts.T

object OrganizationID extends RefinedTypeUUID
type OrganizationID = OrganizationID.T

object OrganizationName extends RefinedType[String, NonEmptyTrimmed]
type OrganizationName = OrganizationName.T

object OrganizationSlug extends RefinedType[String, NonEmptyTrimmedLowerCase]
type OrganizationSlug = OrganizationSlug.T

object OrganizationEmail extends RefinedType[String, EmailPredicate]
type OrganizationEmail = OrganizationEmail.T

//  object OrganizationPhoneNumber extends RefinedType[PhoneNumber, Pure]
//  type OrganizationPhoneNumber = OrganizationPhoneNumber.T

object OrganizationAddressLine1 extends RefinedType[String, NonEmptyTrimmed]
type OrganizationAddressLine1 = OrganizationAddressLine1.T

object OrganizationAddressLine2 extends RefinedType[String, NonEmptyTrimmed]
type OrganizationAddressLine2 = OrganizationAddressLine2.T

object OrganizationCity extends RefinedType[String, NonEmptyTrimmed]
type OrganizationCity = OrganizationCity.T

object OrganizationPostalCode extends RefinedType[String, NonEmptyTrimmed]
type OrganizationPostalCode = OrganizationPostalCode.T

object OrganizationCountry extends RefinedType[String, NonEmptyTrimmed]
type OrganizationCountry = OrganizationCountry.T

/** Unique identifier for a task. */
type TaskId = UUID

/** Refined constraint for a non-empty task name. */
type TaskName = DescribedAs[Not[Empty], "The task name must be alphanumeric"]

/** Refined constraint for a non-empty task description. */
type TaskDescription =
  DescribedAs[Not[Empty], "The task description must be alphanumeric"]

/** Estimated duration of a task in hours. */
type TaskHours = TaskHours.T

/** Refined type companion for `TaskHours`, including arithmetic helpers and a `Monoid` instance.
  */
object TaskHours extends RefinedType[Int, Positive0]

/** Immutable task definition used inside manufacturings.
  *
  * @param id
  *   Stable task identifier.
  * @param name
  *   Human-readable task name.
  * @param taskDescription
  *   Optional task description.
  * @param requiredHours
  *   Estimated effort required to complete the task.
  */
final case class Task(
    id: TaskId,
    name: String :| TaskName,
    taskDescription: Option[String :| TaskDescription],
    requiredHours: TaskHours
)
