# Glossary

> Only terms marked **IMPLEMENTED** are supported by the actual repository code.
> Terms marked **NOT IMPLEMENTED** or **DEFERRED** exist only in documentation
> or as stubs.

---

## Domain & Architecture

### aggregate
**IMPLEMENTED.** A cluster of domain objects treated as a single unit for data
changes. In this project, `Payment` is the aggregate root — it owns the payment
state and enforces invariants. All mutations go through the aggregate, never
directly on the entity. See `Payment.java:37`.

### value object
**IMPLEMENTED.** An immutable object defined by its structural value, not its
identity. In this project: `Money`, `Currency`, `PaymentId`, `IdempotencyKey`,
`ProviderResult`, `ProviderAttempt`, `PaymentLifecycleEvent`, `ChargeResult`.
Value objects are compared by `equals()`, never by reference.

### idempotency
**IMPLEMENTED.** The property that making the same request multiple times has
the same effect as making it once. The API uses an `Idempotency-Key` header;
the PostgreSQL `idempotency` table with a `UNIQUE (merchant_id, idempotency_key)`
constraint ensures at most one payment per key, even under concurrency.

### transactional outbox
**IMPLEMENTED.** A pattern for reliable event publishing: events are written to
an `outbox` table in the same database transaction as the business data change.
A background publisher later reads committed rows and publishes them to Kafka.
This decouples business transactionality from event delivery. Implemented via
`OutboxEventEntity`, `OutboxEventRepository`, `OutboxEventService`, `OutboxPublisher`,
and the `V4__payment_outbox.sql` migration.

### at-least-once delivery
**IMPLEMENTED (by design).** A delivery guarantee where each message is
delivered one or more times. The outbox publisher marks a row `PUBLISHED` only
after receiving a Kafka acknowledgement. If the app crashes between the ack
and the DB update, the row stays `PENDING` and is re-published. Consumers must
deduplicate by `event_id`. Explicitly NOT exactly-once. See
`docs/stage-5-outbox-kafka-erp.md:163-166`.

---

## Concurrency & Transactions

### optimistic locking
**IMPLEMENTED.** A concurrency control strategy using a version column.
`PaymentEntity` has `@Version private Long version` (line 117). On each update,
Hibernate includes `WHERE version = ?` in the SQL. If the version has changed
(concurrent update), the update affects 0 rows and JPA throws
`ObjectOptimisticLockingFailureException`. `ChargeService.applyProviderResult()`
retries 3 times.

### pessimistic locking
**IMPLEMENTED.** A concurrency control strategy using database row locks.
`PaymentRepository.findAndLockByPaymentId()` uses `@Lock(PESSIMISTIC_WRITE)`
(line 39), issuing `SELECT ... FOR UPDATE`. `IdempotencyRepository.lockByKey()`
(line 34) does the same. `OutboxEventRepository.findDue()` uses
`FOR UPDATE SKIP LOCKED` in a native query.

### SELECT FOR UPDATE
**IMPLEMENTED.** A PostgreSQL statement that acquires an exclusive row lock
until the transaction commits. In this project, used via JPA `@Lock` on
`PaymentRepository.findAndLockByPaymentId` and `IdempotencyRepository.lockByKey`.
Also used explicitly in the `OutboxEventRepository.findDue` native query
with `SKIP LOCKED` for parallel publishing.

### deadlock
**IMPLEMENTED (handled by design).** PostgreSQL detects deadlocks (SQL state
`40P01`) and kills one transaction. The `docs/architecture.md:414` documents a
consistent lock ordering (idempotency → payment → journal → outbox) and a
retry policy (≤3 retries with jittered backoff). The actual retry logic for
optimistic lock conflicts is in `ChargeService.applyProviderResult()` (line
323-336).

### transaction
**IMPLEMENTED.** A database ACID unit. `ChargeService` uses `TransactionTemplate`
for two-phase transactions: TX1 (create + reserve) and TX2 (apply result +
finalize). The provider call happens outside both. `@Transactional` is used on
simpler methods like `OutboxEventService.append()`, `Payment.getPayment()`,
`PaymentRecoveryService.sweep()`.

