# Stage 5 — Transactional Outbox, Kafka, and ERP Contract

> Status as of **2026-09-15** (recovery session). This document records the
> actual inspected state of the repository *before* any fix in this session,
> plus the verification done during recovery.

## Objective

Stage 5 makes payment state changes durable and observable by writing a
**transactional outbox** row in the same database transaction as each payment
state transition, then publishing those rows to **Apache Kafka** so the external
ERP can consume them. Recovery of stuck payments must also emit outbox events.

End-to-end flow (see [architecture](architecture.md)):

```
Payment API  ->  Payment transaction (TX1/TX2)  ->  Outbox row  ->  Outbox publisher  ->  Kafka  ->  ERP consumer
```

## Current build status

**The working tree does NOT compile.** A previous incomplete Stage 5 session left
5 compiler errors in the main sources. Tests therefore cannot run until the
build is repaired.

| Severity | File:Line | Problem |
|----------|-----------|---------|
| ERROR | `ChargeService.java:172,181` | `ReplayDuringReservationException` is referenced but **not defined** anywhere. |
| ERROR | `ChargeService.java:220` | `new ChargeResult(updated, false)` — `ChargeResult` is a 3-arg record `(Payment, boolean, int)`. |
| ERROR | `ChargeService.java:233` | `charge()` calls `chargeWithOutcome(..., idempotencyKey, UUID.randomUUID())` (10 args) but the method takes 9. |
| ERROR | `PaymentController.java:137` | `chargeWithOutcome(..., idempotencyKey, paymentId)` (10 args) but the method takes 9. |

Recovery action taken in this session (see `stage-5-status` "Resolution"):
define the missing exception, align the `chargeWithOutcome` signature to accept
the controller-supplied `paymentId`, repair the `ChargeResult` arity, and remove
the controller's old **pre-reservation fast path** that is incompatible with the
Stage 5 transaction model (it would violate the `idempotency.payment_id`
foreign key — see *Known issues* below).

## Current implementation status

The Stage 5 design is implemented in code as a **two-phase payment
transaction** (`ChargeService.chargeWithOutcome`) with the outbox written inside
each phase. The structure is complete; only the compile breakage above blocks
verification.

### Completed components

| Component | Location | Status |
|-----------|----------|--------|
| Outbox table | `V4__payment_outbox.sql` | **COMPLETE** — table, sequence, indexes, FK to `payment(payment_id)`. |
| Outbox entity | `infrastructure/persistence/entity/OutboxEventEntity.java` | **COMPLETE** |
| Outbox repository | `infrastructure/persistence/repository/OutboxEventRepository.java` | **COMPLETE** — `findDue` (SKIP LOCKED + ordering), `claim`, `markPublished`, `markRetryOrFailed`, `markDeadLettered`, `nextEventOrder`. |
| Event creation | `application/service/OutboxEventService.java` | **COMPLETE** — versioned `PaymentLifecycleEvent` serialized to JSON; append in `ChargeService` TX1/TX2 and `PaymentRecoveryService`. |
| Event types | `domain/event/PaymentEventType.java`, `PaymentLifecycleEvent.java` | **COMPLETE** — 7 events, all version 1. |
| Publisher | `infrastructure/messaging/OutboxPublisher.java` | **COMPLETE** (implementation; auto-polling see *Limitations*). |
| Kafka config | `infrastructure/messaging/KafkaOutboxConfiguration.java` | **COMPLETE** |
| Publisher metrics | `OutboxMetrics.java` | **COMPLETE** |
| Outbox config | `config/OutboxProperties.java` | **COMPLETE** |
| Payment flow (ChargeService) | `application/service/ChargeService.java` | **COMPLETE** (Design 2: payment + outbox + idempotency reserve in TX1). |
| Payment recovery (events) | `application/service/PaymentRecoveryService.java`, `RecoveryScheduler.java` | **COMPLETE** (RetryScheduled / RetryExhausted events; recovery sweep). |
| Idempotent reply path | Controller `createPayment` | **COMPLETE** (via `ChargeResult.replayed()`). |

### Partially completed components

