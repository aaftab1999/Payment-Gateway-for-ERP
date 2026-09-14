-- ============================================================================
-- Flyway baseline migration for Payment Gateway & Settlement Core Engine
-- ----------------------------------------------------------------------------
-- Version: 1.0  (2024-09-14)
--
-- Purpose:
--   Establishes the foundational schema version tracking.
--   The complete payment/ledger/idempotency schema is added in later stages
--   (Stage 3+).  This migration creates only the metadata that Flyway itself
--   manages and one application-level metadata table used to record
--   which schema version the application code expects.
--
-- Why Flyway:
--   * Reliable, repeatable database migrations versioned in source control.
--   * Each migration is atomic; a failure rolls back the transaction and
--     marks the migration as FAILED, preventing partial schema application.
--   * Migration IDs follow the V{version}__{description}.sql convention.
--   * Version gaps are detected and cause a clean startup failure.
-- ============================================================================

-- Flyway's own schema history table is created automatically; no action needed.

-- Application metadata: records the schema version the running code targets.
CREATE TABLE IF NOT EXISTS app_metadata (
    key         VARCHAR(100) PRIMARY KEY,
    value       VARCHAR(1000) NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO app_metadata (key, value)
VALUES ('schema_version', '1.0')
ON CONFLICT (key) DO UPDATE
    SET value = EXCLUDED.value,
        updated_at = now();

-- Minimal seed: a single ledger account group table.
-- Full account definitions arrive in Stage 3.
CREATE TABLE IF NOT EXISTS ledger_account_group (
    group_code  VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO ledger_account_group (group_code, name)
VALUES
    ('ASSET',    'Asset accounts'),
    ('LIABILITY','Liability accounts'),
    ('REVENUE',  'Revenue accounts'),
    ('EXPENSE',  'Expense accounts')
ON CONFLICT (group_code) DO NOTHING;