### isolation level
**IMPLEMENTED (implicitly).** PostgreSQL defaults to `READ_COMMITTED`. The
project relies on this default plus explicit `SELECT FOR UPDATE` for row-level
locking. No custom isolation level is configured in `application.yml` or
`TransactionTemplate`. Documented in `docs/architecture.md:412`.

### unique constraint
**IMPLEMENTED.** Database-level enforcement of business rules:
- `UNIQUE (merchant_id, idempotency_key)` on `idempotency` table
- `event_id UNIQUE` on `outbox` table
- `uq_outbox_payment_order UNIQUE (aggregate_id, event_order)` on `outbox`
- `uq_payment_provider_idempotency_key` on `payment` (WHERE NOT NULL)
- `uq_payment_provider_ref` on `payment` (WHERE NOT NULL)

---

## Database Schema

### PostgreSQL
**IMPLEMENTED.** PostgreSQL 16 (per `docker-compose.yml:3`). Used as the
financial source of truth. Migrations via Flyway 11.3.1. Connection pool:
Hikari (default in Spring Boot 3.3).

### JPA / Hibernate
**IMPLEMENTED.** Spring Data JPA (`spring-boot-starter-data-jpa`). Entities:
`PaymentEntity`, `IdempotencyEntity`, `OutboxEventEntity`. Repositories are
Spring Data interfaces extending `JpaRepository`. `ddl-auto: validate`
in `application.yml:15` — schema managed by Flyway, not Hibernate.

### entity
**IMPLEMENTED.** JPA-annotated classes mapping to database tables:
- `PaymentEntity` → `payment` table (`PaymentEntity.java:32`)
- `IdempotencyEntity` → `idempotency` table (`IdempotencyEntity.java:23`)
- `OutboxEventEntity` → `outbox` table (`OutboxEventEntity.java:16`)

### repository
**IMPLEMENTED.** Spring Data JPA interfaces:
- `PaymentRepository` — `findByPaymentId`, `findAndLockByPaymentId` (@Lock),
  `findByBillRefAndMerchantId`, `findForRecovery`, count methods
- `IdempotencyRepository` — `lockByKey` (@Lock), `findByMerchantIdAndIdempotencyKey`,
  `insertReservation` (native INSERT)
- `OutboxEventRepository` — `findDue` (native, SKIP LOCKED), `claim`,
  `markPublished`, `markRetryOrFailed`, `markDeadLettered`, `nextEventOrder`

### version column
**IMPLEMENTED.** The `@Version` annotation on `PaymentEntity.version` (line 117)
and `IdempotencyEntity` (documented). On each update, Hibernate increments the
version and includes `WHERE version = ?` in the SQL, enabling optimistic locking.

### index
**IMPLEMENTED.** Key indexes:
- `idx_payment_bill_ref` — lookup by ERP bill reference
- `idx_payment_merchant` — tenant-scoped queries
- `idx_payment_status` — batch processing by status
- `idx_payment_recovery_created` — recovery by status + created_at
- `idx_payment_recovery_next_retry` — recovery by next_retry_at
- `idx_idempotency_payment` — reverse lookup (payment → idempotency)
- `idx_idempotency_expires` — find expired rows
- `idx_outbox_due` — poll pending outbox events

### index
**IMPLEMENTED.** Key indexes:
- `idx_payment_bill_ref` — lookup by ERP bill reference
- `idx_payment_merchant` — tenant-scoped queries
- `idx_payment_status` — batch processing by status
- `idx_payment_recovery_created` — recovery by status + created_at
- `idx_payment_recovery_next_retry` — recovery by next_retry_at
- `idx_idempotency_payment` — reverse lookup (payment → idempotency)
- `idx_idempotency_expires` — find expired rows
- `idx_outbox_due` — poll pending outbox events

