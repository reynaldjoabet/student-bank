# student-bank
A Scala 3 backend for the student-banking app from the brief: signup + KYC,
credit-card application underwritten by FICO score, transactions + disputes,
points engine (cashback + education rewards), credit-score caching, tax
documents, notifications, and Galileo / Jumio / Experian / SendGrid / HubSpot
/ YouTube / Azure Blob seams.

## Stack

- Scala 3.3.7 — **braces-only** (`-no-indent`)
- [Iron](https://github.com/Iltotore/iron) + `iron-skunk` + `iron-circe`
- [Skunk](https://typelevel.org/skunk/) on Postgres
- http4s (Ember) + Circe + Cats Effect 3
- bcrypt, log4cats / logback

## Iron earning its keep

```scala
type CreditScore   = Int    :| (GreaterEqual[300] & LessEqual[850])   // FICO domain
type AprBps        = Int    :| (GreaterEqual[0]   & LessEqual[100_000]) // anti-usury cap
type CreditLimit   = Long   :| Positive
type Points        = Long   :| GreaterEqual[0]
type Ssn           = String :| (FixedLength[9] & Match["^[0-9]{9}$"])
type SsnHash       = String :| (FixedLength[64] & Match["^[0-9a-f]{64}$"])
type SsnLast4      = String :| (FixedLength[4]  & Match["^[0-9]{4}$"])
type CardToken     = String :| (Not[Blank] & MaxLength[128])
type Last4         = String :| (FixedLength[4]  & Match["^[0-9]{4}$"])
type YoutubeVideoId = String :| (FixedLength[11] & Match["^[A-Za-z0-9_-]{11}$"])
```

## SSN handling

`Ssn` is the only place the raw value exists in the type system, and it crosses
exactly one service boundary (`Onboarding.submitKyc`). Inside that call:

1. `SsnHash` is computed via HMAC-SHA256 with a server-side pepper sourced from
   `SSN_PEPPER_SEED` (in production: KMS / Key Vault). The hash is deterministic,
   so the bank can still resolve "do we know this SSN?" without storing the
   plaintext.
2. `SsnLast4` is extracted for the UX flow ("confirm the last 4 digits").
3. The raw `Ssn` is **discarded** — never written to the DB, never logged. The
   schema has no plaintext SSN column.

## Underwriting policy

Centralised in [`CardService.underwrite`](src/main/scala/sbank/service/CardService.scala#L80-L89):

```
< 580         -> rejected
580 .. 659    -> $500 limit, 29.99% APR (29.99% = 2_999 bps)
660 .. 719    -> $1,000 limit, 24.99% APR
720 +         -> $2,000 limit, 19.99% APR
```

All four limits are `CreditLimit :| Positive` so a "zero limit" can't slip
through; all four APRs are `AprBps :| (>=0 & <=100_000)` so a typo can't
encode 9000% APR. Tune by editing one table.

## Points engine

- **Earn on purchases** — `awardForPurchase` writes a ledger row at 1 point
  per dollar.
- **Earn on education** — `awardForVideo` is idempotent: once `rewardedAt` is
  set on the `watch_progress` row, second-claim attempts return `AlreadyRewarded`.
- **Redeem** — convert points to a card credit at the configured cents-per-point.
  Insufficient balance is checked against `points_ledger.balance_for(user_id)`.

## API

```
POST /auth/signup, /auth/login
GET  /me
POST /kyc                       { fullName, dateOfBirth, ssn, documentImageUrl, selfieUrl }

POST /cards/apply               (underwrites from cached score)
GET  /cards
POST /cards/{id}/activate
POST /cards/{id}/freeze
GET  /cards/{id}/transactions?limit=
POST /cards/transactions/{txId}/dispute

GET  /credit-score
POST /credit-score/refresh

GET  /loans
POST /loans/apply

POST /linked-accounts
GET  /linked-accounts

GET  /points/balance
POST /points/redeem             { points, ontoCard }

GET  /education/videos?limit=
POST /education/videos/{id}/progress     { secondsWatched, completed }
                                          (completion auto-claims the points reward)

GET  /tax-documents
GET  /tax-documents/{id}/download         returns a short-lived signed URL

GET  /notifications
POST /notifications/{id}/read
PUT  /notifications/prefs                  { kind, channel, enabled }
```

## Running

```bash
createdb sbank
psql sbank < src/main/resources/db/schema.sql

export DB_USER=postgres
export DB_PASSWORD=postgres
export DB_NAME=sbank
export SSN_PEPPER_SEED=replace-me-from-kms
sbt run
```

All upstream integrations (Galileo, Jumio, Experian, SendGrid, push, HubSpot,YouTube playlist sync, Azure Blob) wire to sandbox implementations in
`Main.scala`. Replace each one when you have credentials — a single trait per integration.

## Intentional seams

- **JWT auth**: routes trust `X-User-Id`. Add an upstream JWT-validating
  middleware in production.
- **Galileo webhooks**: card-event callbacks need a route that updates
  `card_transactions` (post → posted, refund → reversed, etc.).
- **AutoPay worker**: the schema + repo are in place. Add an fs2-scheduled job
  that pulls `auto_pays.dueOn(today)` and posts payments via Galileo + the
  linked account.
- **Interest accrual**: a daily job should walk `cards` and post `interest`
  transactions per APR.
- **Real Experian scoring**: sandbox returns 620..759 uniformly. Plug in the real bureau, and remember to rate-limit and cache 
- **YouTube ingestion**: `VideoCatalog.syncPlaylist` is a no-op; needs the
  YouTube Data API client plus a scheduled sync to surface new videos.
- **Mobile push delivery**: `Messaging.sendPush` is the trait; the brief calls
  out *native* push (APNS + FCM) — replace the sandbox with both clients.
- **Statement generation**: `CardStatement` is modelled but generation is
  TODO — typically a monthly job + a tax-style PDF render → blob upload.

1. `Plain refined type — A :| C`

`type Email = String :| Not[Blank]`

- Same type as the base. `Email` and `String` are interchangeable with `:|` (refinement is structural — a `String :| Not[Blank]` IS-A String, and any other `String :| Not[Blank]` is the same type).
- No nominal identity: if you also have type `Username = String :| Not[Blank]`, then `Email` and `Username` are the same type. Passing an `Email` where a `Username` is expected just works.

Use when: you want a constraint, but don't care about distinguishing two same-constraint types.

2. `Newtype-style refined — RefinedType[A, C]`
```scala
type Email = Email.T
object Email extends RefinedType[String, Not[Blank]]
```
- Distinct opaque type wrapping `String :| Not[Blank]`. `Email.T` and `Username.T` (even with identical constraints) are different types.
- Still `<: String` at compile time, still erases to String at runtime
- Adds nominal identity on top of the refinement.

3. `Pure newtype (no constraint) — RefinedType[A, Pure]`
```scala
type UserId = UserId.T
object UserId extends RefinedType[UUID, Pure]
```

## Refined type definition

Many changes related to `RefinedTypeOps` definition have been introduced to provide better ergonomy.

In 2.x:

```scala
opaque type Temperature = Double :| Positive
object Temperature extends RefinedTypeOps[Double, Positive, Temperature]
```

In 3.x:

```scala
type Temperature = Temperature.T
object Temperature extends RefinedType[Double, Positive]
```

- `RefinedTypeOps` is now `RefinedType`
- All newtypes are opaque, you can no longer make transparent types

You also no longer need to duplicate the constraint type, therefore, the following pattern is obsolete:

```scala
type TemperatureR = DescribedAs[Positive, "Temperature should be positive"]
opaque type Temperature = Double :| TemperatureR
object Temperature extends RefinedTypeOps[Double, TemperatureR, Temperature]
```