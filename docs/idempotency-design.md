# Idempotency Design

## Overview

Stage 4 makes payment creation safe under retries, duplicate requests, concurrent requests, provider timeouts, and application restarts.

## Idempotency Key Lifecycle

### 1. Client supplies the key

The `Idempotency-Key` header is mandatory for `POST /api/v1/payments`.

```
POST /api/v1/payments
Idempotency-Key: <client-generated-key>
```

### 2. Server reserves the key

The server computes a SHA-256 fingerprint of the canonical request payload and attempts to insert a reservation row into the `idempotency` table:

```
BEGIN
  INSERT INTO idempotency (merchant_id, idempotency_key, request_hash, payment_id, ...)
  VALUES (?, ?, ?, ?, ...)
COMMIT
```

The unique constraint on `(merchant_id, idempotency_key)` serializes concurrent requests. One insert wins; the other observes a unique-violation error (SQL state 23505).

### 3. Server processes the payment

The payment is created in `CREATED`, submitted to the provider, and the result is applied. The idempotency record is finalized with the response payload.

### 4. Client retries with the same key

The server reads the existing idempotency row and returns the cached response without re-contacting the provider.

### 5. Client retries with a different payload

The server returns `409 CONFLICT` with error code `IDEMPOTENCY_KEY_CONFLICT`.

## Request Fingerprint

The fingerprint is a SHA-256 digest of the canonical request fields, sorted by key for stability:

```
merchantId=m_123|customerRef=c_456|billRef=INV-001|amount=100.00|currency=INR|method=UPI|token=success:*****
```

The token is masked before hashing so the fingerprint never contains sensitive credentials.

## Concurrency Strategy

### Database-level guarantees

- **Unique constraint** on `(merchant_id, idempotency_key)` in the `idempotency` table.
- **Pessimistic locking** (`SELECT FOR UPDATE`) on the payment row during state transitions.
- **Optimistic locking** via the `version` column on the `payment` table.

### Why not a distributed lock

PostgreSQL row locks under `READ_COMMITTED` give true serializability guarantees for money. Redis locks are probabilistic (expiry races, network partition). The unique constraint is the source of truth; the row lock only reduces the window for the read-check-insert pattern.

## Provider Idempotency

The provider idempotency key is derived deterministically from the payment ID (`prov_<payment_id>`). It is stable across retries of the same attempt and persists across application restarts. The simulated provider caches the first result for a given key and replays it for any subsequent call.

## Recovery Behaviour

Payments stuck in `CREATED`, `PROCESSING`, `UNKNOWN`, or `REQUIRES_RECONCILIATION` are eligible for recovery. The recovery service:

1. Re-enters `PROCESSING` for the retry.
2. Re-submits to the provider with the same provider idempotency key.
3. Schedules the next retry with exponential backoff.
4. After `maxRetries` attempts, leaves the payment in a clearly-documented recoverable state.

## State Machine

### Valid transitions

```
CREATED        → PROCESSING
PROCESSING     → SUCCEEDED | FAILED | UNKNOWN | REQUIRES_RECONCILIATION
UNKNOWN        → SUCCEEDED | FAILED | PROCESSING  (retry)
REQUIRES_RECONCILIATION → SUCCEEDED | FAILED | PROCESSING  (retry)
```

### Terminal states

`SUCCEEDED`, `FAILED`, `VOIDED`, `REFUNDED` are terminal and cannot be moved backward.

## API Behaviour

| Scenario | Status | Error Code |
|----------|--------|------------|
| First request | 201 Created | — |
| Repeated identical request | 200 OK | — |
| Same key, different payload | 409 Conflict | `IDEMPOTENCY_KEY_CONFLICT` |
| Invalid state transition | 409 Conflict | `ILLEGAL_STATE_TRANSITION` |
| Optimistic-lock conflict | 409 Conflict | `OPTIMISTIC_LOCK_CONFLICT` |
| Provider timeout/unknown | 202 Accepted | — |