### Flyway
**IMPLEMENTED.** Database migration tool. `flyway-core` 11.3.1 and
`flyway-database-postgresql` 11.3.1 (overridden in `pom.xml:41-50`).
Migrations: `V3__idempotency_provider_retry.sql` and `V4__payment_outbox.sql`
(verified). `baseline-on-migrate: true` (per `docs/foundation-decisions.md:42`).
`docs/architecture.md` references V1 and V2 but those files were not read
during inspection — assumed to exist.

### minor units
**IMPLEMENTED.** Monetary amounts stored as integer minor units (e.g. cents,
paise). `Money.toMinorUnits()` (line ~) converts `BigDecimal` to `long`.
`PaymentEntity.fromDomain` stores `BigDecimal.valueOf(payment.getAmount().toMinorUnits())`.
`PaymentEntity.toDomain` validates the stored value is an exact integer via
`longValueExact()`. Database column: `amount_minor DECIMAL(18,2)` — the scale
is cosmetic; application asserts integer-only.

---

## Idempotency

### request fingerprint
**IMPLEMENTED.** A SHA-256 digest of the canonical request fields (merchantId,
customerRef, billRef, amount, currency, method, token) computed in
`PaymentController.requestFingerprint()` (line 202) and
`ChargeService.requestFingerprint()` (line 374). The payment token is masked
before hashing (`maskToken` extracts only the prefix, replaces rest with `*****`).
Stored as `request_hash` (VARCHAR(64)) in the idempotency table.

### database-level uniqueness
**IMPLEMENTED.** The `UNIQUE (merchant_id, idempotency_key)` constraint on the
`idempotency` table ensures at most one reservation per key, even under
concurrent requests. Concurrent inserts cause SQL state 23505 (unique violation),
which the controller catches and resolves via `replay()`. This is documented in
`docs/idempotency-design.md:29` and `docs/architecture.md:574`.

### concurrent duplicate requests
**IMPLEMENTED.** Two concurrent requests with the same key both do
`SELECT FOR UPDATE` (find no row), both try `INSERT`. One wins (commits);
the other gets 23505. `PaymentController` catches this (line 117-135),
calls `idempotencyService.replay()`, and returns the cached 200 response.
See `docs/interview-preparation/full-project-flow.md` Flow 5.

### replaying the original result
**PARTIALLY IMPLEMENTED.** The idempotency table stores `response_body` (JSON),
`response_status` (int), and `is_terminal` (boolean). `PostgresIdempotencyStore.replay()`
(line 112-122) returns a `ReplayOutcome` with these fields. However, the
controller currently rebuilds the response from the `Payment` domain object
(`PaymentDtoMapper.toResponse(payment)`) rather than returning the cached
`response_body` JSON byte-for-byte. See `docs/stage-5-status.md:79`.

### provider idempotency
**IMPLEMENTED.** A provider-specific idempotency key (`prov_<paymentId>`)
is derived deterministically by `Payment.ensureProviderIdempotencyKey()`
(line 218). It's passed to `PaymentProcessor.process()` and persists across
retries. The `SimulatedPaymentProcessor` caches the first result per key
and replays it (line 70-77). Real providers (Stripe, Adyen) deduplicate
by this key. Unique constraint `uq_payment_provider_idempotency_key` prevents
collisions.

---

## Provider Processing

### provider abstraction
**IMPLEMENTED.** `PaymentProcessor` is a Java interface in
`application/port/PaymentProcessor.java` (line 39). It defines a single
method: `process(String paymentToken, long amountMinor, String currency,
UUID correlationId, String providerIdempotencyKey)`. `SimulatedPaymentProcessor`
in `infrastructure/external/provider/` is the current implementation.
The interface is NOT sealed (despite `docs/architecture.md:261` showing a
`sealed interface` example — the actual code is a plain interface).

### simulated provider
**IMPLEMENTED.** `SimulatedPaymentProcessor.java` (line 49). An in-process
@Component that determines outcomes by token prefix: `success:`, `decline:`,
`timeout:`, `error500:`, `connfail:`, `unknown:`. Uses `ConcurrentHashMap`
for provider idempotency caching. All 6 scenarios tested in
`SimulatedPaymentProcessorTest.java`.

