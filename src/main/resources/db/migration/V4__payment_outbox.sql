-- Stage 5: transactional outbox for payment lifecycle events.
CREATE SEQUENCE IF NOT EXISTS outbox_event_order_seq AS BIGINT;

CREATE TABLE IF NOT EXISTS outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL REFERENCES payment(payment_id),
    event_type VARCHAR(64) NOT NULL,
    event_version INTEGER NOT NULL CHECK (event_version > 0),
    event_payload JSONB NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    event_order BIGINT NOT NULL DEFAULT nextval('outbox_event_order_seq'),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD_LETTERED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    last_error VARCHAR(2048),
    lock_owner VARCHAR(64),
    locked_until TIMESTAMPTZ,
    CONSTRAINT uq_outbox_payment_order UNIQUE (aggregate_id, event_order)
);

CREATE INDEX IF NOT EXISTS idx_outbox_due
    ON outbox(status, next_attempt_at, event_order, created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_order
    ON outbox(aggregate_id, event_order);

CREATE INDEX IF NOT EXISTS idx_outbox_event_id
    ON outbox(event_id);

INSERT INTO app_metadata (key, value)
VALUES ('schema_version', '4.0')
ON CONFLICT (key) DO UPDATE
    SET value = EXCLUDED.value,
        updated_at = now();
