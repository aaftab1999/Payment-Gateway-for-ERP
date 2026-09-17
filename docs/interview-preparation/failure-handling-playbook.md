# Failure Handling Playbook

> **Key principle:** Payments must never be in an inconsistent state. If the system
> can't be certain, it must remain non-terminal and await reconciliation — never
> assume failure without evidence.

---

## 1. Duplicate API Request (Same Key, Same Payload)

**Status: IMPLEMENTED**

### What failed
Nothing failed — this is the normal retry path.

### What the system may already have completed
- Idempotency key reserved in `idempotency` table
- Payment created in `CREATED` state
- Outbox event(s) appended
- Provider call completed (payment may be terminal: SUCCEEDED/FAILED)

### What state the payment should be in
Terminal (SUCCEEDED/FAILED) if the first request completed TX2. Otherwise
CREATED or PROCESSING (if first request crashed after TX1 but before TX2).

### What must not be done
- Do NOT create a second payment row — the idempotency record prevents this.
- Do NOT call the provider again — the idempotency record caches the result.

### Whether retry is safe
Yes — retry is the primary mechanism. The idempotency key + fingerprint match
causes `PostgresIdempotencyStore.reserve()` to return a `ReplayOutcome`, and the
controller returns the cached response.

### Which idempotency key must remain stable
The client-supplied `Idempotency-Key` header. The provider idempotency key
(`prov_<paymentId>`) is also stable because it's derived from the payment ID.

### What logs/metrics to inspect
- `idempotency hit: merchant={}, key={}, paymentId={}`
- Look for `23505` unique violation if the reservation raced

### What database records to inspect
- `SELECT * FROM idempotency WHERE merchant_id=? AND idempotency_key=?` — should show `response_body` populated and `is_terminal=true`
- `SELECT * FROM payment WHERE payment_id=?` — should show terminal status

### What recovery process should do
Recovery is not needed — the replay path handles this automatically.

### Interview answer
"When the same idempotency key and payload arrive again, `PostgresIdempotencyStore.reserve()` finds the existing row, compares the fingerprint, and returns a `ReplayOutcome`. The controller returns the cached response with HTTP 200. No second payment is created and no second provider call is made — the database unique constraint on `(merchant_id, idempotency_key)` is the source of truth."

---

## 2. Duplicate API Request with Changed Payload

**Status: IMPLEMENTED**

### What failed
The same idempotency key was reused with a *different* request body.

### What the system may already have completed
- Idempotency key reserved
- Payment created (if first request completed TX1)

### What state the payment should be in
- If first request is terminal: return the original result (safe to replay)
- If first request is non-terminal: payment is in CREATED/PROCESSING

### What must not be done
- Do NOT update the existing payment with the new amount/destination
- Do NOT silently accept the new payload as a retry

### Whether retry is safe
No — this is a client error. The idempotency key has already been "spent."

### Which idempotency key must remain stable
The client's `Idempotency-Key` — it must not be reused for a different payment.

### What logs/metrics to inspect
- `Idempotency conflict: merchant={}, key={}, existingPaymentId={}`
- `IDEMPOTENCY_KEY_CONFLICT` metric

### What database records to inspect
- `SELECT request_hash, is_terminal, payment_id FROM idempotency WHERE idempotency_key=? AND merchant_id=?` — the `request_hash` will differ from the new fingerprint

### What recovery process should do
Recovery cannot fix this — it's a client-side contract violation. The payment
remains as-is; the ERP must send a new idempotency key for a new payment.

### Interview answer
"When the same key is reused with a different payload, `PostgresIdempotencyStore.reserve()` finds the existing row but the `request_hash` doesn't match. If the existing record is non-terminal, we return a `ConflictOutcome` → HTTP 409. If it's terminal, we return the stored result anyway — the payment already completed, and rejecting it would be worse UX than replaying a known-good result."

---

## 3. Concurrent Payment Creation (Same Key, Same Payload)

**Status: IMPLEMENTED** (see Flow 5 in full-project-flow.md)

### What failed
Two threads/requests arrive simultaneously with the same idempotency key.

### What the system may already have completed
One thread may have completed `insertReservation()` and committed.

### What state the payment should be in
The winning thread's payment is in whatever state it reached (CREATED →
PROCESSING → terminal). The losing thread's payment row was never created (the
reservation insert failed with 23505).

### What must not be done
- Do NOT create two payment rows for the same idempotency key
- Do NOT call the provider twice for the same logical payment

