-- ============================================================================
-- V3__idempotency_provider_retry.sql — Stage 4: Idempotency, concurrency, recovery
-- ----------------------------------------------------------------------------
-- Version: 3.0  (2026-09-14)
--
-- Purpose:
--   Adds the schema required for safe retries, duplicate-request handling,
--   provider idempotency, and bounded recovery of uncertain payments.
--
-- Design notes:
--   * IDEMPOTENCY table is authoritative. PK (merchant_id, idempotency_key)
--     enforces uniqueness at the database level so concurrent requests
--     serialize on the Postgres unique constraint — no distributed lock
--     required. The row stores enough state to replay the original response
--     without re-contacting the provider.
--   * payment.provider_idempotency_key is the stable key sent to the external
--     provider for a given attempt. It is nullable and unique so a provider
--     reference can never be double-charged. It is NOT the client-supplied
--     Idempotency-Key header — it is derived deterministically from the
--     payment attempt and persists across application restarts.
--   * payment.attempt_count / last_attempt_at / next_retry_at / last_failure_reason
--     are recovery metadata used by the PaymentRecoveryService to bound and
--     observe retry behaviour. They are NOT financial fields.
--   * payment.version already exists (V2) for optimistic locking. This
--     migration does NOT alter it.
--   * All new columns are nullable or have safe defaults so the migration
--     is non-destructive to existing rows.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. IDEMPOTENCY table (authoritative idempotency store)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS idempotency (
    idempotency_id    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id       VARCHAR(255) NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL,
    request_hash      VARCHAR(64)  NOT NULL,                 -- SHA-256 of canonical request payload
    payment_id        UUID         NOT NULL REFERENCES payment(payment_id),
    response_status   INTEGER      NOT NULL,                 -- HTTP status cached for replay
    response_body     JSONB        NOT NULL,                 -- reconstructable response payload
    is_terminal       BOOLEAN      NOT NULL,                 -- true when payment reached a terminal state
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ  NOT NULL,                 -- TTL for cache cleanup
    -- Enforces uniqueness at the DB level. Two concurrent inserts for the
    -- same (merchant_id, idempotency_key) serialize on this PK.
    PRIMARY KEY (merchant_id, idempotency_key)
);

-- Fast lookup by payment_id (reverse resolution: payment -> idempotency row)
CREATE INDEX IF NOT EXISTS idx_idempotency_payment ON idempotency(payment_id);
-- Recovery / admin queries: find rows past expiry
CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON idempotency(expires_at);
-- Cover the common "is there a terminal record for this key?" query
CREATE INDEX IF NOT EXISTS idx_idempotency_terminal ON idempotency(merchant_id, idempotency_key, is_terminal);

-- ----------------------------------------------------------------------------
-- 2. Extend payment with provider idempotency + retry metadata
-- ----------------------------------------------------------------------------
ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS provider_idempotency_key  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS attempt_count             INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_attempt_at           TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS next_retry_at             TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_failure_reason       VARCHAR(1024);

-- provider_idempotency_key is unique so the provider never charges the same
-- logical attempt twice. Nullable because not all outcomes produce a provider
-- reference (e.g. declines / timeouts).
ALTER TABLE payment
    ADD CONSTRAINT IF NOT EXISTS uq_payment_provider_idempotency_key
    UNIQUE (provider_idempotency_key)
    WHERE provider_idempotency_key IS NOT NULL;

-- Indexes for recovery queries (find payments stuck in non-terminal states)
CREATE INDEX IF NOT EXISTS idx_payment_recovery_created
    ON payment(status, created_at)
    WHERE status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'REQUIRES_RECONCILIATION');

CREATE INDEX IF NOT EXISTS idx_payment_recovery_next_retry
    ON payment(next_retry_at)
    WHERE next_retry_at IS NOT NULL
      AND status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'REQUIRES_RECONCILIATION');

-- Cover tenant-scoped recovery lookups
CREATE INDEX IF NOT EXISTS idx_payment_recovery_merchant
    ON payment(merchant_id, status, next_retry_at)
    WHERE status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'REQUIRES_RECONCILIATION');

-- Provider idempotency key lookup (fast reverse resolution)
CREATE INDEX IF NOT EXISTS idx_payment_provider_idempotency
    ON payment(provider_idempotency_key)
    WHERE provider_idempotency_key IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 3. Record the schema version targeted by the application code
-- ----------------------------------------------------------------------------
INSERT INTO app_metadata (key, value)
VALUES ('schema_version', '3.0')
ON CONFLICT (key) DO UPDATE
    SET value = EXCLUDED.value,
        updated_at = now();