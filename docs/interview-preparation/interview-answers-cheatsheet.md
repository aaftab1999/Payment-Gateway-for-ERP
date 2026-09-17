# Interview Answers — Quick Revision Cheatsheet

> **Key reminder:** At-least-once delivery, NOT exactly-once. Kafka availability
> is NOT required for payment processing. Production-readiness gaps exist.

## 1. 60-Second Project Explanation

"We built a Spring Boot 3.3.3 / Java 21 payment gateway that processes payments
from an external ERP system. The ERP sends `POST /api/v1/payments` with an
`Idempotency-Key`, bill reference, amount, and payment token. The gateway creates
a `Payment` aggregate, submits it to a simulated provider, and applies the result.

Key safety guarantees:
- **Idempotency** via PostgreSQL unique constraint on `(merchant_id, idempotency_key)`
- **UNKNOWN state** for provider timeouts (never assume failure — may have debited)
- **Two-phase transactions** — TX1 creates+commits, provider call happens outside,
  TX2 applies result atomically
- **Transactional outbox** — events committed with payment, published to Kafka async
- **Recovery scheduler** — retries stuck payments with exponential backoff

Architecture: hexagonal — `domain/` (zero framework deps), `application/`
(`@Transactional` orchestration), `infrastructure/` (JPA, Kafka, simulated provider)."

## 2. 2-Minute Project Explanation

"The system has three layers: domain (pure Java), application (orchestration),
infrastructure (adapters). The flow is:

1. **Request:** ERP calls `POST /api/v1/payments` with `Idempotency-Key: ik-abc`.
   `PaymentController` (line 76) validates the `@Valid CreatePaymentRequest`,
   computes a SHA-256 fingerprint (token masked), and calls
   `idempotencyService.reserve()` (`PostgresIdempotencyStore.java:44`).

2. **TX1 (create):** `ChargeService.chargeWithOutcome()` (line 147) uses
   `TransactionTemplate` to: insert `PaymentEntity` (CREATED), append
   `PaymentCreated` outbox event (`OutboxEventService.append`, line 38), and
   reserve the idempotency key — all atomically. If the idempotency insert fails
   with 23505, the controller catches it and replays.

3. **Provider call:** `processor.process(token, amountMinor, currency, corrId,
   providerIdempotencyKey)` — OUTSIDE any transaction. The
   `SimulatedPaymentProcessor` (line 58) determines outcome by token prefix.

4. **TX2 (apply):** Locks the payment (`SELECT FOR UPDATE`), transitions
   CREATED→PROCESSING→terminal, appends outbox events, finalizes idempotency.

5. **Event publishing:** `OutboxPublisher.publishDueEvents()` (line 55, `@Scheduled`)
   polls PENDING rows with `FOR UPDATE SKIP LOCKED`, sends to Kafka `payment.events`,
   marks PUBLISHED after ack. Retries with exponential backoff; DLQ after 10 attempts.

6. **Recovery:** `RecoveryScheduler` (line 53, `@Scheduled`) finds non-terminal
   payments past timeout, re-enters PROCESSING, schedules retry with backoff.

**Known gaps:** Compile errors in Stage 5 handoff, `@EnableScheduling` missing,
byte-exact idempotency replay not implemented, HTTP 202 for UNKNOWN not wired,
no production ERP consumer."

## 3. 5-Minute Deep Explanation

### Domain Model

The `Payment` aggregate (`Payment.java:37`) is the source of truth. It's created
via `Payment.create()` (line 110), which sets `status = CREATED` and validates all
fields. State transitions are enforced by `PaymentStateEngine.transition()` (line 61)
using an exhaustive `switch` — invalid transitions throw
`IllegalStateTransitionException`.

The `PaymentStatus` enum (`PaymentStatus.java:31`) defines 8 states:
- **Non-terminal:** CREATED, PROCESSING, UNKNOWN, REQUIRES_RECONCILIATION
- **Terminal:** SUCCEEDED, FAILED, VOIDED, REFUNDED

`VOIDED` and `REFUNDED` exist in the enum but are **not reachable** from current
code paths — they're Stage 4+ features.

The provider result is modeled as `ProviderResult` (record, `ProviderResult.java:18`):
- `SUCCESS` → SUCCEEDED
- `DECLINED` → FAILED
- `TECHNICAL_FAILURE` → FAILED (safe: no money moved)
- `UNKNOWN` → UNKNOWN (ambiguous: provider may have debited)