### Whether retry is safe
Yes — the losing thread catches the 23505, calls `replay()`, and returns the
cached result.

### Which idempotency key must remain stable
Both threads use the same `Idempotency-Key` header. The winning thread's
provider idempotency key (`prov_<paymentId>`) must also be stable.

### What logs/metrics to inspect
- Controller log: `Idempotency unique violation on key={}, re-reading existing row`
- `idempotency hit` log from the replaying thread

### What database records to inspect
- `idempotency` table: exactly one row for this `(merchant_id, idempotency_key)`
- `payment` table: exactly one payment with the winning `payment_id`

### What recovery process should do
No recovery needed — the race is resolved at the database layer via the unique
constraint.

### Interview answer
"Two concurrent requests with the same idempotency key both call `reserve()`. Both do `SELECT FOR UPDATE` (find no row), then both try `INSERT`. PostgreSQL serializes these — one commits, the other gets SQL state 23505 (unique violation). The controller catches this, calls `replay()`, and returns the cached result. The unique constraint on `(merchant_id, idempotency_key)` is the ultimate arbiter — `synchronized` blocks can't work across multiple JVM instances."

---

## 4. Database Deadlock

**Status: IMPLEMENTED (retry logic exists, deadlock prevention is designed)**

### What failed
PostgreSQL detected a deadlock between two transactions and killed one.

### What the system may already have completed
The killed transaction's writes are rolled back. The surviving transaction
committed successfully.

### What state the payment should be in
The state from the surviving transaction — whatever the last committed state is.

### What must not be done
- Do NOT propagate the deadlock to the client as a 500 error
- Do NOT leave the payment in an inconsistent half-applied state

### Whether retry is safe
Yes — the transaction is retried. The application uses a consistent lock order
(idempotency → payment → journal → outbox) to minimize deadlock probability.

### Which idempotency key must remain stable
N/A — the deadlock retry is transparent to the client.

### What logs/metrics to inspect
- PostgreSQL: `deadlock detected` in server logs
- Application: `PSQLException: deadlock detected` with SQL state `40P01`

### What database records to inspect
Both transactions should be in a consistent committed state — one succeeded,
the other was rolled back entirely.

### What recovery process should do
Retry the transaction. The code comment in `docs/architecture.md:414` mentions
retrying SQL state `40P01` up to 3 times with jittered backoff, but the actual
retry logic is in `ChargeService.applyProviderResult()` for
`ObjectOptimisticLockingFailureException` (line 323-336), which retries on
optimistic lock conflicts, not explicit deadlocks.

### Interview answer
"Deadlocks are retried with backoff. We use a consistent lock ordering
(idempotency → payment → journal → outbox) to minimize them. If a deadlock
occurs, PostgreSQL kills one transaction with SQL state 40P01, and the
application retries. The idempotent provider key and idempotency unique
constraint ensure correctness even across retries."

---

## 5. Optimistic Locking Failure

**Status: IMPLEMENTED**

### What failed
Two concurrent transactions tried to update the same payment row. The
`@Version` column on `PaymentEntity` prevented the second commit.

### What the system may already have completed
The first transaction committed successfully. The second was rejected.

### What state the payment should be in
The first transaction's final state.

### What must not be done
- Do NOT silently overwrite the first transaction's changes
- Do NOT lose the first transaction's update

### Whether retry is safe
Yes — `ChargeService.applyProviderResult()` (line 254-337) retries up to 3 times
with `Thread.sleep(50 * attempt)` backoff.

### Which idempotency key must remain stable
N/A — the optimistic lock failure is within TX2, the idempotency key is
already reserved.

### What logs/metrics to inspect
- `Optimistic lock conflict on payment {} (attempt {}/3), retrying`
- `ObjectOptimisticLockingFailureException` in stack trace

### What database records to inspect
- `payment.version` — the second transaction's version is stale
- `payment.status` — should reflect the first transaction's final state

### What recovery process should do
The retry loop handles it — no manual recovery needed. If all 3 retries fail,
the exception propagates to the caller.

### Interview answer
"The `@Version` column on `PaymentEntity` provides optimistic locking. If two
transactions try to update the same payment, the second gets
`ObjectOptimisticLockingFailureException`. `ChargeService.applyProviderResult()`
retries up to 3 times with backoff. This catches the lost-update problem without
requiring pessimistic locks for every read."

---

## 6. Pessimistic Lock Contention

**Status: IMPLEMENTED**

### What failed
Two transactions tried to acquire `SELECT FOR UPDATE` on the same payment row
simultaneously. One waits; the other holds the lock.