| Component | Status | Notes |
|-----------|--------|-------|
| `ReplayDuringReservationException` | **PARTIAL** (not yet defined) | Referenced by `ChargeService` TX1; created in this recovery session. |
| Controller idempotency contract | **PARTIAL** | The previous session added a `chargeWithOutcome`/`ChargeResult`/`paymentId` contract but left the old controller **pre-reservation fast path** in place. It is being reconciled (fast path removed; 23505→replay handling retained at the controller). |
| HTTP status for `UNKNOWN` (202) | **DEFERRED** | `ChargeResult.responseStatus` is computed (`statusCodeFor`) but the controller currently maps only `replayed ? 200 : 201`. Returning 202 Accepted for non-terminal outcomes is pending alignment (see *Limitations*). |
| `@Scheduled` polling | **PARTIAL** | `OutboxPublisher` and `RecoveryScheduler` are annotated `@Scheduled`, but `@EnableScheduling` is **not present** anywhere → scheduled methods do not auto-fire. Tests invoke publishers directly. |
| Application smoke test | **PARTIAL** (pre-existing) | `ApplicationContextTest` fails to load at Stage 4 baseline (see *Known issues*). |

### Missing / NOT IMPLEMENTED

| Component | Status | Notes |
|-----------|--------|-------|
| ERP **consumer** (Kafka `@KafkaListener`) | **NOT IMPLEMENTED** | Only a test fixture (`ErpPaymentEventFixture`) consumes events. No gateway-side consumer bean exists. The `erpPaymentEventConsumerFactory` bean is defined but no listener is wired. |
| Idempotency response **replay body** (byte-exact) | **NOT IMPLEMENTED** | `idempotency.response_body` is stored, but the controller rebuilds the replay response from the `payment` row rather than returning the cached JSON. |
| Outbox batch **transactional draining** | **PARTIAL** | `publishDueEvents` publishes rows one-by-one in a loop within a single `@Transactional`; a mid-batch failure rolls back status updates for that batch. Acceptable; noted. |
| Settlement / reconciliation / refunds / chargebacks / ledger | **OUT OF SCOPE** — deferred to later stages. |

## Known issues

1. **Controller pre-reservation was incompatible with the FK.** The committed
   Stage 4 controller reserved the idempotency key with a throwaway `UUID`
   *before* the payment existed, which violates `idempotency.payment_id
   REFERENCES payment(payment_id)` (FK 23503). Stage 4 was never covered by a
   controller happy-path test, so this was latent. Stage 5 moves the reservation
   **inside** the payment-creation transaction (after the payment row is
   persisted) so the FK is satisfied — this is the correct design and the reason
   the old fast path is removed.

2. **`ApplicationContextTest` is pre-broken at the Stage 4 baseline.** With
   `DataSourceAutoConfiguration`/`HibernateJpaAutoConfiguration` excluded (no JPA
   beans), `PostgresIdempotencyStore` cannot be created because
   `IdempotencyRepository` is not available. Verified at committed `HEAD` in a
   throwaway worktree (`NoSuchBeanDefinitionException: IdempotencyRepository`).
   Additionally, a **stale `pg_data` volume** on the local `docker-compose`
   postgres (port 15432) carries a Flyway checksum mismatch for `V3` from a
   prior run and must be reset for the Docker-backed smoke test to pass.

3. **No `@EnableScheduling`.** `@Scheduled` methods on `OutboxPublisher` and
   `RecoveryScheduler` will not execute in a running app until scheduling is
   enabled. This is a runtime gap, not a compile error.

## Environment

- Java 21 (compiler release 21), Spring Boot 3.3.3.
- Docker available (`pgw-postgres`, `pgw-kafka` from `docker-compose.yml` are
  running on host ports 15432 / 19092).
- Testcontainers 1.21.4 with `postgresql` + `kafka` modules; integration tests
  use **dynamic** ports (no hardcoded Testcontainers host ports).
- Local Maven wrapper: `./mvnw` (project uses `mvn` in this environment).

## Recommended next step

