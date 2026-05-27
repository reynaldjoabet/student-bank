-- Student-bank schema (PostgreSQL). Idempotent.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------- Users (no plaintext SSN ever) ----------

CREATE TABLE IF NOT EXISTS users (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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

CREATE TABLE IF NOT EXISTS cards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
);

CREATE INDEX IF NOT EXISTS cards_user_idx ON cards(user_id);

-- ---------- Transactions ----------

CREATE TABLE IF NOT EXISTS card_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id         UUID         NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    kind            VARCHAR(20)  NOT NULL,
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    merchant        VARCHAR(200),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('pending','posted','disputed','reversed','declined')),
    galileo_ref     VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS tx_card_time_idx ON card_transactions(card_id, occurred_at DESC);

-- ---------- Disputes ----------

CREATE TABLE IF NOT EXISTS disputes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID         NOT NULL REFERENCES card_transactions(id) ON DELETE CASCADE,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason          TEXT         NOT NULL,
    status          VARCHAR(24)  NOT NULL CHECK (status IN ('open','resolved_customer','resolved_merchant','withdrawn')),
    opened_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ
);

-- ---------- Loans ----------

CREATE TABLE IF NOT EXISTS loans (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind              VARCHAR(16)  NOT NULL CHECK (kind IN ('student','personal')),
    principal_minor   BIGINT       NOT NULL CHECK (principal_minor > 0),
    apr_bps           INT          NOT NULL CHECK (apr_bps >= 0 AND apr_bps <= 100000),
    term_months       INT          NOT NULL CHECK (term_months > 0),
    status            VARCHAR(16)  NOT NULL,
    remaining_minor   BIGINT       NOT NULL DEFAULT 0 CHECK (remaining_minor >= 0),
    applied_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    disbursed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS loans_user_idx ON loans(user_id, status);

-- ---------- Linked external bank accounts ----------

CREATE TABLE IF NOT EXISTS linked_bank_accounts (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    routing_number           CHAR(9)      NOT NULL,
    account_number_last4     CHAR(4)      NOT NULL,
    holder_name              VARCHAR(200) NOT NULL,
    plaid_item_id            VARCHAR(128),
    added_at                 TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS auto_pays (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id             UUID         NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    source_account_id   UUID         NOT NULL REFERENCES linked_bank_accounts(id) ON DELETE RESTRICT,
    cadence             VARCHAR(16)  NOT NULL CHECK (cadence IN ('weekly','bi_weekly','monthly')),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    next_run_on         DATE         NOT NULL,
    active              BOOLEAN      NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS auto_pays_next_run_idx ON auto_pays(next_run_on) WHERE active;

-- ---------- Points ledger ----------

CREATE TABLE IF NOT EXISTS points_ledger (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind                    VARCHAR(16)  NOT NULL CHECK (kind IN ('earn','redeem','adjustment')),
    amount                  BIGINT       NOT NULL CHECK (amount >= 0),
    cents_value             BIGINT       NOT NULL,
    related_transaction_id  UUID REFERENCES card_transactions(id) ON DELETE SET NULL,
    related_video_id        UUID,
    note                    VARCHAR(200),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS points_user_idx ON points_ledger(user_id, created_at DESC);

-- ---------- Education ----------

CREATE TABLE IF NOT EXISTS education_videos (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    youtube_id      CHAR(11)     NOT NULL UNIQUE,
    title           VARCHAR(200) NOT NULL,
    description     TEXT         NOT NULL,
    duration_sec    INT          NOT NULL CHECK (duration_sec > 0),
    points_reward   BIGINT       NOT NULL CHECK (points_reward >= 0),
    published_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS watch_progress (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    video_id        UUID         NOT NULL REFERENCES education_videos(id) ON DELETE CASCADE,
    seconds_watched INT          NOT NULL DEFAULT 0,
    completed       BOOLEAN      NOT NULL DEFAULT false,
    rewarded_at     TIMESTAMPTZ,
    UNIQUE (user_id, video_id)
);

-- ---------- Tax documents (blob references only — body lives in Azure Blob) ----------

CREATE TABLE IF NOT EXISTS tax_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tax_year        INT          NOT NULL,
    document_type   VARCHAR(32)  NOT NULL,
    blob_ref        VARCHAR(512) NOT NULL,
    issued_on       DATE         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS tax_user_year_idx ON tax_documents(user_id, tax_year);

-- ---------- Notifications ----------

CREATE TABLE IF NOT EXISTS notification_prefs (
    user_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind      VARCHAR(24)  NOT NULL,
    channel   VARCHAR(8)   NOT NULL CHECK (channel IN ('push','email','sms')),
    enabled   BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (user_id, kind, channel)
);

CREATE TABLE IF NOT EXISTS notification_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind            VARCHAR(24)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    channel         VARCHAR(8)   NOT NULL,
    delivered_at    TIMESTAMPTZ,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS notif_user_idx ON notification_events(user_id, created_at DESC);

-- ---------- Support ----------

CREATE TABLE IF NOT EXISTS support_tickets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel         VARCHAR(16)  NOT NULL,
    subject         VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ
);