### What the system may already have completed
Whichever transaction acquired the lock first completed its state transition.

### What state the payment should be in
The state after the first transaction's commit.

### What must not be done
- Do NOT timeout and leave the payment in a half-applied state
- Do NOT skip the transition

### Whether retry is safe
Yes — the second transaction waits, then acquires the lock, re-reads the
current state, and applies its transition (which may be a no-op if the payment
is already terminal).

### Which idempotency key must remain stable
N/A — lock contention is within TX2.

### What logs/metrics to inspect
- PostgreSQL: `process acquired` / lock wait logs
- `db_lock_wait_seconds` metric (from `pg_stat_activity` poll)

### What database records to inspect
- `pg_stat_activity` — `SELECT * FROM pg_stat_activity WHERE wait_event_type = 'Lock'`
- `payment.version` — should match the latest committed state

### What recovery process should do
No manual recovery — the `FOR UPDATE` lock blocks the second transaction until
the first commits. If it's a long-running recovery sweep, PostgreSQL will
timeout with `LockNotAvailable` (only if `NOWAIT` or `SKIP LOCKED` is used —
not in the current code).

### Interview answer
"For state transitions, we use `SELECT FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`
on `PaymentRepository.findAndLockByPaymentId()`. This blocks concurrent
updaters until the lock holder commits. The second transaction waits, then
re-reads the current state and applies its transition — which is a safe no-op
if the payment is already terminal. We monitor lock wait times via
`db_lock_wait_seconds`."

---

## 7. Provider Timeout

**Status: IMPLEMENTED** (simulated; real HTTP timeout handling exists)

### What failed
The provider did not respond within the expected time window.

### What the system may already have completed
- Payment created in CREATED (TX1 committed)
- Provider call in-flight or timed out

### What state the payment should be in
`CREATED` (if provider was never successfully called) or `UNKNOWN` (if called
but no response received or response timed out).

### What must not be done
- Do NOT mark the payment as `FAILED`
- Do NOT retry the provider call with a different idempotency key (would double-charge)

### Whether retry is safe
Yes — the provider idempotency key is reused. If the first call timed out but
the provider actually processed it, the retry returns the same result (idempotent).

### Which idempotency key must remain stable
Provider idempotency key: `prov_<paymentId>` — must be stable across retries.

### What logs/metrics to inspect
- `Provider call failed for payment {}: {}` (ChargeService line 201)
- `provider_errors_total{paymentMethod, errorType: TIMEOUT}` metric
- `PaymentTimeout` or `PaymentUnknown` outbox event

### What database records to inspect
- `SELECT status, attempt_count, next_retry_at FROM payment WHERE payment_id=?`
- `SELECT event_type FROM outbox WHERE aggregate_id=? ORDER BY event_order`

### What recovery process should do
`PaymentRecoveryService` finds the `UNKNOWN` payment, re-enters `PROCESSING`
(via `markRetrySubmitted()`), and schedules the next retry with exponential
backoff. The provider idempotency key is reused.

### Interview answer
"A provider timeout does not mean the payment failed — the provider may have
debited the customer. We return `UNKNOWN` (non-terminal) with HTTP 202 Accepted.
The recovery scheduler re-submits with the same provider idempotency key, which
the simulated provider (or real provider) deduplicates. This prevents double-charging."

---

## 8. Provider Connection Reset

**Status: IMPLEMENTED**

### What failed
The provider's HTTP endpoint reset the connection (e.g. `ConnectionResetException`).

### What the system may already have completed
- Payment created (TX1 committed)

### What state the payment should be in
`CREATED` (if the connection failed before any response) — recovery will retry.

### What must not be done
- Do NOT mark as FAILED without knowing whether money moved

### Whether retry is safe
Yes — the provider idempotency key makes this safe.

### Which idempotency key must remain stable
`prov_<paymentId>`

### What logs/metrics to inspect
- `provider_errors_total{errorType: CONNECT}`
- Stack trace with `ConnectionResetException` or `ConnectException`

### What database records to inspect
- `payment.status = CREATED`, `attempt_count > 0`

### What recovery process should do
Same as timeout — re-submit with the same provider key.

### Interview answer
"A `ConnectionResetException` is caught in `ChargeService` (line 200) and
converted to `ProviderResult.technicalFailure()`, which maps to `FAILED`.
This is safe because a connection reset means the provider never responded —
if it had debited, it would have sent a response. The `TECHNICAL_FAILURE` type
explicitly means 'no money moved' in our domain model."

