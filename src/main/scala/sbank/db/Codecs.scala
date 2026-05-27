package sbank.db

import sbank.domain.*
import skunk.*
import skunk.codec.all.*
import org.typelevel.twiddles.syntax.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.skunk.*
import java.time.{Instant, LocalDate, ZoneOffset}

object Codecs {

  // ---- ids ----
  val userId: Codec[UserId] = uuid.imap(UserId.assume)(_.value)
  val cardId: Codec[CardId] = uuid.imap(CardId.assume)(_.value)
  val transactionId: Codec[TransactionId] =
    uuid.imap(TransactionId.assume)(_.value)
  val disputeId: Codec[DisputeId] = uuid.imap(DisputeId.assume)(_.value)
  val loanId: Codec[LoanId] = uuid.imap(LoanId.assume)(_.value)
  val linkedAccountId: Codec[LinkedAccountId] =
    uuid.imap(LinkedAccountId.assume)(_.value)
  val autoPayId: Codec[AutoPayId] = uuid.imap(AutoPayId.assume)(_.value)
  val pointsLedgerId: Codec[PointsLedgerId] =
    uuid.imap(PointsLedgerId.assume)(_.value)
  val educationVideoId: Codec[EducationVideoId] =
    uuid.imap(EducationVideoId.assume)(_.value)
  val watchProgressId: Codec[WatchProgressId] =
    uuid.imap(WatchProgressId.assume)(_.value)
  val taxDocumentId: Codec[TaxDocumentId] =
    uuid.imap(TaxDocumentId.assume)(_.value)
  val notificationId: Codec[NotificationId] =
    uuid.imap(NotificationId.assume)(_.value)
  val supportTicketId: Codec[SupportTicketId] =
    uuid.imap(SupportTicketId.assume)(_.value)

  // ---- scalars ----
  val email: Codec[Email] =
    varchar(254).refined[EmailConstraint].imap(Email.assume)(_.value)
  val phone: Codec[PhoneE164] = varchar(16)
    .refined[Match["""^\+[1-9][0-9]{7,14}$"""]]
    .imap(PhoneE164.assume)(_.value)
  val passwordHash: Codec[PasswordHash] = varchar(120)
    .refined[Not[Blank] & MaxLength[120]]
    .imap(PasswordHash.assume)(_.value)
  val fullName: Codec[FullName] = varchar(200)
    .refined[Not[Blank] & MaxLength[200]]
    .imap(FullName.assume)(_.value)

  val ssnHash: Codec[SsnHash] = bpchar(64)
    .refined[FixedLength[64] & Match["^[0-9a-f]{64}$"]]
    .imap(SsnHash.assume)(_.value)
  val ssnLast4: Codec[SsnLast4] = bpchar(4)
    .refined[FixedLength[4] & Match["^[0-9]{4}$"]]
    .imap(SsnLast4.assume)(_.value)

  val cardToken: Codec[CardToken] = varchar(128)
    .refined[Not[Blank] & MaxLength[128]]
    .imap(CardToken.assume)(_.value)
  val galileoPrn: Codec[GalileoPrn] =
    varchar(16).refined[Match["^[0-9]{9,16}$"]].imap(GalileoPrn.assume)(_.value)
  val last4: Codec[Last4] = bpchar(4)
    .refined[FixedLength[4] & Match["^[0-9]{4}$"]]
    .imap(Last4.assume)(_.value)
  val cardExpiry: Codec[CardExpiry] = varchar(5)
    .refined[Match["""^(0[1-9]|1[0-2])/[0-9]{2}$"""]]
    .imap(CardExpiry.assume)(_.value)
  val cardBrand: Codec[CardBrand] = varchar(32)
    .refined[Not[Blank] & MaxLength[32]]
    .imap(CardBrand.assume)(_.value)

  //
  val amountMinor: Codec[AmountMinor] = int8.imap(AmountMinor.assume)(_.value)
  val positiveAmount: Codec[PositiveAmount] =
    int8.refined[Positive].imap(PositiveAmount.assume)(_.value)
  val currency: Codec[CurrencyCode] =
    bpchar(3).refined[Match["^[A-Z]{3}$"]].imap(CurrencyCode.assume)(_.value)
  val creditScore: Codec[CreditScore] = int4
    .refined[GreaterEqual[300] & LessEqual[850]]
    .imap(CreditScore.assume)(_.value)
  val aprBps: Codec[AprBps] = int4
    .refined[GreaterEqual[0] & LessEqual[100_000]]
    .imap(AprBps.assume)(_.value)
  val creditLimit: Codec[CreditLimit] =
    int8.refined[Positive].imap(CreditLimit.assume)(_.value)
  val points: Codec[Points] =
    int8.refined[GreaterEqual[0]].imap(Points.assume)(_.value)