### Money and Precision

`Money` (value object) wraps `BigDecimal` with a `Currency` enum
(`Currency.java`). Scale is validated at construction — `Money.of("10.001",
INR)` throws because INR has scale 2. The database stores minor units
as `BIGINT` (e.g. ₹1,250.00 → 125000 paise). On read, `longValueExact()`
fails if the stored value is fractional — catching data corruption.

### Transaction Model

```java
// ChargeService.chargeWithOutcome() — two-phase
TX1: transactionTemplate.execute(status -> {
    PaymentEntity entity = PaymentEntity.fromDomain(created);
    paymentRepository.saveAndFlush(entity);           // INSERT payment (CREATED)
    OutboxEventEntity event = outboxEventService.append(...);  // INSERT outbox (PaymentCreated)
    idempotencyService.reserve(...);                  // reserve idempotency key
    return created;
});  // COMMIT — payment is durable before provider call

ProviderResult result = processor.process(...);  // OUTSIDE transaction

TX2: transactionTemplate.execute(status -> {
    PaymentEntity entity = paymentRepository.findAndLockByPaymentId(paymentId);  // FOR UPDATE
    Payment payment = entity.toDomain(null);
    payment.markProcessing();              // CREATED → PROCESSING
    OutboxEventEntity procEvent = outboxEventService.append(..., PAYMENT_PROCESSING_STARTED);
    payment.applyProviderResult(result);   // → SUCCEEDED/FAILED/UNKNOWN
    OutboxEventEntity resultEvent = outboxEventService.append(...);
    idempotencyService.finalize(...);      // cache response for replay
    entity.updateFromDomain(payment);
    paymentRepository.flush();
    return payment;
});  // COMMIT — result is durable
```

### Idempotency

The idempotency contract (`IdempotencyService.java` interface,
`PostgresIdempotencyStore.java` implementation):
- `reserve()`: `SELECT FOR UPDATE` on existing row. If none, `INSERT` reservation.
  Returns `Proceed`, `ReplayOutcome`, or `ConflictOutcome`.
- `finalize()`: Updates the reservation with the terminal response (status code +
  JSON body + `is_terminal=true`).
- `replay()`: Reads the committed idempotency row, returns cached response.

The unique constraint on `(merchant_id, idempotency_key)` is the ultimate arbiter
for concurrent requests. The controller catches SQL 23505 and calls `replay()`.

**Provider idempotency:** `Payment.ensureProviderIdempotencyKey()` (line 218)
generates `prov_<paymentId>` deterministically. `SimulatedPaymentProcessor`
replays the first result per key (line 70-77). This prevents double-charging
on retries.

### Outbox and Kafka

The transactional outbox (`OutboxEventEntity.java`, `OutboxEventRepository.java`,
`V4__payment_outbox.sql`):
- Written in TX1 and TX2 (atomic with payment state changes)
- `event_id` is UUID UNIQUE (consumer dedup key)
- `event_order` from `outbox_event_order_seq` (per-payment ordering)
- `findDue()` uses `FOR UPDATE SKIP LOCKED` + `NOT EXISTS` subquery
  (skips later events for a payment when earlier ones are pending)

`OutboxPublisher.publishDueEvents()` (line 55):
- `@Scheduled(fixedDelay = 1s)` — polls pending rows
- `claim()` — CAS on `lock_owner` + `locked_until` (lease)
- Sends to `payment.events` with `eventKey = payment UUID`
- `.get(sendTimeout)` — blocks for Kafka ack
- `markPublished()` only after ack
- `handleFailure()` — exponential backoff, DLQ after 10 attempts, `markDeadLettered()`

**Delivery guarantee:** At-least-once. A crash after Kafka ack but before
`PUBLISHED` update re-publishes. Consumers deduplicate by `event_id`.

**KNOWN GAP:** `@EnableScheduling` is missing from
`PaymentGatewaySettlementApplication.java`. Scheduled methods on
`OutboxPublisher` and `RecoveryScheduler` won't fire in a running app. Tests
call publishers directly.

### Recovery

`RecoveryScheduler.runRecoverySweep()` (line 53): `@Scheduled(fixedDelay=60s,
initialDelay=30s)`.