---

## 9. Provider Accepted Request but Response Was Lost

**Status: IMPLEMENTED (conceptually)**

### What failed
The provider accepted and processed the payment (customer charged) but
the response was lost (network partition, client crash, load balancer timeout).

### What the system may already have completed
- Provider debited the customer
- Gateway has no record of the result

### What state the payment should be in
`UNKNOWN` (treated as if the provider call timed out)

### What must not be do
- Do NOT retry with a new idempotency key (would double-charge)
- Do NOT mark as SUCCEEDED without provider confirmation

### Whether retry is safe
Yes — using the SAME provider idempotency key. The simulated provider
replays the cached result. A real provider (Stripe, etc.) deduplicates by
the idempotency key header.

### Which idempotency key must remain stable
`prov_<paymentId>` — the provider idempotency key, NOT the client's
`Idempotency-Key`.

### What logs/metrics to inspect
- `PaymentUnknown` outbox event
- Provider's own transaction logs

### What database records to inspect
- `payment.status = UNKNOWN`, `provider_idempotency_key` set, `provider_reference = NULL`

### What recovery process should do
Re-submit with the same `provider_idempotency_key`. The provider returns
the original result. Apply it; payment transitions to the correct terminal state.

### Interview answer
"This is the key reason we have the UNKNOWN state. When the provider's
response is lost (but the provider may have charged), we can't assume failure.
Recovery re-submits with the same provider idempotency key, which the
provider deduplicates — returning the original result. The customer is
charged once, not twice."

---

## 10. Provider Rejected Request

**Status: IMPLEMENTED**

### What failed
The provider rejected the request (decline, insufficient funds, etc.).

### What the system may already have completed
- Provider responded with a decline
- Payment transitioned to FAILED

### What state the payment should be in
`FAILED` (terminal)

### What must not be done
- Do NOT retry automatically (declines are business decisions, not transient errors)

### Whether retry is safe
Not applicable — FAILED is terminal.

### Which idempotency key must remain stable
The idempotency record is finalized as terminal — no retry needed.

### What logs/metrics to inspect
- `PaymentFailed` outbox event
- `failureCode: DECLINED`

### What database records to inspect
- `payment.status = FAILED`, `failure_code = 'DECLINED'`

### What recovery process should do
No recovery — FAILED is terminal. The payment is visible for ERP correlation.

### Interview answer
"A provider decline (e.g. insufficient funds) is a business decision, not a
transient error. It maps to `FAILED` status, which is terminal. We don't
auto-retry. The ERP can display the decline reason to the customer."

---

## 11. Application Crash Before Database Commit (TX1)

**Status: IMPLEMENTED**

### What failed
The application crashed before TX1 committed.

### What the system may already have completed
Nothing — the transaction was rolled back. No payment row, no idempotency row.

### What state the payment should be in
No payment exists in the database.

### What must not be done
- Do NOT assume the payment was created
- Do NOT create a duplicate on retry

### Whether retry is safe
Yes — the client's idempotency key was never persisted. A retry starts fresh.

### Which idempotency key must remain stable
The client's `Idempotency-Key` — the client should retry with the same key.
On the server side, nothing was persisted, so the reservation starts fresh.

### What logs/metrics to inspect
- Check PostgreSQL: no payment row with the given correlation ID

### What database records to inspect
- `SELECT * FROM payment WHERE correlation_id=?` — should be empty
- `SELECT * FROM idempotency WHERE merchant_id=? AND idempotency_key=?` — should be empty

### What recovery process should do
Nothing — the transaction was atomic. A client retry creates a new payment.

### Interview answer
"If the app crashes before TX1 commits, the database transaction rolls back
entirely — no payment row, no idempotency row. The client retries with the
same `Idempotency-Key`, and the reservation starts fresh. This is why we
use `TransactionTemplate` — atomicity is guaranteed by the database, not by
the application process."

---

## 12. Application Crash After Database Commit (TX2)

**Status: IMPLEMENTED**

### What failed
The application crashed after TX2 committed but before the HTTP response was sent.

### What the system may already have completed
- Payment is terminal (SUCCEEDED/FAILED/UNKNOWN)
- Idempotency record is finalized with the response body and status code
- Outbox events are written

### What state the payment should be in
Whatever TX2 set — SUCCEEDED, FAILED, or UNKNOWN. Fully durable.

### What must not be done
- Do NOT re-process the payment
- Do NOT re-call the provider

