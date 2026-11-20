-- Student-bank schema (PostgreSQL 18+, tuned for 19). Idempotent.
--
-- Conventions
--   * Primary keys are UUIDv7. The app generates them (sbank.domain.Uuids.timeOrdered);
--     the uuidv7() default (core since PG 18) keeps manual inserts time-ordered too,
--     so new keys append to the right edge of the B-tree instead of splitting random pages.
--   * Every foreign-key column has an index. Without one, ON DELETE CASCADE / SET NULL /
--     RESTRICT from the parent has to scan the whole child table.
--   * Indexes match the ORDER BY of the queries in sbank.db.*, so list endpoints
--     read rows in index order with no sort step.
--   * CHECK constraints mirror the string encodings in sbank.db.Codecs.
--   * Tables with frequent in-place updates use fillfactor 90 so those updates
--     can stay HOT (no index writes, less bloat).
--
-- Maintenance (PG 19): reclaim bloat on hot tables without blocking reads or writes:
--   REPACK (CONCURRENTLY) cards;
--   REPACK (CONCURRENTLY) watch_progress;
-- REPACK replaces VACUUM FULL / CLUSTER, which take an ACCESS EXCLUSIVE lock.

DO $$
BEGIN
    IF current_setting('server_version_num')::int < 180000 THEN
        RAISE EXCEPTION 'student-bank schema requires PostgreSQL 18 or later (uuidv7()), found %',
            current_setting('server_version');
    END IF;
END
$$;

-- ---------- Users (no plaintext SSN ever) ----------

CREATE TABLE IF NOT EXISTS users (
    id                       UUID PRIMARY KEY DEFAULT uuidv7(),
    email                    VARCHAR(254) NOT NULL UNIQUE,
    phone                    VARCHAR(16)  NOT NULL UNIQUE,
    password_hash            VARCHAR(120) NOT NULL,
    full_name                VARCHAR(200) NOT NULL,
    ssn_hash                 CHAR(64) UNIQUE,
    ssn_last4                CHAR(4),
    date_of_birth            DATE,
    kyc_status               VARCHAR(16)  NOT NULL DEFAULT 'not_started'
                             CHECK (kyc_status IN ('not_started','pending','approved','rejected')),
    cached_credit_score      INT          CHECK (cached_credit_score BETWEEN 300 AND 850),
    cached_credit_score_at   TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------- Cards ----------

-- balance_minor is updated on every transaction and is not indexed, so leave
-- page headroom for HOT updates.
CREATE TABLE IF NOT EXISTS cards (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    galileo_prn     VARCHAR(16)  NOT NULL UNIQUE,
    token           VARCHAR(128) NOT NULL,
    brand           VARCHAR(32)  NOT NULL,
    last4           CHAR(4)      NOT NULL,
    expiry          VARCHAR(5)   NOT NULL,
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('applied','issued','active','frozen','cancelled')),
    credit_limit    BIGINT       NOT NULL CHECK (credit_limit > 0),
    balance_minor   BIGINT       NOT NULL DEFAULT 0,
    apr_bps         INT          NOT NULL CHECK (apr_bps >= 0 AND apr_bps <= 100000),
    issued_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
) WITH (fillfactor = 90);

-- Cards.listFor: WHERE user_id = ? ORDER BY created_at
DROP INDEX IF EXISTS cards_user_idx;
CREATE INDEX IF NOT EXISTS cards_user_created_idx ON cards(user_id, created_at);

-- ---------- Transactions ----------

CREATE TABLE IF NOT EXISTS card_transactions (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    card_id         UUID         NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    kind            VARCHAR(20)  NOT NULL
                    CHECK (kind IN ('purchase','refund','fee','interest','payment','cashback_accrual','adjustment')),
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    merchant        VARCHAR(200),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('pending','posted','disputed','reversed','declined')),
    galileo_ref     VARCHAR(128)
);

-- CardTransactions.listFor: WHERE card_id = ? ORDER BY occurred_at DESC LIMIT n
CREATE INDEX IF NOT EXISTS tx_card_time_idx ON card_transactions(card_id, occurred_at DESC);

-- ---------- Disputes ----------