`PaymentRecoveryService.sweep()` (line 70):
- Finds payments in CREATED/PROCESSING/UNKNOWN/REQUIRES_RECONCILIATION
  older than `createdTimeoutMs` and with `next_retry_at <= now`
- For each: `findAndLockByPaymentId` (FOR UPDATE), check `attemptCount < maxRetries`
- If retries exhausted: `markRetryExhausted()`, append `PaymentRetryExhausted` event
- Otherwise: append `PaymentRetryScheduled` event, re-enter PROCESSING,
  schedule next retry with `baseBackoffMs * 2^attemptCount` (capped at `maxBackoffMs`)

**KNOWN GAP:** Recovery transitions to PROCESSING but does NOT call
`processor.process()` — the provider re-submission is not wired in. This means
recovery schedules a retry and marks it PROCESSING, but doesn't actually re-attempt
the provider call. The provider call only happens in `ChargeService.chargeWithOutcome()`.

## 4. Architecture Explanation

See `docs/interview-preparation/full-project-flow.md` → Section "Mermaid Diagram:
High-Level Architecture" for the full diagram.

**Key components:**
- **API Layer** (`PaymentController`) — HTTP entry/exit, request validation, idempotency routing
- **Application Layer** (`ChargeService`, `PaymentRecoveryService`, `OutboxEventService`) — `@Transactional` orchestration
- **Domain Layer** (`Payment`, `PaymentStateEngine`, `Money`, `ProviderResult`) — pure Java, no framework deps
- **Ports** (`PaymentProcessor`, `IdempotencyService`) — interfaces in `application/port/`
- **Adapters** (`SimulatedPaymentProcessor`, `PostgresIdempotencyStore`, `OutboxEventRepository`) — in `infrastructure/`
- **Event Infrastructure** (`OutboxPublisher`, `KafkaOutboxConfiguration`) — at-least-once delivery, DLQ, lease-based claiming

**Data flow:** ERP → HTTP → Controller → ChargeService (TX1) → PostgreSQL → Provider (outside TX) → ChargeService (TX2) → PostgreSQL + Outbox → OutboxPublisher → Kafka → ERP consumer (test fixture)

**Data ownership:** PostgreSQL = financial truth. Redis = cache only. Kafka = event delivery. ERP owns invoices.

## 5. Payment Lifecycle Explanation

```
CREATED → PROCESSING → SUCCEEDED | FAILED | UNKNOWN | REQUIRES_RECONCILIATION
UNKNOWN → SUCCEEDED | FAILED | PROCESSING (retry)
REQUIRES_RECONCILIATION → SUCCEEDED | FAILED | PROCESSING (retry)
```

- **CREATED:** Payment record persisted, idempotency reserved, not yet submitted to provider
- **PROCESSING:** Submitted to provider, awaiting response (inside TX2 only, briefly)
- **SUCCEEDED:** Provider confirmed success (terminal)
- **FAILED:** Provider declined or technical failure (terminal, no money moved)
- **UNKNOWN:** Provider returned ambiguous result (may have debited) — non-terminal
- **REQUIRES_RECONCILIATION:** Timeout with bank discrepancy — non-terminal (but not reachable from current code)

## 6. Idempotency Explanation

**Three-tier safety:**
1. **Client key** (`Idempotency-Key` header) — merchant-scoped, unique constraint on `(merchant_id, idempotency_key)`
2. **Request fingerprint** (SHA-256) — distinguishes same-key-same-payload from same-key-different-payload
3. **Provider key** (`prov_<paymentId>`) — stable across retries, deduplicated by provider

**Behavior:**
- Same key + same payload → 200 OK (cached)
- Same key + different payload + non-terminal → 409 Conflict
- Same key + different payload + terminal → 200 OK (terminal replay)
- Concurrent race (23505) → catch, replay, 200 OK

## 7. Concurrency Explanation

**Four mechanisms:**
1. **Unique constraint** — serializes concurrent idempotency reservations at the DB level
2. **`SELECT FOR UPDATE`** — locks payment/idempotency rows during state transitions
3. **`@Version`** — optimistic locking catches lost updates (retried 3x)
4. **`FOR UPDATE SKIP LOCKED`** — allows parallel outbox publishing without blocking

**`synchronized` is insufficient** — only works within one JVM. In a multi-instance
deployment, the DB unique constraint is the cross-instance arbiter.

