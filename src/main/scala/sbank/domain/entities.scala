package sbank.domain

import java.time.{Instant, LocalDate, YearMonth}

// ---------- User & identity ----------

final case class User(
    id: UserId,
    email: Email,
    phone: PhoneE164,
    passwordHash: PasswordHash,
    fullName: FullName,
    /** Deterministic SSN hash; null until KYC submission completes. */
    ssnHash: Option[SsnHash],
    ssnLast4: Option[SsnLast4],
    dateOfBirth: Option[LocalDate],
    kycStatus: KycStatus,
    /** Last computed credit score (cached; refreshed by [[CreditScoreService]]).
      */
    cachedCreditScore: Option[CreditScore],
    cachedCreditScoreAt: Option[Instant],
    createdAt: Instant
)

// ---------- Cards ----------

final case class Card(
    id: CardId,
    userId: UserId,
    /** Galileo's PRN — joins our row to their account. */
    galileoPrn: GalileoPrn,
    token: CardToken,
    brand: CardBrand,
    last4: Last4,
    expiry: CardExpiry,
    status: CardStatus,
    creditLimit: CreditLimit,
    /** Outstanding balance in cents — positive means user owes us money. */
    balanceMinor: Long,
    aprBps: AprBps,
    issuedAt: Option[Instant],
    createdAt: Instant
)

// ---------- Transactions & disputes ----------

final case class CardTransaction(
    id: TransactionId,
    cardId: CardId,
    kind: TransactionKind,
    /** Signed; sign carries direction (negative for purchases). */
    amountMinor: AmountMinor,
    currency: CurrencyCode,
    merchant: Option[Title],
    occurredAt: Instant,
    status: TransactionStatus,
    /** Galileo reference for the underlying authorisation / capture. */
    galileoRef: Option[String]
)

final case class Dispute(
    id: DisputeId,
    transactionId: TransactionId,
    userId: UserId,
    reason: Body,
    status: DisputeStatus,
    openedAt: Instant,
    resolvedAt: Option[Instant]
)

// ---------- Loans ----------

final case class Loan(
    id: LoanId,
    userId: UserId,
    kind: LoanKind,
    principalMinor: PositiveAmount,
    aprBps: AprBps,
    termMonths: Int,
    status: LoanStatus,
    /** Outstanding principal remaining; capped >= 0 by service logic. */
    remainingMinor: Long,
    appliedAt: Instant,
    disbursedAt: Option[Instant]
)

// ---------- Linked external accounts & autopay ----------

final case class LinkedBankAccount(
    id: LinkedAccountId,
    userId: UserId,
    routingNumber: RoutingNumber,
    accountNumberLast4: Last4,
    holderName: FullName,
    plaidItemId: Option[String], // if using Plaid for verification
    addedAt: Instant
)

final case class AutoPay(
    id: AutoPayId,
    userId: UserId,
    cardId: CardId,
    sourceAccountId: LinkedAccountId,
    cadence: AutoPayCadence,
    amountMinor: PositiveAmount,
    nextRunOn: LocalDate,
    active: Boolean
)

// ---------- Points / rewards ----------

final case class PointsLedger(
    id: PointsLedgerId,
    userId: UserId,
    kind: PointsEventKind,
    amount: Points,
    /** Cents value at the moment of the transaction; useful for audits of conversion rates.
      */
    centsValue: Long,
    relatedTransactionId: Option[TransactionId],
    relatedVideoId: Option[EducationVideoId],
    note: Option[Title],
    createdAt: Instant
)

final case class PointsBalance(
    userId: UserId,
    balance: Points,
    /** Convertible at the active rate to USD cents. */
    centsValue: Long
)

// ---------- Education ----------

final case class EducationVideo(
    id: EducationVideoId,
    youtubeId: YoutubeVideoId,
    title: Title,
    description: Body,
    durationSeconds: Int,
    pointsReward: Points,
    publishedAt: Instant
)

final case class WatchProgress(
    id: WatchProgressId,
    userId: UserId,
    videoId: EducationVideoId,
    secondsWatched: Int,
    completed: Boolean,
    rewardedAt: Option[Instant]
)

// ---------- Tax documents ----------

final case class TaxDocument(
    id: TaxDocumentId,
    userId: UserId,
    /** Year the document covers (e.g. 2026 for a 1099 issued in early 2027). */
    taxYear: Int,
    documentType: String, // '1099-INT' | '1099-MISC' | '1098-E' (student loan interest)
    blobRef: BlobRef,
    issuedOn: LocalDate,
    createdAt: Instant
)

// ---------- Notifications ----------

final case class NotificationPref(
    userId: UserId,
    kind: NotificationKind,
    channel: NotificationChannel,
    enabled: Boolean
)

final case class NotificationEvent(
    id: NotificationId,
    userId: UserId,
    kind: NotificationKind,
    title: Title,
    body: Body,
    channel: NotificationChannel,
    deliveredAt: Option[Instant],
    readAt: Option[Instant],
    createdAt: Instant
)

// ---------- Support ----------

final case class SupportTicket(
    id: SupportTicketId,
    userId: UserId,
    channel: String, // 'phone' | 'email' | 'chat'
    subject: Title,
    body: Body,
    createdAt: Instant,
    closedAt: Option[Instant]
)

// ---------- Reporting ----------

final case class CardStatement(
    cardId: CardId,
    period: YearMonth,
    openingBalanceMinor: Long,
    purchasesMinor: Long,
    paymentsMinor: Long,
    feesMinor: Long,
    interestMinor: Long,
    closingBalanceMinor: Long,
    minimumPaymentMinor: Long,
    dueOn: LocalDate
)