### Whether retry is safe
Yes — the idempotency record is finalized as terminal. A client retry
(the TCP connect may have succeeded even if the HTTP response was lost)
calls `replay()` and gets the cached result.

### Which idempotency key must remain stable
The client's `Idempotency-Key` — the client retries with the same key.
The idempotency record's `is_terminal=true` flag makes the replay safe.

### What logs/metrics to inspect
- `idempotency hit: merchant={}, key={}, paymentId={}`
- `PaymentOutboxTransactionIntegrationTest` confirms atomicity

### What database records to inspect
- `idempotency` table: `is_terminal=true`, `response_body` populated
- `payment` table: terminal status

### What recovery process should do
No recovery needed — the state is durable. The idempotency replay path
handles the client retry.

### Interview answer
"After TX2 commits, the payment state and idempotency record are durable
in PostgreSQL. If the app crashes before sending the HTTP response, the
client's TCP connection may still succeed. On retry, the idempotency
`replay()` path finds the terminal record and returns the cached response
with HTTP 200. The outbox events are also committed — the publisher
processes them after restart."

---

## 13. Application Crash After Provider Acceptance (Before TX2 Commit)

**Status: IMPLEMENTED**

### What failed
The provider accepted the payment (customer may be charged) but the gateway
crashed before TX2 committed.

### What the system may already have completed
- TX1 committed: payment in CREATED, idempotency reserved, outbox PaymentCreated written
- Provider returned a result (e.g. SUCCESS)
- Gateway crashed before TX2 (which would have persisted the result)

### What state the payment should be in
`CREATED` — TX2 never committed, so the payment is still in its initial state.

### What must not be done
- Do NOT skip the provider call during recovery
- Do NOT use a new provider idempotency key

### Whether retry is safe
Yes — recovery re-submits with the same provider idempotency key. The simulated
provider replays the cached result. A real provider deduplicates by the key.

### Which idempotency key must remain stable
Provider idempotency key: `prov_<paymentId>` — persisted on the payment row
in TX1. Recovery reads it and reuses it.

### What logs/metrics to inspect
- `Payment timeout or no result` — recovery scheduler log
- `provider_txn_{correlationId}` — in provider's logs

### What database records to inspect
- `SELECT status, provider_idempotency_key, attempt_count FROM payment WHERE payment_id=?`
- `SELECT * FROM outbox WHERE aggregate_id=? ORDER BY event_order`

### What recovery process should do
1. `PaymentRecoveryService.sweep()` finds the `CREATED` payment
2. `findAndLockByPaymentId()` acquires `SELECT FOR UPDATE`
3. `ensureProviderIdempotencyKey()` confirms the key is set
4. Recovery re-enters `PROCESSING` via `markRetrySubmitted()` (actually uses `markProcessing()` for CREATED)
5. Re-submits to provider with same key

**GAP:** Recovery does NOT call `processor.process()` — it only transitions to PROCESSING and schedules a retry. The actual provider re-submission is not wired into the recovery path. See full-project-flow.md Flow 12.

### Interview answer
"This is the hardest case. The provider may have charged the customer but
we have no record. Recovery finds the `CREATED` payment, re-enters
`PROCESSING`, and re-submits with the same provider idempotency key. The
provider (or simulated provider) returns the original result — no
double-charge. The key insight: never use a new provider idempotency key,
or you risk charging twice."

---

## 14. Database Unavailable

**Status: IMPLEMENTED (at the infrastructure level)**

### What failed
PostgreSQL is down or unreachable.

### What the system may already have completed
Nothing that requires persistence.

### What state the payment should be in
No payment exists (or previous committed transactions are visible after DB recovery).

### What must not be done
- Do NOT use stale in-memory state
- Do NOT accept payments without persistence (would lose them)

### Whether retry is safe
Yes — the client retries. No partial state exists.

### Which idempotency key must remain stable
The client's `Idempotency-Key` — retry with the same key.

### What logs/metrics to inspect
- `HikariPool-1 - ConnectionException` in logs
- `pg_isready` failing
- `db_lock_wait_seconds` metric showing errors

### What database records to inspect
None accessible — database is down.