  val youtubeId: Codec[YoutubeVideoId] =
    bpchar(11)
      .refined[FixedLength[11] & Match["^[A-Za-z0-9_-]{11}$"]]
      .imap(YoutubeVideoId.assume)(_.value)

  val title: Codec[Title] = varchar(200)
    .refined[Not[Blank] & MaxLength[200]]
    .imap(Title.assume)(_.value)
  val body: Codec[Body] =
    text.refined[Not[Blank] & MaxLength[8000]].imap(Body.assume)(_.value)

  val routingNumber: Codec[RoutingNumber] = bpchar(9)
    .refined[FixedLength[9] & Match["^[0-9]{9}$"]]
    .imap(RoutingNumber.assume)(_.value)
  val accountNumber: Codec[AccountNumber] = varchar(34)
    .refined[MinLength[6] & MaxLength[34] & Match["^[A-Z0-9]+$"]]
    .imap(AccountNumber.assume)(_.value)
  val blobRef: Codec[BlobRef] = varchar(512)
    .refined[Not[Blank] & MaxLength[512] & Match["^[A-Za-z0-9._/-]+$"]]
    .imap(BlobRef.assume)(_.value)

  // ---- enums ----

  val kycStatus: Codec[KycStatus] = varchar(16).eimap[KycStatus] {
    case "not_started" => Right(KycStatus.NotStarted)
    case "pending"     => Right(KycStatus.Pending)
    case "approved"    => Right(KycStatus.Approved)
    case "rejected"    => Right(KycStatus.Rejected)
    case o             => Left(s"unknown kyc status: $o")
  } {
    case KycStatus.NotStarted => "not_started"
    case KycStatus.Pending    => "pending"
    case KycStatus.Approved   => "approved"
    case KycStatus.Rejected   => "rejected"
  }

  val cardStatus: Codec[CardStatus] = varchar(12).eimap[CardStatus] {
    case "applied"   => Right(CardStatus.Applied)
    case "issued"    => Right(CardStatus.Issued)
    case "active"    => Right(CardStatus.Active)
    case "frozen"    => Right(CardStatus.Frozen)
    case "cancelled" => Right(CardStatus.Cancelled)
    case o           => Left(s"unknown card status: $o")
  } { _.toString.toLowerCase }

  val transactionKind: Codec[TransactionKind] =
    varchar(20).eimap[TransactionKind] {
      case "purchase"         => Right(TransactionKind.Purchase)
      case "refund"           => Right(TransactionKind.Refund)
      case "fee"              => Right(TransactionKind.Fee)
      case "interest"         => Right(TransactionKind.Interest)
      case "payment"          => Right(TransactionKind.Payment)
      case "cashback_accrual" => Right(TransactionKind.CashbackAccrual)
      case "adjustment"       => Right(TransactionKind.Adjustment)
      case o                  => Left(s"unknown tx kind: $o")
    } {
      case TransactionKind.Purchase        => "purchase"
      case TransactionKind.Refund          => "refund"
      case TransactionKind.Fee             => "fee"
      case TransactionKind.Interest        => "interest"
      case TransactionKind.Payment         => "payment"
      case TransactionKind.CashbackAccrual => "cashback_accrual"
      case TransactionKind.Adjustment      => "adjustment"
    }

  val transactionStatus: Codec[TransactionStatus] =
    varchar(12).eimap[TransactionStatus] {
      case "pending"  => Right(TransactionStatus.Pending)
      case "posted"   => Right(TransactionStatus.Posted)
      case "disputed" => Right(TransactionStatus.Disputed)
      case "reversed" => Right(TransactionStatus.Reversed)
      case "declined" => Right(TransactionStatus.Declined)
      case o          => Left(s"unknown tx status: $o")
    } { _.toString.toLowerCase }