### provider idempotency key
**IMPLEMENTED.** See "provider idempotency" above.

### provider timeout
**IMPLEMENTED (conceptually).** The simulated provider doesn't simulate
actual HTTP timeouts — the `timeout:` token returns `ProviderResult.unknown()`
immediately (line 86-88). In production, a real provider timeout would throw
a `SocketTimeoutException`, caught by `ChargeService` (line 200) and converted
to `ProviderResult.technicalFailure()`. However, this maps to `FAILED`, not
`UNKNOWN`. A real timeout should map to `UNKNOWN` — this is an implementation
detail of the real provider adapter, not the simulated one.

### uncertain provider result
**IMPLEMENTED.** `ProviderResult.Type.UNKNOWN` (line 25) → `PaymentStatus.UNKNOWN`
(via `Payment.mapResultToStatus`, line 297). The payment stays non-terminal.
`ProviderResult.isAmbiguous()` (line 55) returns true for UNKNOWN.

### duplicate provider request
**IMPLEMENTED.** The `SimulatedPaymentProcessor` caches results by
`providerIdempotencyKey` (line 70-77, using `putIfAbsent`). A duplicate call
with the same key returns the cached result. `Payment.applyProviderResult()`
(line 149-158) also handles duplicate results idempotently (terminal check).

### provider reference
**IMPLEMENTED.** `ProviderResult.providerReference()` is stored on
`Payment.providerReference` (line 167). The `payment` table has a unique
constraint `uq_payment_provider_ref UNIQUE (provider_ref) WHERE provider_ref IS NOT NULL`
(migration V2, per `docs/architecture.md:327`). This prevents two payments
from claiming the same provider transaction.

### why a timeout does not necessarily mean payment failure
**IMPLEMENTED.** Documented extensively in `docs/architecture.md:281` and
`docs/provider-simulator.md:46-53`. The `UNKNOWN` state preserves the
payment as non-terminal until a confirmed outcome is received. A retry with
the same provider idempotency key is safe (provider deduplicates). This
prevents double-charging.

---

## Recovery

### CREATED stuck state
**IMPLEMENTED.** `PaymentRecoveryService.sweep()` (line 70) queries for
payments in CREATED/PROCESSING/UNKNOWN/REQUIRES_RECONCILIATION older than
`createdTimeoutMs` (default 60s, `PaymentRepository.findForRecovery`, line 65).
`Payment.markRetrySubmitted()` transitions CREATED → PROCESSING on recovery.

### PROCESSING stuck state
**IMPLEMENTED.** Same `findForRecovery` query includes PROCESSING.
`processingTimeoutMs` (default 300s) controls eligibility.

### application crash
**IMPLEMENTED.** All recovery state is persisted in PostgreSQL (attemptCount,
nextRetryAt, providerIdempotencyKey). Recovery runs on restart via
`@Scheduled` (but `@EnableScheduling` is missing — see implementation gap).

### retry metadata
**IMPLEMENTED.** `Payment` fields: `attemptCount`, `lastAttemptAt`,
`nextRetryAt`, `lastFailureReason` (lines 56-71). Persisted as columns in
the `payment` table (V3 migration, lines 57-62).

### exponential backoff
**IMPLEMENTED.** `PaymentRecoveryService.recoverPayment()` (line 161-163):
`backoff = min(baseBackoffMs * 2^attemptCount, maxBackoffMs)`.
Defaults: base 1000ms, max 30000ms.

### retry limits
**IMPLEMENTED.** `RecoveryProperties.maxRetries` (default 3, line 18).
`PaymentRecoveryService` checks `attemptCount >= maxRetries` (line 134) and
calls `markRetryExhausted()`.

### scheduled recovery
**IMPLEMENTED (but broken).** `RecoveryScheduler.runRecoverySweep()` is
annotated `@Scheduled(fixedDelay = 60000L, initialDelay = 30000L)` (line 53).
However, `@EnableScheduling` is NOT present on the application class —
scheduled methods won't fire. See `docs/stage-5-status.md:103-105`.