1. Apply the minimal compile fix described in *Current build status*.
2. Run `mvn test` and record exact results.
3. If the Stage 5 integration tests (Testcontainers) pass, the transactional
   outbox + Kafka publish path is verified end to end.
4. Leave the ERP consumer bean and `@EnableScheduling` as explicit **DEFERRED**
   items (do **not** add a speculative Kafka consumer here — it risks a broad
   rewrite of untested behavior).

## Recovery checklist results

### Transactional Outbox Checklist

| Item | Result | Evidence |
|------|--------|----------|
| outbox table | COMPLETE | `V4__payment_outbox.sql` |
| outbox entity | COMPLETE | `OutboxEventEntity` |
| repository | COMPLETE | `OutboxEventRepository` |
| event creation | COMPLETE | `OutboxEventService.append`, called from `ChargeService` TX1/TX2 & `PaymentRecoveryService` |
| event payload serialization | COMPLETE | `PaymentLifecycleEvent` → JSON via `ObjectMapper` |
| same-transaction persistence with payment changes | COMPLETE | `ChargeService` uses `TransactionTemplate`; payment + outbox written in the same TX |
| unpublished event query | COMPLETE | `OutboxEventRepository.findDue` (PENDING, SKIP LOCKED, deterministic order) |
| retry metadata | COMPLETE | `attempt_count`, `next_attempt_at`, `lock_owner`, `locked_until`, `last_error` |
| publisher | COMPLETE (impl) / PARTIAL (scheduling) | `OutboxPublisher.publishDueEvents`; `@Scheduled` present but `@EnableScheduling` absent — see Limitations |
| Kafka acknowledgement handling | COMPLETE | `kafkaTemplate.send(...).get(sendTimeout)` blocks for the acknowledgement |
| publication status update | COMPLETE | `markPublished`, `markRetryOrFailed`, `markDeadLettered` |
| concurrency protection for publisher workers | COMPLETE | `claim()` CAS on `lock_owner` + `FOR UPDATE SKIP LOCKED` + earliest-event-first ordering |
| stable Kafka message key | COMPLETE | `eventKey` = payment UUID string |
| graceful failure when Kafka unavailable | COMPLETE | payment transaction commits independently; publisher retries with backoff then DLQ then `FAILED` row |

### Kafka Checklist

| Item | Result | Evidence |
|------|--------|----------|
| Kafka dependency | COMPLETE | `org.springframework.kafka:spring-kafka` in `pom.xml` |
| Kafka configuration valid | COMPLETE | `KafkaOutboxConfiguration` |
| Docker Compose configuration valid | COMPLETE | `docker-compose.yml` (`kafka` + `kafka-init`) |
| topic names defined | COMPLETE | `payment.events` (6 partitions), `payment.events.DLQ` (1) |
| serializers configured | COMPLETE | `StringSerializer`/`StringDeserializer` |
| producer can publish | COMPLETE (impl) | `KafkaOutboxPublisher`; covered by `KafkaOutboxIntegrationTest` |
| behavior when Kafka unavailable | COMPLETE | payment commits; outbox stays PENDING; publisher retries then DLQ/FAILED |
| tests without hardcoded host ports | COMPLETE | `KafkaContainer`/`PostgreSQLContainer` use dynamic ports via `@ServiceConnection` |
| no second/kafka setup | COMPLETE | single `KafkaOutboxConfiguration` + single docker-compose `kafka` service |

## Limitations (do not overstate)

- **Not exactly-once.** Delivery is **at-least-once**: the publisher marks a row
  `PUBLISHED` only after the Kafka `send().get(...)` acknowledgement, but a crash
  after acknowledgement and before the DB update would re-publish on the next
  poll. Consumers must be idempotent.
- **Ordering** is guaranteed **per payment** (events for one payment share the
  same Kafka key → same partition → same order), **not globally** across payments.
- **Kafka availability is not required to take a payment.** A down Kafka cluster
  leaves outbox rows `PENDING`; payments are committed and retried by the
  publisher. This is by design.
- **Auto-scheduling is off** until `@EnableScheduling` is added (see *Known
  issues* #3).