  val disputeStatus: Codec[DisputeStatus] = varchar(24).eimap[DisputeStatus] {
    case "open"              => Right(DisputeStatus.Open)
    case "resolved_customer" => Right(DisputeStatus.ResolvedCustomer)
    case "resolved_merchant" => Right(DisputeStatus.ResolvedMerchant)
    case "withdrawn"         => Right(DisputeStatus.Withdrawn)
    case o                   => Left(s"unknown dispute status: $o")
  } {
    case DisputeStatus.Open             => "open"
    case DisputeStatus.ResolvedCustomer => "resolved_customer"
    case DisputeStatus.ResolvedMerchant => "resolved_merchant"
    case DisputeStatus.Withdrawn        => "withdrawn"
  }

  val loanKind: Codec[LoanKind] = varchar(16).eimap[LoanKind] {
    case "student"  => Right(LoanKind.Student)
    case "personal" => Right(LoanKind.Personal)
    case o          => Left(s"unknown loan kind: $o")
  } { _.toString.toLowerCase }

  val loanStatus: Codec[LoanStatus] = varchar(16).eimap[LoanStatus] {
    case "applied"   => Right(LoanStatus.Applied)
    case "approved"  => Right(LoanStatus.Approved)
    case "disbursed" => Right(LoanStatus.Disbursed)
    case "repaying"  => Right(LoanStatus.Repaying)
    case "paid_off"  => Right(LoanStatus.PaidOff)
    case "defaulted" => Right(LoanStatus.Defaulted)
    case "rejected"  => Right(LoanStatus.Rejected)
    case o           => Left(s"unknown loan status: $o")
  } {
    case LoanStatus.Applied   => "applied"
    case LoanStatus.Approved  => "approved"
    case LoanStatus.Disbursed => "disbursed"
    case LoanStatus.Repaying  => "repaying"
    case LoanStatus.PaidOff   => "paid_off"
    case LoanStatus.Defaulted => "defaulted"
    case LoanStatus.Rejected  => "rejected"
  }

  val pointsKind: Codec[PointsEventKind] = varchar(16).eimap[PointsEventKind] {
    case "earn"       => Right(PointsEventKind.Earn)
    case "redeem"     => Right(PointsEventKind.Redeem)
    case "adjustment" => Right(PointsEventKind.Adjustment)
    case o            => Left(s"unknown points kind: $o")
  } { _.toString.toLowerCase }

  val notificationKind: Codec[NotificationKind] =
    varchar(24).eimap[NotificationKind] {
      case "transaction"   => Right(NotificationKind.Transaction)
      case "marketing"     => Right(NotificationKind.Marketing)
      case "education_new" => Right(NotificationKind.EducationNew)
      case "score_update"  => Right(NotificationKind.ScoreUpdate)
      case "support"       => Right(NotificationKind.Support)
      case o               => Left(s"unknown notification kind: $o")
    } {
      case NotificationKind.Transaction  => "transaction"
      case NotificationKind.Marketing    => "marketing"
      case NotificationKind.EducationNew => "education_new"
      case NotificationKind.ScoreUpdate  => "score_update"
      case NotificationKind.Support      => "support"
    }

  val notificationChannel: Codec[NotificationChannel] =
    varchar(8).eimap[NotificationChannel] {
      case "push"  => Right(NotificationChannel.Push)
      case "email" => Right(NotificationChannel.Email)
      case "sms"   => Right(NotificationChannel.Sms)
      case o       => Left(s"unknown channel: $o")
    } { _.toString.toLowerCase }

  val autoPayCadence: Codec[AutoPayCadence] =
    varchar(16).eimap[AutoPayCadence] {
      case "weekly"    => Right(AutoPayCadence.Weekly)
      case "bi_weekly" => Right(AutoPayCadence.BiWeekly)
      case "monthly"   => Right(AutoPayCadence.Monthly)
      case o           => Left(s"unknown cadence: $o")
    } {
      case AutoPayCadence.Weekly   => "weekly"
      case AutoPayCadence.BiWeekly => "bi_weekly"
      case AutoPayCadence.Monthly  => "monthly"
    }