### UNKNOWN state
**IMPLEMENTED.** Recovery handles UNKNOWN (line 184-185): calls
`markRetrySubmitted()` → UNKNOWN → PROCESSING, then schedules next retry.
The payment idempotency key is reused for the provider call.

### REQUIRES_RECONCILIATION state
**IMPLEMENTED (recovery path only).** `PaymentRecoveryService` handles
REQUIRES_RECONCILIATION the same as UNKNOWN (line 184). However, no code
path produces REQUIRES_RECONCILIATION — `applyProviderResult` maps
TECHNICAL_FAILURE → FAILED, not → REQUIRES_RECONCILIATION.

### safe versus unsafe retries
**IMPLEMENTED (by design).** Safe retries:
- Same provider idempotency key (provider deduplicates)
- Idempotent apply (terminal check in `Payment.applyProviderResult`)
- Retry budget enforced (`maxRetries`)

Unsafe scenario documented but handled: retrying UNKNOWN with a NEW
provider key would potentially double-charge. The system prevents this
by always reusing `prov_<paymentId>`.

### REQUIRES_RECONCILIATION state
**IMPLEMENTED (recovery path only).** `PaymentRecoveryService` handles
REQUIRES_RECONCILIATION the same as UNKNOWN (line 184). However, no code
path produces REQUIRES_RECONCILIATION — `applyProviderResult` maps
TECHNICAL_FAILURE → FAILED, not → REQUIRES_RECONCILIATION.

---

## Kafka & Messaging

### Kafka topic
**IMPLEMENTED.** `payment.events` (6 partitions, 7-day retention, cleanup.policy=delete)
and `payment.events.DLQ` (1 partition). Topics created by `kafka-init` container
in `docker-compose.yml:57-69` and by `KafkaOutboxConfiguration` bean definitions
(line 48-60).

### event envelope
**IMPLEMENTED.** `PaymentLifecycleEvent` (line 8) is an 18-field record
containing: eventId, eventType, eventVersion, eventOrder, paymentId,
merchantId, erpReference, amountMinor, currency, paymentStatus,
previousPaymentStatus, providerReference, failureCode, occurredAt,
nextAttemptAt, retryAttempt, correlationId, causationId, reasonCode.
Serialized to JSON via `ObjectMapper` in `OutboxEventService.append()` (line 71).

### event version
**IMPLEMENTED.** All 7 event types in `PaymentEventType` (line 3-10) are
version 1. `PaymentLifecycleEvent.eventVersion` field (line 11) carries
the version. Consumers should retain events with unknown/incompatible
versions for inspection.

### consumer group
**IMPLEMENTED (test fixture).** `external-erp-payment-events`
(`OutboxProperties.java` and `application.yml:72`). The consumer factory
is defined in `KafkaOutboxConfiguration.erpPaymentEventConsumerFactory()`
(line 81-92) but **no `@KafkaListener` bean exists**. See
`docs/stage-5-status.md:78`.

### at-least-once delivery
**IMPLEMENTED (by design).** See "at-least-once delivery" above.

### duplicate event
**IMPLEMENTED (by design).** The `event_id` column is `UNIQUE` on the outbox
table — the publisher cannot re-insert a published event. For consumer-side
dedup, `ErpPaymentEventFixture` (test fixture) uses a `Set<UUID>`. In
production, the ERP would maintain a `processed_event` table.

### event ordering
**IMPLEMENTED.** Per-payment ordering via:
1. `event_order` from `outbox_event_order_seq` sequence (assigned in
   `OutboxEventService.append`, line 46)
2. `findDue` query's `NOT EXISTS` subquery skips later events while earlier
   ones for the same payment are pending
3. Kafka `event_key` = payment UUID → same partition → ordered delivery
4. Unique constraint `uq_outbox_payment_order` on `(aggregate_id, event_order)`

---

## Infrastructure