## 8. Provider Timeout Explanation

A timeout does NOT mean failure — the provider may have debited the customer.
Marking it FAILED + retrying with a new key = double-charge.

**Solution:** UNKNOWN state (non-terminal) + recovery re-submits with the SAME
provider idempotency key. The simulated provider (and real providers like Stripe)
deduplicate by this key, returning the original result. No second charge.

## 9. Recovery Explanation

**Trigger:** `RecoveryScheduler` (`@Scheduled`, needs `@EnableScheduling`)

**Process:**
1. Query: non-terminal payments with `created_at < now - createdTimeoutMs`
2. Lock: `SELECT FOR UPDATE` on payment row
3. Check: `attemptCount < maxRetries` (default 3)
4. If exhausted: `markRetryExhausted()`, append `PaymentRetryExhausted` event
5. If due: append `PaymentRetryScheduled` event, re-enter PROCESSING,
   schedule next retry with exponential backoff (`baseBackoffMs * 2^attempt`)
6. **GAP:** Does NOT call `processor.process()` — provider re-submission not wired

**Safety:** Provider idempotency key is stable. State transitions are atomic.
`@Version` prevents lost updates. Each payment is locked individually.

## 10. Transactional Outbox Explanation

**Problem:** Can't have a single ACID transaction across PostgreSQL + Kafka.
Publish-then-commit → phantom events if DB fails. Commit-then-publish →
lost events if app crashes between commit and publish.

**Solution:** Write an `outbox` row in the same transaction as the payment state
change. The row is durable. A background publisher reads committed rows and
publishes to Kafka. If Kafka is down, rows accumulate — payment is unaffected.

**Mechanism:**
- `OutboxEventEntity` written in TX1/TX2 (atomic with payment)
- `OutboxPublisher` polls with `FOR UPDATE SKIP LOCKED`
- `claim()` uses CAS on `lock_owner` for concurrency
- `markPublished()` only after Kafka ack (`.get(timeout)`)
- Exponential backoff retry (1s → 30s capped), DLQ after 10 attempts

**Guarantee:** At-least-once. Consumers deduplicate by `event_id`. Per-payment
ordering via `event_order` + Kafka key = payment UUID.

## 11. Kafka Explanation

**Topics:** `payment.events` (6 partitions), `payment.events.DLQ` (1 partition)

**Producer:** `StringSerializer`, `acks=all`, `enable.idempotence=true`,
`retries=3`, `max.in.flight.requests.per.connection=1`

**Consumer (test fixture only):** `StringDeserializer`, `group.id=external-erp-payment-events`,
`isolation.level=read_committed`, `enable.auto.commit=false`

**Key design:**
- Message key = payment UUID → same partition → per-payment ordering
- Consumer factory defined but **no `@KafkaListener`** → ERP consumer NOT implemented
- Test fixture (`ErpPaymentEventFixture`) deduplicates by `event_id`

**Delivery guarantee:** At-least-once, NOT exactly-once. Producer idempotence
only prevents intra-session duplicates. Consumers must deduplicate.

## 12. ERP Integration Explanation

**The ERP owns:** Customer records, bill/invoice generation, outstanding balance,
customer UI.

**The gateway owns:** Payment initiation, provider integration, idempotency,
state machine, events, observability.

**Integration paths:**
1. **Push (primary):** ERP calls `POST /api/v1/payments` → gateway returns 201/200/202
2. **Event stream:** Gateway publishes to `payment.events` → ERP consumes (NOT implemented —
   only test fixture)
3. **Polling (fallback):** ERP calls `GET /api/v1/payments/{id}` for status

**Never:** Gateway does NOT update ERP invoice tables. `billRef` is an opaque
reference for correlation only.

## 13. Testing Explanation

**Three tiers:**
1. **Domain unit tests** (`PaymentStateEngineTest.java`): 20 tests, no Spring,
   no DB. Tests all state transitions and invariants.
2. **Repository/service tests** (`PaymentTest.java`, `MoneyTest.java`): Pure
   unit tests for domain logic.
3. **Integration tests** (Testcontainers):
   - `PaymentOutboxTransactionIntegrationTest`: Verifies TX1+outbox atomicity,
     rollback behavior, invalid transitions
   - `KafkaOutboxIntegrationTest`: Verifies Kafka publishing, consumer dedup,
     event ordering, concurrency
   - `OutboxPublisherRetryIntegrationTest`: Publisher retry + DLQ behavior
   - Uses dynamic ports via `@ServiceConnection` — no hardcoded host ports

