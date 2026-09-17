# Stage 5: Transactional Outbox, Kafka, and ERP Contract

> Status: **Handoff — outbox/Kafka infrastructure and the ERP event contract are documented; replay wiring and integration verification remain pending.**

This document is the implementation contract for payment lifecycle publication. The ERP remains an external system. The gateway writes events to PostgreSQL and Kafka; it never writes to the ERP database or ERP frontend.

## 1. Payment-to-event mapping

The payment aggregate remains the source of truth. An event is appended only after the aggregate has accepted a supported transition.

| Payment operation | Supported transition | Event | Causation |
| --- | --- | --- | --- |
| Create payment | new aggregate in `CREATED` | `PaymentCreated.v1` | request correlation ID |
| Submit to provider | `CREATED -> PROCESSING` | `PaymentProcessingStarted.v1` | `PaymentCreated` event ID |
| Provider success | `PROCESSING -> SUCCEEDED` | `PaymentSucceeded.v1` | `PaymentProcessingStarted` event ID |
| Provider decline/failure | `PROCESSING -> FAILED` | `PaymentFailed.v1` | `PaymentProcessingStarted` event ID |
| Provider outcome unknown | `PROCESSING -> UNKNOWN` | `PaymentUnknown.v1` | `PaymentProcessingStarted` event ID |
| Recovery retry is scheduled | retry metadata is scheduled; status may remain `UNKNOWN`/`CREATED` | `PaymentRetryScheduled.v1` | latest payment event ID |
| Recovery re-enters processing | `UNKNOWN`/`CREATED`/`REQUIRES_RECONCILIATION -> PROCESSING` | `PaymentProcessingStarted.v1` | retry-scheduled event ID |
| Retry budget exhausted | retry metadata reaches its bound | `PaymentRetryExhausted.v1` | latest payment event ID |

`REQUIRES_RECONCILIATION` is accepted by the existing recovery state machine but is not produced by the current provider-result mapper. No event is invented for an unsupported transition. Invalid transitions throw `IllegalStateTransitionException` and do not append an event.

## 2. Transactional write flow

1. The ERP calls `POST /api/v1/payments` with `Idempotency-Key`.
2. The gateway reserves the idempotency key in PostgreSQL.
3. In the payment transaction, the gateway inserts the `payment` row and one `outbox` row for `PaymentCreated`.
4. The provider call is made after the creation transaction commits.
5. In a second transaction, the gateway locks the payment, transitions it, appends the processing/result outbox rows, flushes the payment, and finalizes the idempotency response.
6. If either transaction rolls back, its outbox rows roll back with it.
7. The background publisher later reads committed outbox rows. Kafka availability does not affect payment commit.

The controller and `ChargeService` pass the same generated payment ID through the idempotency reservation so the foreign key is valid and replay returns the original payment.

## 3. Outbox table

`V4__payment_outbox.sql` creates `outbox` with:

- `id` and unique `event_id` UUIDs;
- `aggregate_type`, `aggregate_id`, `event_type`, `event_version`;
- explicit JSONB `event_payload`;
- `event_key`, `event_order`, status, attempt count, availability/retry timestamps;
- creation/publication timestamps, bounded `last_error`, lock owner, and lease expiry;
- a foreign key to `payment(payment_id)`;
- a unique `(aggregate_id, event_order)` index;
- a partial due-event index on `(status, next_attempt_at, event_order, created_at, id)`.

`event_order` is allocated from `outbox_event_order_seq`. It is included in the event payload and is the per-payment ordering value. Sequence gaps are allowed; ordering is by the value, not by timestamp.

## 4. Event schema

All timestamps are UTC `Instant` values. The JSON envelope is versioned and stable:

```json
{
  "eventId": "UUID",
  "eventType": "PaymentSucceeded",
  "eventVersion": 1,
  "eventOrder": 42,
  "paymentId": "UUID",
  "merchantId": "merchant-id",
  "erpReference": "ERP invoice/bill reference",
  "amountMinor": 1250,
  "currency": "USD",
  "paymentStatus": "SUCCEEDED",
  "previousPaymentStatus": "PROCESSING",
  "providerReference": "safe-provider-reference",
  "failureCode": null,
  "occurredAt": "2026-09-15T00:00:00Z",
  "nextAttemptAt": null,
  "retryAttempt": null,
  "correlationId": "UUID",
  "causationId": "UUID",
  "reasonCode": "PROVIDER_SUCCESS"
}
```