CREATE TABLE IF NOT EXISTS disputes (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    transaction_id  UUID         NOT NULL REFERENCES card_transactions(id) ON DELETE CASCADE,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason          TEXT         NOT NULL,
    status          VARCHAR(24)  NOT NULL CHECK (status IN ('open','resolved_customer','resolved_merchant','withdrawn')),
    opened_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    CHECK (resolved_at IS NULL OR resolved_at >= opened_at)
);

-- CardTransactions.listDisputes: WHERE user_id = ? ORDER BY opened_at DESC
CREATE INDEX IF NOT EXISTS disputes_user_opened_idx ON disputes(user_id, opened_at DESC);
-- FK: cascade from card_transactions
CREATE INDEX IF NOT EXISTS disputes_transaction_idx ON disputes(transaction_id);

-- ---------- Loans ----------

CREATE TABLE IF NOT EXISTS loans (
    id                UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id           UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind              VARCHAR(16)  NOT NULL CHECK (kind IN ('student','personal')),
    principal_minor   BIGINT       NOT NULL CHECK (principal_minor > 0),
    apr_bps           INT          NOT NULL CHECK (apr_bps >= 0 AND apr_bps <= 100000),
    term_months       INT          NOT NULL CHECK (term_months > 0),
    status            VARCHAR(16)  NOT NULL
                      CHECK (status IN ('applied','approved','disbursed','repaying','paid_off','defaulted','rejected')),
    remaining_minor   BIGINT       NOT NULL DEFAULT 0 CHECK (remaining_minor >= 0),
    applied_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    disbursed_at      TIMESTAMPTZ
);

-- Loans.listFor: WHERE user_id = ? ORDER BY applied_at DESC
DROP INDEX IF EXISTS loans_user_idx;
CREATE INDEX IF NOT EXISTS loans_user_applied_idx ON loans(user_id, applied_at DESC);

-- ---------- Linked external bank accounts ----------