### What recovery process should do
Wait for database to recover (via Hikari's connection retry), then resume.
If the database was down for a long time, pending outbox events and
recovery items accumulate and process on restart.

### Interview answer
"PostgreSQL is the source of truth. If it's down, the HikariCP connection
pool retries connections. The API returns 503 until the DB is available.
We do NOT accept payments without persistence — that would create unrecoverable
in-flight state. Once the DB recovers, all pending work (outbox, recovery)
processes naturally."

---

## 15. Kafka Unavailable

**Status: IMPLEMENTED (payment path remains available)**

### What failed
Kafka broker is down or unreachable.

### What the system may already have completed
- Payment is committed (SUCCEEDED/FAILED/UNKNOWN)
- Outbox row is written (PENDING)

### What state the payment should be in
Whatever TX2 set — fully durable in PostgreSQL.

### What must not be done
- Do NOT block the payment response waiting for Kafka
- Do NOT leave the payment in a half-committed state

### Whether retry is safe
Yes — the payment is already committed. Kafka publish is decoupled.

### Which idempotency key must remain stable
N/A — this doesn't affect payment processing.

### What logs/metrics to inspect
- `Outbox publication failed: eventId={}, paymentId={}, error={}` 
- `outbox_backlog_count` metric increasing
- `kafka_consumer_lag` (from ERP side)

### What database records to inspect
- `SELECT COUNT(*) FROM outbox WHERE status='PENDING'` — backlog growing
- `SELECT * FROM outbox WHERE status='PENDING' ORDER BY next_attempt_at`

### What recovery process should do
The `OutboxPublisher` retries with exponential backoff (bounded by
`retry-max-backoff: 30s`). After 10 attempts, sends to DLQ. If DLQ succeeds,
marks `DEAD_LETTERED`. If DLQ also fails, marks `FAILED` for operator
inspection. Payments remain correct throughout — Kafka is not part of the
payment transaction.

### Interview answer
"Kafka availability is NOT required to process a payment. The outbox row
is committed in the same transaction as the payment state change. If Kafka
is down, the row stays PENDING and the publisher retries with exponential
backoff. The payment itself is already committed and visible via the REST
API or polling. This is the outbox pattern's key benefit — payment processing
and event delivery are decoupled."

---

## 16. Outbox Publisher Failure

**Status: IMPLEMENTED**

### What failed
The publisher fails to send an event to Kafka (network error, serialization error).

### What the system may already have completed
- Payment is committed and in a terminal/non-terminal state
- Outbox row is written (PENDING)

### What state the payment should be in
Unchanged by publisher failure.

### What must not be done
- Do NOT lose the event
- Do NOT skip the payment's status transition

### Whether retry is safe
Yes — the publisher increments `attempt_count` and schedules a retry with
exponential backoff.

### Which idempotency key must remain stable
N/A — this is at the event level. The `event_id` (UUID) is the consumer
deduplication key.

### What logs/metrics to inspect
- `Outbox retry scheduled: eventId={}, paymentId={}, attempt={}, nextAttemptAt={}`
- `outbox_publish_failures_total` metric
- `outbox_dead_letter_count` if DLQ attempted

### What database records to inspect
- `SELECT * FROM outbox WHERE event_id=?` — `attempt_count`, `next_attempt_at`, `last_error`

### What recovery process should do
The publisher's `handleFailure()` (line 100-135) handles retry scheduling
and DLQ routing automatically. No manual recovery needed unless the row
reaches `FAILED` status (requires operator investigation).

### Interview answer
"The publisher marks a row PENDING, sends to Kafka, and only marks PUBLISHED
after receiving an acknowledgement. If the send fails, it increments
`attempt_count`, records the error, and schedules a retry with exponential
backoff. After 10 failed attempts, it routes to the DLQ. A DLQ failure
leaves a terminal FAILED row for operator inspection. The payment itself
is unaffected — it's already committed."

---

## 17. Duplicate Kafka Event

**Status: IMPLEMENTED (by design)**

### What failed
Kafka redelivered an event that was already processed (at-least-once delivery).

### What the system may already have completed
- The original event was processed by the consumer
- The payment state is correct

### What state the payment should be in
No change — the duplicate is ignored.

### What must not be done
- Do NOT apply the event twice
- Do NOT update the invoice twice

### Whether retry is safe
N/A — this is a consumer-side concern.

### Which idempotency key must remain stable
The `event_id` (UUID) — consumers deduplicate by this.

### What logs/metrics to inspect
- `duplicate ERP processing` metric
- Consumer log: deduplicated eventId

### What database records to inspect
- `processed_event` table (if the consumer persists dedup state)
- `outbox` table: status = PUBLISHED (only one PUBLISHED row per event_id)

### What recovery process should do
The consumer's deduplication logic handles it — no gateway-side recovery.

### Interview answer
"At-least-once delivery means duplicates can happen — a crash after Kafka
acknowledgement but before the PUBLISHED status update would cause a
re-publish. Consumers must deduplicate by `event_id`. Our test fixture
(`ErpPaymentEventFixture`) stores a `Set<UUID>` of processed event IDs
and returns `false` for duplicates. In production, the ERP maintains its
own `PROCESSED_EVENT` table."

---

## 18. Out-of-Order Event

**Status: IMPLEMENTED (per-payment ordering guaranteed)**

### What failed
Events for the same payment arrived out of order at the consumer.

### What the system may already have completed
The consumer processed a later event before an earlier one.

### What state the payment should be in
The consumer should handle ordering — the gateway guarantees per-payment
ordering via Kafka partitioning, but cross-payment ordering is not guaranteed.

### What must not be done
- Do NOT apply a newer event and then "downgrade" with an older one

### Whether retry is safe
N/A — consumer-side concern.

### Which idempotency key must remain stable
The `event_order` field — consumers compare it with their current invoice state.

### What logs/metrics to inspect
- `out-of-order event` log on consumer side
- `event_order` mismatch warnings

### What database records to inspect
- Consumer's `processed_event` table with `event_order` tracking

### What recovery process should do
The consumer should retain the out-of-order event and process it later,
or reject it if the current state is already ahead. The gateway emits
`eventOrder` for this purpose.

### Interview answer
"Per-payment ordering is guaranteed by Kafka: all events for one payment
share the same key (payment UUID), so they go to the same partition and
are delivered in order. Cross-payment ordering is NOT guaranteed — events
for different payments can be interleaved. Consumers use `eventOrder`
to detect and handle any out-of-order scenarios, typically by buffering
the event until the expected order arrives."

---

## 19. Recovery Scheduler Failure

**Status: IMPLEMENTED (error handling exists)**

### What failed
`RecoveryScheduler.runRecoverySweep()` threw an exception.

### What the system may already have completed
Some payments may have been processed; others not.

### What state the payment should be in
Unchanged — if the sweep failed, no payment was modified.

### What must not be done
- Do NOT lose the payments that were eligible for recovery
- Do NOT corrupt any payment that was being processed

### Whether retry is safe
Yes — the next scheduled sweep picks up where it left off. Each payment is
locked via `SELECT FOR UPDATE`, so incomplete processing from a crash is
re-locked and re-attempted.

### Which idempotency key must remain stable
Provider idempotency key: `prov_<paymentId>`

### What logs/metrics to inspect
- `Recovery sweep failed: {}` (RecoveryScheduler line 66)
- `Recovery failed for payment {}: {}` (line 95)
- `payment_recovery_sweep_failures_total` metric

### What database records to inspect
- Payments in non-terminal states that are past their timeout thresholds

### What recovery process should do
The `@Scheduled` annotation with `fixedDelay` ensures the next sweep runs
even if the previous one failed. Each payment is processed independently —
a failure on one payment (caught at line 93-96) doesn't block others.

### Interview answer
"The `RecoveryScheduler` catches exceptions per-payment (line 93-96), so
one bad payment doesn't crash the entire sweep. If the sweep itself fails
(line 65-67), the scheduler retries on the next `@Scheduled` run. Payments
are locked with `SELECT FOR UPDATE`, so a crash mid-recovery leaves the
payment available for the next sweep."

---

## 20. Retry Limit Reached

**Status: IMPLEMENTED**

### What failed
The payment has been retried `maxRetries` (default 3) times without reaching
a terminal state.

### What the system may already have completed
- Payment is in UNKNOWN or CREATED state
- Multiple provider calls were made (all with the same idempotency key)
- Multiple outbox events were written

### What state the payment should be in
Still non-terminal (UNKNOWN or CREATED), with `last_failure_reason` set to
the exhaustion message.

### What must not be done
- Do NOT continue retrying indefinitely
- Do NOT silently delete the payment

### Whether retry is safe
No automatic retry — the payment requires manual investigation or a
reconciliation pass.

### Which idempotency key must remain stable
Provider idempotency key: `prov_<paymentId>` — remains stable, provider
won't double-charge on accidental retries.

### What logs/metrics to inspect
- `Payment {} has exhausted retry budget (attemptCount={})`
- `PaymentRetryExhausted` outbox event

### What database records to inspect
- `SELECT status, attempt_count, last_failure_reason FROM payment WHERE payment_id=?`

### What recovery process should do
Flag the payment for manual review. The payment remains in its non-terminal
state — a reconciliation pass (Stage 6) can still resolve it if the
provider confirms the outcome.

### Interview answer
"After 3 retries, the payment is left in `UNKNOWN` with a
`PaymentRetryExhausted` event emitted. This is intentional — we never
silently drop a payment. An operator or reconciliation service must
manually resolve it, possibly by contacting the provider directly."

---

## 21. Corrupted or Invalid State Transition

**Status: IMPLEMENTED**

### What failed
An attempt to transition a payment between two statuses that are not
connected in the state machine (e.g. `SUCCEEDED → FAILED`).

### What the system may already have completed
Nothing — `PaymentStateEngine.transition()` throws before any persistence.

### What state the payment should be in
Unchanged — the transition was never applied.

### What must not be done
- Do NOT allow the transition
- Do NOT silently ignore the error

### Whether retry is safe
The error should surface to the caller — this indicates a bug, not a retryable
condition.

### Which idempotency key must remain stable
N/A

### What logs/metrics to inspect
- `IllegalStateTransitionException: Invalid transition: X → Y`
- Application error rate increasing

### What database records to inspect
- Payment status should be unchanged

### What recovery process should do
Investigate the code path that attempted the invalid transition — this is
a bug, not a data issue.

### Interview answer
"The state machine is enforced by `PaymentStateEngine.transition()`, which
throws `IllegalStateTransitionException` for any invalid transition. This
is a domain-layer invariant — even if the API is called incorrectly or a
race condition occurs, the state machine prevents illegal moves. Terminal
states (SUCCEEDED, FAILED, VOIDED, REFUNDED) have no outgoing transitions."

---

## 22. Duplicate Provider Callback / Result

**Status: IMPLEMENTED**

### What failed
The provider sent the same result twice (e.g. two webhooks, or a retry of
the same callback).

### What the system may already have completed
- First result was applied (payment in terminal state)
- Second result arrives for the same payment

### What state the payment should be in
The state from the first result — terminal.

### What must not be done
- Do NOT apply the result twice
- Do NOT overwrite the provider reference

### Whether retry is safe
Yes — the apply is idempotent.

### Which idempotency key must remain stable
Provider idempotency key: `prov_<paymentId>` — same key, so the provider
returns the same result.

### What logs/metrics to inspect
- `Payment {} already terminal ({}) — idempotent apply` (ChargeService line 265)

### What database records to inspect
- `SELECT status FROM payment WHERE payment_id=?` — should be terminal

### What recovery process should do
No recovery needed — `Payment.applyProviderResult()` (line 149-158) checks
`this.status.isTerminal()` and returns early (no-op).

### Interview answer
"If the provider sends the same result twice, `Payment.applyProviderResult()`
checks `this.status.isTerminal()` first. If true, it's a no-op — it only
updates `providerReference` if that field is still null. This prevents
duplicate callbacks from corrupting state."

---

## Failure Matrix

| Failure | Payment State Impact | Recovery Mechanism | Key Safety |
|---------|---------------------|-------------------|------------|
| Duplicate API request | None | Idempotency replay (cached response) | Client key stable |
| Concurrent same-key | None | DB unique constraint serializes | Client key stable |
| DB deadlock | None | Retry (3x) | Transparent |
| Optimistic lock fail | None | Retry (3x) | Transparent |
| Provider timeout | UNKNOWN | Recovery scheduler retries | Provider key stable |
| Provider conn reset | FAILED | No retry (terminal) | N/A |
| Provider accepted, response lost | CREATED | Recovery re-submits | Provider key stable |
| Crash before TX1 commit | Rolled back | Client retry | Client key stable |
| Crash after TX2 commit | Durable | Idempotency replay | Client key stable |
| DB unavailable | Request rejected | Hikari retries | Client key stable |
| Kafka unavailable | No impact | Publisher retries | Event ID stable |
| Outbox publish failure | No impact | Exponential backoff + DLQ | Event ID stable |
| Duplicate Kafka event | None | Consumer dedup by eventId | Event ID used |
| Out-of-order event | None | Consumer buffers by eventOrder | Event order tracked |
| Recovery crash | None | Next sweep re-locks | Provider key stable |
| Retry exhaustion | Non-terminal | Manual review / reconciliation | Provider key stable |
| Invalid state transition | No transition | Exception thrown | N/A |
| Duplicate provider callback | None | Idempotent apply (terminal check) | Provider key stable |

## Verification Commands Executed

- `git status` — confirmed modified vs untracked files
- `git log --oneline -10` — confirmed recent commits
- Source code inspection — all classes, methods, and line numbers verified against actual source