  private val instant: Codec[java.time.Instant] =
    timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))

  // ---- aggregates ----
  // skunk 1.x's `*:` chain produces right-nested Tuple2 pairs `(A, (B, (C, ...)))`.
  // Case class Mirrors are flat tuples `(A, B, C, ...)`, so `.to[T]` cannot derive
  // an Iso for >2 fields. We bridge with explicit `imap` and nested destructuring.

  val user: Codec[User] =
    (userId *: email *: phone *: passwordHash *: fullName *: ssnHash.opt *: ssnLast4.opt *: date.opt *:
      kycStatus *: creditScore.opt *: instant.opt *: instant).imap {
      case (
            id,
            (em, (ph, (pw, (fn, (sh, (sl, (dob, (kyc, (cs, (csa, ca))))))))))
          ) =>
        User(id, em, ph, pw, fn, sh, sl, dob, kyc, cs, csa, ca)
    }(u =>
      (
        u.id,
        (
          u.email,
          (
            u.phone,
            (
              u.passwordHash,
              (
                u.fullName,
                (
                  u.ssnHash,
                  (
                    u.ssnLast4,
                    (
                      u.dateOfBirth,
                      (
                        u.kycStatus,
                        (
                          u.cachedCreditScore,
                          (u.cachedCreditScoreAt, u.createdAt)
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    )

  val card: Codec[Card] =
    (cardId *: userId *: galileoPrn *: cardToken *: cardBrand *: last4 *: cardExpiry *: cardStatus *:
      creditLimit *: int8 *: aprBps *: instant.opt *: instant).imap {
      case (
            id,
            (
              uid,
              (
                prn,
                (tok, (br, (l4, (exp, (st, (cl, (bal, (apr, (iss, ca)))))))))
              )
            )
          ) =>
        Card(id, uid, prn, tok, br, l4, exp, st, cl, bal, apr, iss, ca)
    }(c =>
      (
        c.id,
        (
          c.userId,
          (
            c.galileoPrn,
            (
              c.token,
              (
                c.brand,
                (
                  c.last4,
                  (
                    c.expiry,
                    (
                      c.status,
                      (
                        c.creditLimit,
                        (c.balanceMinor, (c.aprBps, (c.issuedAt, c.createdAt)))
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    )

  val transaction: Codec[CardTransaction] =
    (transactionId *: cardId *: transactionKind *: amountMinor *: currency *: title.opt *:
      instant *: transactionStatus *: varchar(128).opt).imap {
      case (id, (cid, (k, (amt, (cur, (mer, (occ, (st, ref)))))))) =>
        CardTransaction(id, cid, k, amt, cur, mer, occ, st, ref)
    }(t =>
      (
        t.id,
        (
          t.cardId,
          (
            t.kind,
            (
              t.amountMinor,
              (
                t.currency,
                (t.merchant, (t.occurredAt, (t.status, t.galileoRef)))
              )
            )
          )
        )
      )
    )

  val dispute: Codec[Dispute] =
    (disputeId *: transactionId *: userId *: body *: disputeStatus *: instant *: instant.opt)
      .imap { case (id, (tid, (uid, (r, (st, (oa, ra)))))) =>
        Dispute(id, tid, uid, r, st, oa, ra)
      }(d =>
        (
          d.id,
          (
            d.transactionId,
            (d.userId, (d.reason, (d.status, (d.openedAt, d.resolvedAt))))
          )
        )
      )

  val loan: Codec[Loan] =
    (loanId *: userId *: loanKind *: positiveAmount *: aprBps *: int4 *: loanStatus *: int8 *:
      instant *: instant.opt).imap { case (id, (uid, (k, (prin, (apr, (tm, (st, (rem, (aa, da))))))))) =>
      Loan(id, uid, k, prin, apr, tm, st, rem, aa, da)
    }(l =>
      (
        l.id,
        (
          l.userId,
          (
            l.kind,
            (
              l.principalMinor,
              (
                l.aprBps,
                (
                  l.termMonths,
                  (l.status, (l.remainingMinor, (l.appliedAt, l.disbursedAt)))
                )
              )
            )
          )
        )
      )
    )

  val linkedAccount: Codec[LinkedBankAccount] =
    (linkedAccountId *: userId *: routingNumber *: last4 *: fullName *: varchar(
      128
    ).opt *: instant).imap { case (id, (uid, (rn, (l4, (fn, (pid, at)))))) =>
      LinkedBankAccount(id, uid, rn, l4, fn, pid, at)
    }(a =>
      (
        a.id,
        (
          a.userId,
          (
            a.routingNumber,
            (a.accountNumberLast4, (a.holderName, (a.plaidItemId, a.addedAt)))
          )
        )
      )
    )

  val autoPay: Codec[AutoPay] =
    (autoPayId *: userId *: cardId *: linkedAccountId *: autoPayCadence *: positiveAmount *: date *: bool)
      .imap { case (id, (uid, (cid, (src, (cad, (amt, (nxt, act))))))) =>
        AutoPay(id, uid, cid, src, cad, amt, nxt, act)
      }(a =>
        (
          a.id,
          (
            a.userId,
            (
              a.cardId,
              (
                a.sourceAccountId,
                (a.cadence, (a.amountMinor, (a.nextRunOn, a.active)))
              )
            )
          )
        )
      )

  val pointsLedger: Codec[PointsLedger] =
    (pointsLedgerId *: userId *: pointsKind *: points *: int8 *: transactionId.opt *: educationVideoId.opt *:
      title.opt *: instant).imap { case (id, (uid, (k, (amt, (cv, (rtid, (rvid, (n, ca)))))))) =>
      PointsLedger(id, uid, k, amt, cv, rtid, rvid, n, ca)
    }(p =>
      (
        p.id,
        (
          p.userId,
          (
            p.kind,
            (
              p.amount,
              (
                p.centsValue,
                (
                  p.relatedTransactionId,
                  (p.relatedVideoId, (p.note, p.createdAt))
                )
              )
            )
          )
        )
      )
    )

  val educationVideo: Codec[EducationVideo] =
    (educationVideoId *: youtubeId *: title *: body *: int4 *: points *: instant)
      .imap { case (id, (yt, (t, (desc, (dur, (pts, pa)))))) =>
        EducationVideo(id, yt, t, desc, dur, pts, pa)
      }(v =>
        (
          v.id,
          (
            v.youtubeId,
            (
              v.title,
              (
                v.description,
                (v.durationSeconds, (v.pointsReward, v.publishedAt))
              )
            )
          )
        )
      )

  val watchProgress: Codec[WatchProgress] =
    (watchProgressId *: userId *: educationVideoId *: int4 *: bool *: instant.opt)
      .imap { case (id, (uid, (vid, (sw, (comp, ra))))) =>
        WatchProgress(id, uid, vid, sw, comp, ra)
      }(w =>
        (
          w.id,
          (
            w.userId,
            (w.videoId, (w.secondsWatched, (w.completed, w.rewardedAt)))
          )
        )
      )

  val taxDocument: Codec[TaxDocument] =
    (taxDocumentId *: userId *: int4 *: varchar(
      32
    ) *: blobRef *: date *: instant).imap { case (id, (uid, (yr, (dt, (br, (io, ca)))))) =>
      TaxDocument(id, uid, yr, dt, br, io, ca)
    }(t =>
      (
        t.id,
        (
          t.userId,
          (t.taxYear, (t.documentType, (t.blobRef, (t.issuedOn, t.createdAt))))
        )
      )
    )

  val notificationPref: Codec[NotificationPref] =
    (userId *: notificationKind *: notificationChannel *: bool).imap { case (uid, (k, (ch, en))) =>
      NotificationPref(uid, k, ch, en)
    }(p => (p.userId, (p.kind, (p.channel, p.enabled))))

  val notificationEvent: Codec[NotificationEvent] =
    (notificationId *: userId *: notificationKind *: title *: body *: notificationChannel *:
      instant.opt *: instant.opt *: instant).imap { case (id, (uid, (k, (t, (b, (ch, (da, (ra, ca)))))))) =>
      NotificationEvent(id, uid, k, t, b, ch, da, ra, ca)
    }(e =>
      (
        e.id,
        (
          e.userId,
          (
            e.kind,
            (
              e.title,
              (e.body, (e.channel, (e.deliveredAt, (e.readAt, e.createdAt))))
            )
          )
        )
      )
    )

  val supportTicket: Codec[SupportTicket] =
    (supportTicketId *: userId *: varchar(
      16
    ) *: title *: body *: instant *: instant.opt).imap { case (id, (uid, (ch, (subj, (b, (ca, cla)))))) =>
      SupportTicket(id, uid, ch, subj, b, ca, cla)
    }(t =>
      (
        t.id,
        (
          t.userId,
          (t.channel, (t.subject, (t.body, (t.createdAt, t.closedAt))))
        )
      )
    )
}