### correlation ID
**IMPLEMENTED.** A UUID that traces a request across system boundaries.
`CorrelationIdFilter` (line 1) generates or resolves it from the
`X-Correlation-Id` header and stores it in the MDC. All log lines include
it via the pattern in `application.yml:53`. Propagated through the
`PaymentLifecycleEvent.correlationId` field and returned in API responses.

### causation ID
**IMPLEMENTED.** Links an event to the event that caused it (its "parent").
In `OutboxEventService.append()`, the `causationId` parameter is the
`eventId` of the preceding event for the same payment. For example,
`PaymentSucceeded` has `causationId` = the `eventId` of `PaymentProcessingStarted`.
Stored in `PaymentLifecycleEvent.causationId` (line 26).

### dead-letter queue
**IMPLEMENTED.** `payment.events.DLQ` topic (1 partition). When an outbox
event fails to publish after `maxAttempts` (default 10), the `OutboxPublisher`
sends the payload to the DLQ (line 106-116). If the DLQ send succeeds,
the row is marked `DEAD_LETTERED`. If it also fails, the row is marked `FAILED`
for operator inspection.

### reconciliation
**DEFERRED (Stage 6).** `Payment.resolveReconciliation()` exists (line 245)
but is NOT called by any active code path. It would be invoked by a
reconciliation job that polls the provider's status endpoint and matches
against settlement files. The schema for `RECONCILIATION` and `RECON_RESULT`
tables is documented in `docs/architecture.md:386-400` but NOT in the
Flyway migrations.

### settlement
**DEFERRED (Stage 6).** No settlement tables or jobs exist in the codebase.
`docs/architecture.md:489-522` documents the design (CSV settlement file,
matching engine, classification matrix) but implementation is Stage 6.
The `SettlementFileGenerator` and `ReconciliationJob` mentioned in docs
are NOT in the source tree.

### ledger
**DEFERRED (Stage 4+).** The `Payment.journalEntryId` field exists (line 54)
and `PaymentEntity.journal_entry_id` column exists (line 91), but no
`LEDGER_ACCOUNT`, `JOURNAL_ENTRY`, or `JOURNAL_LINE` tables or services exist.
`docs/architecture.md:340-363` documents the schema, and `docs/architecture.md:84`
notes "Ledger | Deferred to Stage 6+" — no posting in the current baseline.

### chargeback
**NOT IMPLEMENTED.** No chargeback handling code exists. `PaymentStatus`
has no `CHARGEBACK` state. `docs/architecture.md` does not mention chargebacks.
Deferred to a future stage.

### webhook
**NOT IMPLEMENTED.** No webhook endpoint exists. `PaymentController` has
no method for receiving provider callbacks. `docs/payment-api.md:250` lists
"REST callback — DEFERRED; `callbackUrl` is not part of the current request DTO."

---

## Observability

### log
**IMPLEMENTED.** Structured logging via SLF4J + Logback. Pattern in
`application.yml:52-54` includes `correlationId=%X{correlationId}`,
`paymentId=%X{paymentId}`, `merchantId=%X{merchantId}`.

### metrics
**IMPLEMENTED.** Micrometer counters/timer via `OutboxMetrics.java` and
`ErpConsumerMetrics.java`. Prometheus registry via
`micrometer-registry-prometheus` (pom.xml:101-103). Metrics exposed on
`/actuator/prometheus` (port 8081). Documented in `docs/architecture.md:531-543`.

### health check
**IMPLEMENTED.** Spring Boot Actuator `/actuator/health` (includes DB, Redis
status). Custom `/api/v1/internal/health` endpoint via
`InternalHealthController.java` — returns app name, status, and correlation ID.

---

## API & HTTP

### Idempotency-Key
**IMPLEMENTED.** HTTP header, mandatory for `POST /api/v1/payments`.
Validated in `PaymentController` (line 81: `required = true`).
`IdempotencyKey.of()` validates non-blank and ≤255 chars.

### request body
**IMPLEMENTED.** `CreatePaymentRequest.java` — record with Bean Validation
annotations. Fields: merchantId, customerRef, billRef, amount, currency,
paymentMethod, paymentToken.