**Gap:** Mocked tests are insufficient for DB locking — real PostgreSQL
containers are needed to test unique constraints, `SELECT FOR UPDATE`, and
23505 handling.

## 14. Observability Explanation

**Logging (MDC):**
```yaml
pattern: "...[traceId=%X{traceId} correlationId=%X{correlationId} paymentId=%X{paymentId} merchantId=%X{merchantId}] %logger{36} - %msg%n"
```
- `CorrelationIdFilter` sets correlationId in MDC
- `RequestLoggingFilter` scrubs sensitive fields (`paymentToken`, `password`) from DEBUG body
- Sensitive headers filtered: `Authorization`, `X-Correlation-Id`, `Cookie`

**Metrics (Micrometer via Prometheus):**
- `payment_charge_requests_total{status, paymentMethod, outcome}`
- `payment_state_duration_seconds` (Timer)
- `idempotency_conflicts_total{reason}`
- `provider_errors_total{paymentMethod, errorType}`
- `db_lock_wait_seconds{table}` (from pg_stat_activity poll)
- `outbox_backlog_count` (Gauge)
- `outbox_publish_failures_total`
- `kafka_consumer_lag{topic, partition}`
- `dlq_messages_total{topic}`

**Health:** Actuator on port 8081 (`/actuator/health`, `/actuator/prometheus`)
plus custom `/internal/health` for K8s probes.

## 15. Known Limitations and Future Improvements

**Stage 5 (PARTIAL):**
| Item | Status | Notes |
|------|--------|-------|
| Compile errors | PARTIAL | `ReplayDuringReservationException` undefined, `ChargeResult` arity mismatch |
| `@EnableScheduling` | MISSING | Scheduled methods won't auto-fire |
| `ApplicationContextTest` | BROKEN | Excludes JPA config → `IdempotencyRepository` unavailable |
| ERP consumer | NOT IMPLEMENTED | Consumer factory exists, no `@KafkaListener` |
| Byte-exact replay | NOT IMPLEMENTED | Controller rebuilds from Payment, not cached JSON |
| HTTP 202 for UNKNOWN | DEFERRED | Controller returns 201, not 202 |
| Recovery → provider call | GAP | Recovery doesn't call `processor.process()` |

**Deferred to Stage 6+:**
- Settlement & reconciliation (file matching, discrepancy events)
- Double-entry ledger (journal entries, chart of accounts)
- Refunds & chargebacks (REFUNDED state)
- Cancellation/void API (VOIDED state)
- Webhook/callback endpoint
- Circuit breaker on provider calls
- Rate limiting
- Authentication/authorization
- Global unique constraints / triggers for state validation

---

## Important Terminology

| Term | Meaning |
|------|---------|
| **At least once** | Event may be delivered multiple times; consumers must deduplicate (NOT exactly-once) |
| **Idempotency key** | Client-supplied key scoped by merchant; ensures one payment per key |
| **Provider idempotency** | Provider-side dedup key (`prov_<paymentId>`) sent on every attempt |
| **UNKNOWN** | Non-terminal state for ambiguous provider outcomes (timeout = may have debited) |
| **FOR UPDATE SKIP LOCKED** | PostgreSQL pattern for parallel workers without blocking |
| **Outbox** | Table storing events to publish; written in same TX as payment state |
| **REQUIRES_RECONCILIATION** | Non-terminal state for timeout + bank discrepancy (not reachable from current code) |
| **TransactionTemplate** | Programmatic transaction management for explicit two-phase boundaries |
| **Virtual threads** | JVM-managed threads, multiplexed onto fewer OS threads |

## Important Database Concepts

| Concept | Details |
|---------|---------|
| **Unique constraint** | `UNIQUE (merchant_id, idempotency_key)` — serializes concurrent requests |
| **SELECT FOR UPDATE** | Row-level lock; blocks other transactions until commit |
| **@Version** | Optimistic locking; `version` column increments on update |
| **FOR UPDATE SKIP LOCKED** | Parallel outbox publishing without blocking |
| **Minor units** | Amounts stored as `BIGINT` (paise/cents); avoids floating-point errors |
| **JSONB** | `event_payload` and `response_body` stored as JSONB for indexing |
| **event_id UNIQUE** | Consumer dedup key on outbox table |
| **CHECK constraints** | `status IN ('PENDING','PUBLISHED','FAILED','DEAD_LETTERED')` on outbox |
| **Foreign key** | `outbox.aggregate_id REFERENCES payment(payment_id)` — atomic with payment TX |