The payload deliberately excludes payment tokens, raw credentials, PAN data, customer PII, and internal entity fields. `erpReference` is the existing `billRef`; the gateway does not create or own an ERP invoice.

Consumers must treat `eventId` as the idempotency key. Unknown event types or newer incompatible versions should be retained for inspection and must not be applied as a known status change. Additive fields are backward-compatible for a v1 consumer; changing the meaning of an existing field requires a new event version.

## 5. Kafka topics, keys, and serializers

Local topics are created by `docker compose up -d kafka kafka-init`:

- `payment.events`: six partitions, seven-day retention;
- `payment.events.DLQ`: one partition for poison events.

The stable Kafka key is the payment UUID string. All events for one payment therefore use one partition. The producer uses `StringSerializer` for keys and values; the ERP consumer factory uses `StringDeserializer` for both. The consumer group is `external-erp-payment-events` and auto-commit is disabled.

The application creates the topic beans, but `KafkaAdmin` is configured not to fail startup when the broker is unavailable. The payment path remains available and outbox rows remain pending until Kafka recovers.

## 6. Publisher and retry behavior

`OutboxPublisher` polls `next_attempt_at <= now()` using a native `FOR UPDATE SKIP LOCKED` query. The query excludes a later event while an earlier event for the same payment is still pending, which preserves per-payment order across workers. A row lease is recorded for observability and stale-lease recovery; the database row lock is the primary worker exclusion mechanism.

For each row:

1. Claim the row with a unique owner.
2. Deserialize the explicit event payload.
3. Send the payload to `payment.events` with the payment ID key.
4. Await Kafka acknowledgement within `app.outbox.send-timeout`.
5. Mark the row `PUBLISHED` only after acknowledgement.

A failed acknowledgement increments `attempt_count`, records a bounded error, clears the lease, and schedules exponential backoff capped by `app.outbox.retry-max-backoff`. At the configured maximum, the publisher attempts the same envelope on `payment.events.DLQ`. A successful DLQ acknowledgement marks the row `DEAD_LETTERED`; a DLQ failure leaves a terminal `FAILED` row for operator inspection. This is bounded and does not create an infinite retry loop.

Configuration is under `app.outbox`:

```yaml
enabled: true
polling-interval: 1s
batch-size: 50
max-attempts: 10
retry-base-backoff: 1s
retry-max-backoff: 30s
send-timeout: 10s
lease-duration: 30s
kafka:
  topic: payment.events
  dead-letter-topic: payment.events.DLQ
  consumer-group: external-erp-payment-events
```

Set `app.outbox.enabled=false` for a local development run without Kafka.

## 7. Delivery guarantees

The design provides at-least-once delivery:

- A crash after Kafka acknowledgement but before the `PUBLISHED` update can cause a duplicate.
- A crash before acknowledgement leaves the row pending and causes a retry.
- Kafka producer idempotence prevents duplicate records from one producer session, but it is not end-to-end exactly-once business processing.

ERP consumers must deduplicate by `eventId`, use the payment ID and ERP reference for correlation, and apply their own invoice business rules. Consumers should compare `eventOrder` and their current invoice state; an older event must not downgrade a newer state. `PaymentUnknown` maps to an ERP review-required state. `PaymentRetryExhausted` maps to an explicit payment-failed/review outcome. The ERP remains responsible for deciding whether an invoice is paid, open, or requires manual resolution.

## 8. Failure flows

### Kafka outage

```text
payment transaction commits -> outbox PENDING -> publisher send fails
-> attempt_count++ -> next_attempt_at moves forward -> payment remains usable
-> publisher retries later -> Kafka acknowledges -> outbox PUBLISHED
```

### Duplicate delivery

```text
Kafka ack succeeds -> process crashes before PUBLISHED update
-> row remains PENDING -> publisher retries
-> ERP sees the same eventId again -> ignores it
```

### Poison message

```text
payload cannot be processed -> retry attempts advance
-> maximum reached -> send to payment.events.DLQ
-> DLQ ack -> DEAD_LETTERED and alert/metric
```

### Out-of-order consumer