### payment response
**IMPLEMENTED.** `PaymentResponse.java` — contains paymentId, merchantId,
customerRef, billRef, amount, currency, paymentMethod, status, providerReference,
failureCode, correlationId, createdAt, updatedAt, links. Does NOT include
paymentToken.

### Actuator
**IMPLEMENTED.** Exposed on port 8081 at `/actuator`. Endpoints:
`health`, `info`, `metrics`, `prometheus`. Health shows details (`show-details: always`).

### health checks
**IMPLEMENTED.** `/actuator/health` checks PostgreSQL and Redis.
`/api/v1/internal/health` is a custom lightweight check (no external probes).

---

## Deployment & Environment

### virtual threads
**IMPLEMENTED.** Enabled in `application.yml:10` via
`spring.threads.virtual.enabled: true`. Java 21 feature. Used for I/O-bound
concurrency (DB queries, Redis, HTTP to providers).

### profiles
**IMPLEMENTED.** `local` (Docker Compose) and `test` (Testcontainers).
Profile-specific config in `application-local.yml` (not confirmed in working tree)
and `application-test.yml` (modified in current session). Shared defaults in
`application.yml`.

### Docker Compose
**IMPLEMENTED.** `docker-compose.yml` defines: `postgres` (port 15432),
`redis` (port 16379), `kafka` (port 19092, KRaft mode), `kafka-init`
(topic creation). Modified in current session (see `git diff`).

### Testcontainers
**IMPLEMENTED.** `postgresql` and `kafka` Testcontainers modules (pom.xml:137-166).
Tests use `@ServiceConnection` for dynamic port injection.
`TestcontainersSupport.java` centralizes configuration. No hardcoded host ports.

---

## Not Implemented / Deferred

### refund
**NOT IMPLEMENTED.** No refund endpoint or logic exists. `PaymentStatus.REFUNDED`
exists in the enum (line 55) but is not reachable from any code path.

### chargeback
**NOT IMPLEMENTED.** No chargeback handling. No `CHARGEBACK` state or webhook.

### ledger (double-entry)
**DEFERRED.** `journalEntryId` field and column exist, but no journal tables.
See `docs/architecture.md:340-363` for schema design.

### circuit breaker
**NOT IMPLEMENTED.** No circuit breaker on provider HTTP calls.
`docs/architecture.md:549` mentions "circuit-break provider calls" as a
point-of-failure mitigation, but no code exists.

### rate limiting
**NOT IMPLEMENTED.** No rate limiting or throttling.

### authentication
**NOT IMPLEMENTED.** No Spring Security. All endpoints are open.
`docs/architecture.md:577` mentions this is a production-readiness gap.

### webhook
**NOT IMPLEMENTED.** `callbackUrl` is not in `CreatePaymentRequest`.
No endpoint for provider callbacks. See `docs/payment-api.md:250`.

### @EnableScheduling
**NOT IMPLEMENTED.** Missing from `PaymentGatewaySettlementApplication.java`.
`@Scheduled` methods on `RecoveryScheduler` and `OutboxPublisher` won't fire
in a running application. See `docs/stage-5-status.md:103-105`.

### byte-exact idempotency replay
**NOT IMPLEMENTED.** The controller rebuilds responses from the `Payment` domain
object instead of returning cached `response_body` JSON. See
`docs/stage-5-status.md:79`.

---

## Verification Notes

- V1 and V2 Flyway migrations were referenced in documentation but NOT read
  during inspection. Their existence is assumed based on doc references.
- `application-local.yml` was NOT found in the working tree during inspection
  (only `application.yml`, `application-test.yml` were modified).
- `PaymentGatewaySettlementApplication.java` was NOT read during inspection.
  `@EnableScheduling` status is inferred from `docs/stage-5-status.md`.
- `SimulatedPaymentProcessorFactory` is mentioned in `docs/provider-simulator.md:65`
  but NOT found in the source tree glob. May not exist as a separate class.
- `InternalHealthControllerTest.java` and `RedisIntegrationTest.java` exist
  but were NOT read during inspection.