CREATE TABLE IF NOT EXISTS linked_bank_accounts (
    id                       UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id                  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    routing_number           CHAR(9)      NOT NULL CHECK (routing_number ~ '^[0-9]{9}$'),
    account_number_last4     CHAR(4)      NOT NULL CHECK (account_number_last4 ~ '^[0-9]{4}$'),
    holder_name              VARCHAR(200) NOT NULL,
    plaid_item_id            VARCHAR(128),
    added_at                 TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- LinkedAccounts.listFor: WHERE user_id = ? ORDER BY added_at
CREATE INDEX IF NOT EXISTS linked_accounts_user_added_idx ON linked_bank_accounts(user_id, added_at);

-- next_run_on is advanced after every run; keep headroom for HOT updates.
CREATE TABLE IF NOT EXISTS auto_pays (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id             UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id             UUID         NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    source_account_id   UUID         NOT NULL REFERENCES linked_bank_accounts(id) ON DELETE RESTRICT,
    cadence             VARCHAR(16)  NOT NULL CHECK (cadence IN ('weekly','bi_weekly','monthly')),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    next_run_on         DATE         NOT NULL,
    active              BOOLEAN      NOT NULL DEFAULT true
) WITH (fillfactor = 90);

-- AutoPays.dueOn: WHERE active AND next_run_on <= ?
CREATE INDEX IF NOT EXISTS auto_pays_next_run_idx ON auto_pays(next_run_on) WHERE active;
-- AutoPays.listFor, and the FKs (cascade from users/cards, RESTRICT check from linked_bank_accounts)
CREATE INDEX IF NOT EXISTS auto_pays_user_idx           ON auto_pays(user_id);
CREATE INDEX IF NOT EXISTS auto_pays_card_idx           ON auto_pays(card_id);
CREATE INDEX IF NOT EXISTS auto_pays_source_account_idx ON auto_pays(source_account_id);

-- ---------- Points ledger ----------

CREATE TABLE IF NOT EXISTS points_ledger (
    id                      UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id                 UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind                    VARCHAR(16)  NOT NULL CHECK (kind IN ('earn','redeem','adjustment')),
    amount                  BIGINT       NOT NULL CHECK (amount >= 0),
    cents_value             BIGINT       NOT NULL,
    related_transaction_id  UUID REFERENCES card_transactions(id) ON DELETE SET NULL,
    related_video_id        UUID,
    note                    VARCHAR(200),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Points.listFor: WHERE user_id = ? ORDER BY created_at DESC LIMIT n
-- Points.balanceFor: SUM over (kind, amount) WHERE user_id = ?. The INCLUDE columns
-- make the balance an index-only scan. The ledger is append-only, so pages stay
-- all-visible, and PG 19 scans can set the visibility-map bits themselves.
DROP INDEX IF EXISTS points_user_idx;
CREATE INDEX IF NOT EXISTS points_user_created_idx
    ON points_ledger(user_id, created_at DESC) INCLUDE (kind, amount);
-- FK: SET NULL from card_transactions. Most rows have no related transaction.
CREATE INDEX IF NOT EXISTS points_related_tx_idx
    ON points_ledger(related_transaction_id) WHERE related_transaction_id IS NOT NULL;

-- ---------- Education ----------

CREATE TABLE IF NOT EXISTS education_videos (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    youtube_id      CHAR(11)     NOT NULL UNIQUE CHECK (youtube_id ~ '^[A-Za-z0-9_-]{11}$'),
    title           VARCHAR(200) NOT NULL,
    description     TEXT         NOT NULL,
    duration_sec    INT          NOT NULL CHECK (duration_sec > 0),
    points_reward   BIGINT       NOT NULL CHECK (points_reward >= 0),
    published_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Education.listVideos: ORDER BY published_at DESC LIMIT n
CREATE INDEX IF NOT EXISTS videos_published_idx ON education_videos(published_at DESC);

-- Upserted on every progress ping; keep headroom for HOT updates.
CREATE TABLE IF NOT EXISTS watch_progress (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    video_id        UUID         NOT NULL REFERENCES education_videos(id) ON DELETE CASCADE,
    seconds_watched INT          NOT NULL DEFAULT 0 CHECK (seconds_watched >= 0),
    completed       BOOLEAN      NOT NULL DEFAULT false,
    rewarded_at     TIMESTAMPTZ,
    UNIQUE (user_id, video_id)
) WITH (fillfactor = 90);

-- FK: cascade from education_videos. user_id is covered by the UNIQUE index.
CREATE INDEX IF NOT EXISTS watch_progress_video_idx ON watch_progress(video_id);

-- ---------- Tax documents (blob references only — body lives in Azure Blob) ----------

CREATE TABLE IF NOT EXISTS tax_documents (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tax_year        INT          NOT NULL CHECK (tax_year BETWEEN 1900 AND 9999),
    document_type   VARCHAR(32)  NOT NULL,
    blob_ref        VARCHAR(512) NOT NULL,
    issued_on       DATE         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- TaxDocs.listFor: WHERE user_id = ? ORDER BY tax_year DESC, issued_on DESC
DROP INDEX IF EXISTS tax_user_year_idx;
CREATE INDEX IF NOT EXISTS tax_user_year_issued_idx
    ON tax_documents(user_id, tax_year DESC, issued_on DESC);

-- ---------- Notifications ----------

CREATE TABLE IF NOT EXISTS notification_prefs (
    user_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind      VARCHAR(24)  NOT NULL
              CHECK (kind IN ('transaction','marketing','education_new','score_update','support')),
    channel   VARCHAR(8)   NOT NULL CHECK (channel IN ('push','email','sms')),
    enabled   BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (user_id, kind, channel)
);

CREATE TABLE IF NOT EXISTS notification_events (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind            VARCHAR(24)  NOT NULL
                    CHECK (kind IN ('transaction','marketing','education_new','score_update','support')),
    title           VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    channel         VARCHAR(8)   NOT NULL CHECK (channel IN ('push','email','sms')),
    delivered_at    TIMESTAMPTZ,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Notifications.listFor: WHERE user_id = ? ORDER BY created_at DESC LIMIT n
CREATE INDEX IF NOT EXISTS notif_user_idx ON notification_events(user_id, created_at DESC);

-- ---------- Support ----------

CREATE TABLE IF NOT EXISTS support_tickets (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel         VARCHAR(16)  NOT NULL,
    subject         VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    CHECK (closed_at IS NULL OR closed_at >= created_at)
);

-- FK: cascade from users, and per-user ticket history
CREATE INDEX IF NOT EXISTS support_user_created_idx ON support_tickets(user_id, created_at DESC);