## Important State Transitions

| From | To | Trigger | Key Safety |
|------|-----|---------|------------|
| CREATED | PROCESSING | `markProcessing()` | Provider key stable |
| PROCESSING | SUCCEEDED | `applyProviderResult(SUCCESS)` | Idempotent apply |
| PROCESSING | FAILED | `applyProviderResult(DECLINED/TECHNICAL_FAILURE)` | No money moved |
| PROCESSING | UNKNOWN | `applyProviderResult(UNKNOWN)` | Non-terminal |
| UNKNOWN | PROCESSING | `markRetrySubmitted()` (recovery) | Same provider key |
| UNKNOWN | SUCCEEDED | `resolveReconciliation()` (Stage 6) | Reconciliation only |
| REQUIRES_RECONCILIATION | PROCESSING | `markRetrySubmitted()` (recovery) | Same provider key |
| Any terminal | * | — | Blocked by state engine |

## Important Failure Scenarios

| Scenario | Risk | Mitigation |
|----------|------|------------|
| Provider timeout | Double-charge | UNKNOWN state + stable provider key |
| App crash after TX1 | Payment stuck in CREATED | Recovery scheduler |
| App crash after TX2 | Idempotency not finalized | Replay on client retry |
| Duplicate Kafka event | Double-processed by ERP | Consumer dedup by event_id |
| DB unique violation | Concurrent duplicate | Catch 23505 → replay |
| Optimistic lock | Lost update | Retry 3x with backoff |
| Kafka unavailable | Events not published | Outbox stays PENDING; publisher retries |
| Recovery crash | Stuck payment | Next sweep re-locks via FOR UPDATE |

## Common Interview Traps

| What to say | What NOT to say |
|-------------|-----------------|
| "At-least-once, not exactly-once" | "Exactly-once delivery" |
| "PostgreSQL unique constraint is the source of truth" | "synchronized block prevents duplicates" |
| "UNKNOWN because timeout ≠ failure" | "Timeout = failure; retry with new key" |
| "Recovery uses stable provider idempotency key" | "Retry with new idempotency key" |
| "Outbox decouples payments from Kafka" | "Payments wait for Kafka ack" |
| "State machine prevents invalid transitions" | "Database enforces all constraints" |
| "Recovery re-enters PROCESSING" | "Recovery auto-charges the customer" |
| "Two-phase TX: lock only during quick transition" | "One transaction holds lock during provider call" |
| "Testcontainers for DB locking tests" | "Unit tests cover concurrency" |
| "Multiple known gaps — compile errors, missing @EnableScheduling" | "Production-ready" |

## Phrases to Avoid (Overclaim)

| Phrase | Why it's wrong | What to say instead |
|--------|----------------|---------------------|
| "Exactly-once processing" | Violates the at-least-once design | "At-least-once with consumer deduplication" |
| "Production-ready" | Multiple known gaps exist | "Production-oriented educational system with known gaps" |
| "The ERP consumer processes events" | Only a test fixture exists | "The outbox publisher sends to Kafka; the ERP consumer is deferred" |
| "HTTP 202 is returned for UNKNOWN" | Controller returns 201 | "ChargeResult computes 202, but the controller mapping is deferred" |
| "Byte-exact replay" | Response is rebuilt from Payment, not cached JSON | "Response is rebuilt from the payment aggregate, not cached byte-exact" |
| "Auto-scheduled recovery" | `@EnableScheduling` is missing | "Recovery is data-driven but scheduling is disabled" |
| "Recovery calls the provider" | Recovery doesn't call `processor.process()` | "Recovery re-enters PROCESSING; provider re-submission is a gap" |
| "REQUIRES_RECONCILIATION is reachable" | No code path produces this state | "REQUIRES_RECONCILIATION is a defined state but not produced by current code" |
| "Integration tests pass" | Compile errors block all tests | "Unit tests for the state machine pass; integration tests are blocked by compile errors" |