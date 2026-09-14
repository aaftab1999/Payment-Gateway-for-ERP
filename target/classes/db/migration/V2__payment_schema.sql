-- ============================================================================
-- V2__payment_schema.sql — Payment domain schema
-- ----------------------------------------------------------------------------
-- Version: 2.0  (2026-09-14)
--
-- Purpose:
--   Creates the payment aggregate table with appropriate constraints,
--   indexes, and optimistic-locking support.
--
-- Design notes:
--   * amount_minor stored as DECIMAL(18,2) — supports amounts up to
--     999,999,999,999,999.99 with 2-decimal precision for INR/USD/EUR.
--     JPY (no decimals) is stored as N.00 for uniformity.
--   * provider_reference is UNIQUE but nullable — prevents duplicate
--     provider responses from creating duplicate payment records.
--   * @Version (JPA optimistic lock) maps to the version column.
--   * No foreign keys to ERP tables — the gateway does not own ERP data.
--   * No customer table — customerRef is an opaque string reference.
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment (
    payment_id        UUID         PRIMARY KEY,
    merchant_id       VARCHAR(255) NOT NULL,
    customer_ref      VARCHAR(255),
    bill_ref          VARCHAR(255) NOT NULL,
    amount_minor      DECIMAL(18,2) NOT NULL CHECK (amount_minor > 0),
    currency          VARCHAR(3)   NOT NULL,
    payment_method    VARCHAR(32)  NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'CREATED',
    provider_reference VARCHAR(255) UNIQUE,
    failure_code      VARCHAR(64),
    failure_reason    TEXT,
    correlation_id    UUID,
    journal_entry_id  UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0
);

-- Lookup indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_payment_merchant    ON payment(merchant_id);
CREATE INDEX IF NOT EXISTS idx_payment_bill_ref    ON payment(bill_ref, merchant_id);
CREATE INDEX IF NOT EXISTS idx_payment_status      ON payment(status);
CREATE INDEX IF NOT EXISTS idx_payment_created_at  ON payment(created_at);

-- Foreign-key-like index on provider_reference (already unique-constrained)
-- No FK constraints — provider_reference is external and may be absent.