```text
ERP has eventOrder 8 -> receives eventOrder 6
-> retain/correlate the record, do not downgrade invoice state
```

## 9. Observability

The gateway emits logs and Micrometer counters for event creation, polling, successful publication, publication failure, retry scheduling, dead-lettering, Kafka connection failure, duplicate ERP processing, ERP processing failure, and ERP outcome application. Logs include event ID, payment ID, event type, and key but never payment tokens or raw credentials. Correlation and causation IDs remain in the event envelope.

## 10. ERP integration steps

1. Generate an `Idempotency-Key` for each payment command and reuse it for retries.
2. Send the existing `POST /api/v1/payments` request with `billRef` as the ERP invoice/reference.
3. Consume `payment.events` with group `external-erp-payment-events` and `read_committed` isolation.
4. Deserialize the JSON envelope and persist `eventId` before applying an invoice effect.
5. Correlate by `paymentId` and `erpReference`; use `correlationId` for traces and `causationId` for the preceding event.
6. On duplicates, return success without a second invoice update.
7. On `PaymentUnknown`, place the invoice in review-required state. On `PaymentRetryExhausted`, apply the ERP's explicit payment-failed/review rule.
8. Update invoice status only after the ERP's own business rules and reconciliation checks pass.

The gateway never updates ERP tables and does not expose a production ERP implementation.

## 11. Local verification

```bash
docker compose up -d postgres redis kafka kafka-init
mvn -Dtest=PaymentOutboxTransactionIntegrationTest test
mvn -Dtest=OutboxPublisherRetryIntegrationTest test
mvn -Dtest=KafkaOutboxIntegrationTest test
mvn clean verify
```

Testcontainers uses dynamically allocated PostgreSQL and Kafka ports. The old hard-coded Kafka integration path is replaced by a Testcontainers Kafka container; no host port such as `15432` is used by the Kafka test.

## 12. Stage 5 handoff

### Current state

The Stage 5 contract and infrastructure scaffold are documented and present:

- PostgreSQL outbox schema and entity/repository wiring;
- versioned payment event envelope and per-payment ordering;
- `OutboxPublisher`, Kafka topic configuration, retry/backoff, lease recovery, and DLQ behavior;
- local/Testcontainers integration-test fixtures for the outbox publisher and an in-test ERP event fixture; the production ERP remains external.

The charge/idempotency replay wiring is partially applied and is not yet a clean compile or integration baseline. The current worktree references `ReplayDuringReservationException` without a definition, and the controller/service `ChargeResult` call contract is inconsistent. Treat those changes as a handoff point, not as completed Stage 5 work.

### Pending before Stage 6

1. Define the replay-during-reservation exception and its HTTP mapping. Decide whether a concurrent replay returns the cached result, a retryable response, or a conflict, then keep the controller and service contract consistent.
2. Align `ChargeService.chargeWithOutcome`, `PaymentController.createPayment`, and `ChargeResult` signatures/constructors. The current controller passes an extra payment ID and the service references the missing exception type.
3. Verify the two transaction boundaries: creation plus outbox append, then provider result plus processing/result outbox events plus idempotency finalization. Confirm that a rollback removes all rows created in that transaction.
4. Verify replay state and restart recovery. Replay must continue to read the authoritative PostgreSQL idempotency/payment state after an application restart; add coverage for Redis loss and process recovery.
5. Add integration coverage for concurrent idempotency reservation, replay after commit, stale replay, Kafka redelivery, outbox publication, DLQ handling, and Redis loss/recovery.
6. Run `mvn clean verify` and the three Stage 5 integration tests listed below. Do not mark Stage 5 complete until the build and targeted tests pass.

### Next stage boundary

Stage 6 is reconciliation. Before starting it, carry forward the pending Stage 5 items above and preserve the outbox event envelope, `eventId` deduplication, payment ordering, and ERP ownership boundaries documented here. Reconciliation may consume gateway/provider records and produce reconciliation results, but it must not update ERP invoice tables directly.

Stage 6 entry criteria:

- the Stage 5 compile and targeted integration tests are green;
- replay and concurrent-reservation behavior has an agreed HTTP contract;
- outbox publication, ordering, retry, and DLQ behavior are covered by tests;
- the production ERP consumer is explicitly scoped as a Stage 6 dependency or left external.


