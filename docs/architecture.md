# Payment Gateway & Settlement Core Engine — Architecture & Design

> Status: **Stage 1 — Architecture & Design only. No implementation code.**

## 0. Repository findings

`/home/gt/git/GT/ERP_paymentGateway` is **empty and not a git repository**. No existing source, dependencies, or configuration. This is a **new project** (greenfield). A `docs/` directory was created for this document.

---

## 1. System boundary

### 1.1 External ERP (out of scope, owned externally)

* Customer records (`customer_ref`, name, contact).
* Bill/invoice generation (`billRef`, line items, amount, outstanding balance).
* Customer-facing UI (bill display, outstanding dues, payment-method selection).
* Its own **invoice database** — the gateway must **never** join or modify ERP tables directly.

### 1.2 Our Payment Gateway (in scope)

* Payment initiation API.
* Payment-method processing (simulated providers).
* Payment state machine.
* Double-entry financial ledger (immutable).
* Idempotency (Redis cache + PostgreSQL authority).
* Kafka events + transactional outbox.
* Settlement & reconciliation engine.
* Observability (MDC, Micrometer, structured logs).

A **lightweight mock ERP** and **mock payment providers** are allowed as test fixtures to validate contracts — not as production modules.

---

## 2. High-level architecture

```
         +-----------------+      REST callback (optional)      +-----------------+
         |  External ERP   | ---------------------------------> |  Mock ERP (test)|
         |  (owner of       |                                    |  (in-process
         |   invoices)      |                                    |   WireMock)     |
         +-----------------+      GET /payments/{id}            +-----------------+
                  |   1. POST /api/v1/payments/charge
                  |        Idempotency-Key, billRef, amount
                  v
         +---------------------------------------------------+
         |  PAYMENT GATEWAY & SETTLEMENT CORE ENGINE         |
         |                                                   |
         |  API (Spring MVC + virtual threads)              |
         |  Payment Orchestration  PaymentProcessor SPI       |
         |  Idempotency  Ledger  Outbox/Kafka  Reconciliation|
         |  Observability (MDC + Micrometer)                |
         +---------------------------------------------------+
             |            |            |             |
             v            v            v             v
        PostgreSQL      Redis       Kafka        Simulated
        (source of truth)(cache)     (events)     Provider HTTP
             (migrations via Flyway)
```

### Component responsibilities & data ownership

| Component | Owns | Never owns |
|---|---|---|
| **API layer** | HTTP request/response, idempotency cache lookups | financial truth, event offsets |
| **Payment Orchestration** | `Payment` aggregate (status, provider_ref) | invoice balances (ERP) |
| **PaymentProcessor SPI** | provider HTTP request/response shapes | card raw digits, CVV |
| **Idempotency** | `IDEMPOTENCY` table (key, hash, terminal response) | balance computation |
| **Ledger** | `JOURNAL_ENTRY`, `JOURNAL_LINE`, `LEDGER_ACCOUNT` (immutable) | mutable balance as source of truth |
| **Outbox** | `OUTBOX` rows (atomic with payment commit) | Kafka broker state |
| **Kafka consumer** | `PROCESSED_EVENT` dedup | payment status as source of truth |
| **Reconciliation** | `RECONCILIATION_RUN`, `RECONCILIATION_RESULT` | ERP invoice balance |

---

## 3. Package structure

```
com.paymentgateway.settlement
├── PaymentGatewaySettlementApplication.java
│
├── api                            # REST boundaries
│   └── controller / dto / mapper
│
├── domain                         # Pure domain (no framework deps)
│   ├── payment                    # aggregate, state machine, value objects
│   ├── ledger                     # journal, accounts
│   ├── idempotency                # IdempotencyKey value object
│   ├── money                      # Money (minor units), Currency
│   ├── event                      # domain events (internal)
│   └── exception                  # ApiError, domain exceptions
│
├── application                    # Use cases & orchestration
│   ├── service                    # ChargeService, LedgerService, ReconciliationJob
│   └── port                       # SPIs (PaymentProcessor, IdempotencyService)
│
├── infrastructure                 # Adapters (infra → port wiring)
│   ├── persistence                # JPA entities, repositories, Flyway
│   ├── messaging                  # Kafka producer/consumer, OutboxProcessor
│   ├── external                   # PaymentProviderClient, ErpClient, mocks
│   ├── idempotency                # RedisIdempotencyCache
│   ├── reconciliation             # SettlementFileGenerator, match engine
│   ├── observability              # MdcFilter, metrics, logging
│   └── config                     # Bean wiring, KafkaProducer config
│
└── test                           # Testcontainers, fixtures, contracts
```

**Why this layout**: Clean Hexagonal Architecture — `domain` has zero framework imports, making the state machine and `Money` math trivially unit-testable. `application` owns `@Transactional`. `infrastructure` holds the framework glue. Future microservice extraction (e.g. splitting ledger) only requires relocating packages; no cross-cutting changes.

---

## 4. End-to-end payment flow

### 4.1 Happy path (full payment)

1. **ERP** already generated bill `INV-2024-00743`, amount ₹1,250.00; outstanding balance is shown in ERP UI.
2. **User** selects UPI in ERP UI. ERP calls our gateway:
   `POST /api/v1/payments/charge` with `Idempotency-Key: ik-abc`, payload as in §5.1.
3. **Gateway** validates merchant, currency, method; checks Redis then PostgreSQL idempotency record (`SELECT FOR UPDATE` on `(merchant_id, idempotency_key)`).
4. **Gateway** inserts `INIT` payment + DEBIT journaling (`CASH_UPI` debit, `SETTLEMENT_LIABILITY` credit) + idempotency row — **single transaction, committed**.
5. **Gateway** invokes simulated UPI provider HTTP `/v1/payments` → returns `200 {status: SUCCESS, ref: upi_txn_99}`.
6. **Gateway** (new TX): updates payment to `SUCCEEDED`, inserts CREDIT journal (`REVENUE` credit, `FEES_EXPENSE` debit), inserts `OUTBOX` row — committed.
7. **Outbox processor** polls PENDING rows, sends Kafka record `payment.status` (key=`payment_id`), marks row `PUBLISHED`.
8. **ERP** consumes Kafka `payment.status` (or polls `GET /payments/{id}`), updates its invoice to PAID / NO_DUES.

### 4.2 Partial payment flow

Identical up to step 7, but `amount` < `bill.outstanding`. ERP subtracts the confirmed payment amount from its **own** outstanding balance and displays the **remaining** amount. No gateway-side split-billing — ERP owns the notion of "outstanding".

### 4.3 Timeout / unknown outcome

Steps 3–4 complete; at step 5 provider returns **timeout**. Gateway sets payment `UNKNOWN`, inserts a `REQUIRES_RECONCILIATION` outbox event, and **returns 202 ACCEPTED** `{status: UNKNOWN, paymentId: ...}`. ERP displays "payment status pending — check back later". A background job polls the provider's status endpoint (simulated) and resolves `UNKNOWN` → `SUCCEEDED|FAILED`. Idempotency key remains safe: a retry with the **same** key reuses the `UNKNOWN` payment and is **not** re-submitted to the provider.

---

## 5. API contracts

### 5.1 `POST /api/v1/payments/charge`

```jsonc
// REQUEST
// Header: Idempotency-Key: ik-abc-123   (merchant-scoped, required)
// Header: X-Correlation-Id: corr-001    (optional, generated if absent)
{
  "merchantId": "m_5f2e",              // required, must be a registered merchant
  "customerRef": "c_8812",             // required, opaque token
  "billRef": "INV-2024-00743",         // required, ERP's identifier
  "amount": "1250.00",                 // required, BigDecimal-as-string (2 decimals for INR)
  "currency": "INR",                   // required, ISO-4217
  "paymentMethod": "UPI",              // required, UPI|CREDIT_CARD|DEBIT_CARD|NET_BANKING
  "paymentToken": "upi://pay/xyz@kok|...",  // required, tokenized/simulated
  "callbackUrl": "https://erp.local/webhook/pay" // optional
}

// RESPONSE (201 Created, new) OR (200 OK, idempotent replay)
{
  "paymentId": "p_7e3a9b",
  "merchantId": "m_5f2e",
  "billRef": "INV-2024-00743",
  "amount": "1250.00",
  "currency": "INR",
  "paymentMethod": "UPI",
  "status": "SUCCEEDED",               // PENDING for timeout/unknown
  "providerRef": "upi_txn_99",
  "processedAt": "2026-09-13T17:22:10Z",
  "links": {
    "self": "/api/v1/payments/p_7e3a9b",
    "statusHistory": "/api/v1/payments/p_7e3a9b/events"
  }
}
```

**Status codes**:

| Code | Meaning |
|---|---|
| 201 | New payment accepted & completed synchronously |
| 200 | Idempo-key replay of an already-completed payment |
| 202 | Accepted, processing asynchronously (timeout/unknown) |
| 400 | JSON validation failed (missing/invalid field) |
| 409 | Idempotency key reused **with a different payload and not terminal** |
| 422 | Merchant unknown / currency unsupported |
| 429 | Too many concurrent idempotency checks for this key (rare) |

**Idempotency rules**:
* Same key + same payload hash → return stored result (terminal or `202 UNKNOWN`).
* Same key + **different** payload hash + non-terminal in-flight → `409` conflict.
* Same key + different payload + **terminal** stored → return stored result anyway (payload ignored — the key locked the outcome).
* Redis TTL 5 min, PostgreSQL row authoritative.

### 5.2 `GET /api/v1/payments/{paymentId}`

```jsonc
// 200
{
  "paymentId": "p_7e3a9b",
  "merchantId": "m_5f2e",
  "billRef": "INV-2024-00743",
  "customerRef": "c_8812",
  "amount": "1250.00",
  "currency": "INR",
  "paymentMethod": "UPI",
  "status": "SUCCEEDED",
  "providerRef": "upi_txn_99",
  "failureReason": null,
  "requestedAt": "2026-09-13T17:20:00Z",
  "processedAt": "2026-09-13T17:22:10Z"
}
// 404 if not found
```

### 5.3 `POST /api/v1/payments/status/bulk` (N+1-safe)

```jsonc
// REQUEST
{ "paymentIds": ["p_7e3a9b", "p_4c1x", "p_88zz"] }   // max 500

// RESPONSE — single `WHERE payment_id = ANY(?)` query
[
  {"paymentId":"p_7e3a9b","status":"SUCCEEDED","amount":125000,"currency":"INR"},
  {"paymentId":"p_4c1x","status":"UNKNOWN","amount":0,"currency":"INR"},
  {"paymentId":"p_88zz","status":"FAILED"}
]
```

### 5.4 ERP payment confirmation mechanism

| Option | Trade-off |
|---|---|
| **Polling** `GET /payments/{id}` | Simple, reliable, but latency + load on gateway. |
| **REST callback** `callbackUrl` | Push-based, but gateway owns retry/delivery; ERP must be idempotent & online. |
| **Kafka consume** `payment.status` | Best for high volume; ordering by partition; replayable; **requires ERP to be a Kafka consumer**. |

**Recommendation for this educational project**: **Kafka is authoritative** for the integration event (matches §9). The ERP mock will be a **Kafka consumer** in integration tests. A **polling fallback** (§5.2) is also provided so ERPs that can't consume Kafka still work. REST callback is **supported but not required** — implemented via the same outbox so delivery is idempotent; if ERP is down the callback retries against the durable event.

**Data ownership reminder**: the callback/event is **informational**. The ERP applies it to its own invoice row and is itself responsible for exactly-once invoice update via its own idempotency.

---

## 6. Payment methods & provider abstraction

### 6.1 Abstraction

```java
public sealed interface PaymentProcessor
    permits UpiProcessor, CreditCardProcessor, DebitCardProcessor, NetBankingProcessor {
    ProviderResult charge(String paymentToken, Money amount);
}
```

`ChargeService` selects processor by `PaymentMethod` via a `Map<PaymentMethod, PaymentProcessor>` (Strategy + Factory). No `if/else` on method in orchestration.

**Security**: raw PAN/CVV **never** accepted. `paymentToken` is always a simulated token like `card_tok_abcdef` or a UPI URI. Processors log only an hashed last-4 of the token.

### 6.2 Simulated provider behavior matrix

| Scenario | Provider response | Gateway status |
|---|---|---|
| Success | `200 {status:SUCCESS, ref}` | `SUCCEEDED` + ledger posted |
| Decline (card decline / insufficient) | `200 {status:DECLINED, reason}` | `FAILED` |
| Timeout | no response > N ms | `UNKNOWN` (202 issued) |
| HTTP 500 / connection error | `5xx` or `ConnectException` | `FAILED` (retry-safe — **only safe because no money moved**) |
| Unknown outcome | `200 {status:"unknown"}` | `UNKNOWN` |

**Why timeout ≠ failure**: The external provider may have debited the customer but timed out before responding. Treating it as failed would **double-charge** on retry. `UNKNOWN` persists the payment in a non-terminal state awaiting reconciliation/polling. **[POINT OF FAILURE]** — symptom: customer sees "pending" indefinitely; cause: network flap at provider edge; recovery: provider-status poll resolves it.

---

## 7. Payment state machine

```
CREATED ─reserve(ledger)──▶ AUTHORIZED ─provider.charge──▶
                                            │ SUCCESS  → SUCCEEDED ─refund──▶ REFUNDED
                                            │ DECLINED → FAILED
                                            │ TIMEOUT  → UNKNOWN ─poll──▶ SUCCEEDED | FAILED
                                            │ ERROR    → FAILED
AUTHORIZED ─cancel──▶ CANCELLED        # customer-initiated cancel before provider hit
SUCCEEDED ─refund──▶ REFUNDED
FAILED ─(no outgoing)──▶ FAILED          # terminal
UNKNOWN ─poll/resolve──▶ SUCCEEDED | FAILED
```

Transitions enforced by exhaustive `switch` in `PaymentStateEngine`. **No backwards transitions** (e.g. `SUCCEEDED` → `FAILED`). Authorization (hold) and capture are **not separated** in v1 — provider call is synchronous capture-style; separation is a noted future extension (§15).

---

## 8. PostgreSQL schema

All monetary values stored as `BIGINT` **minor units** (e.g. ₹1,250.00 → `125000`). `amount_minor` sign is always **positive** for debit-direction lines; account *type* implies direction.

```sql
-- MERCHANT
merchant_id UUID PRIMARY KEY,
name        TEXT NOT NULL;

-- PAYMENT
payment_id        UUID PRIMARY KEY,
merchant_id       UUID NOT NULL REFERENCES merchant(merchant_id),
bill_ref          VARCHAR(255) NOT NULL,
customer_ref      VARCHAR(255),
amount_minor      BIGINT NOT NULL CHECK (amount_minor > 0),
currency          CHAR(3) NOT NULL,
payment_method    VARCHAR(32) NOT NULL,
status            VARCHAR(32) NOT NULL,
provider_ref      VARCHAR(255),
failure_reason    TEXT,
correlation_id    UUID NOT NULL,
created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_at        TIMESTAMPTZ NOT NULL,
version           BIGINT NOT NULL DEFAULT 0,           -- optimistic lock
CONSTRAINT uq_payment_provider_ref UNIQUE (provider_ref) WHERE provider_ref IS NOT NULL;

-- IDEMPOTENCY (authoritative)
merchant_id      UUID NOT NULL REFERENCES merchant(merchant_id),
idempotency_key  VARCHAR(255) NOT NULL,
request_hash     VARCHAR(64) NOT NULL,                 -- SHA-256 of payload
payment_id       UUID NOT NULL REFERENCES payment(payment_id),
response_json    JSONB NOT NULL,                       -- cached terminal response
is_terminal      BOOLEAN NOT NULL,
created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
expires_at       TIMESTAMPTZ NOT NULL,
PRIMARY KEY (merchant_id, idempotency_key);

-- LEDGER_ACCOUNT
account_id UUID PRIMARY KEY,
code       VARCHAR(64) UNIQUE NOT NULL,
name       TEXT NOT NULL,
type       VARCHAR(32) NOT NULL CHECK (type IN ('ASSET','LIABILITY','REVENUE','EXPENSE')),
currency   CHAR(3) NOT NULL;

-- JOURNAL_ENTRY (immutable)
journal_entry_id UUID PRIMARY KEY,
payment_id       UUID REFERENCES payment(payment_id),  -- nullable for fees
description      TEXT NOT NULL,
currency         CHAR(3) NOT NULL,
created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
posted           BOOLEAN NOT NULL DEFAULT FALSE,
version          BIGINT NOT NULL DEFAULT 0;

-- JOURNAL_LINE (immutable)
journal_line_id  UUID PRIMARY KEY,
journal_entry_id UUID NOT NULL REFERENCES journal_entry(journal_entry_id),
account_id       UUID NOT NULL REFERENCES ledger_account(account_id),
amount_minor     BIGINT NOT NULL,                      -- signed: debit +, credit -
currency         CHAR(3) NOT NULL,
payment_id       UUID REFERENCES payment(payment_id),
created_at       TIMESTAMPTZ NOT NULL DEFAULT now();

-- OUTBOX
outbox_id        UUID PRIMARY KEY,
aggregate_type   VARCHAR(64) NOT NULL,
aggregate_id     UUID NOT NULL,
event_type       VARCHAR(64) NOT NULL,
event_version    VARCHAR(8) NOT NULL DEFAULT 'v1',
event_id         UUID NOT NULL,                        -- consumer dedup key
correlation_id   UUID NOT NULL,
payload         JSONB NOT NULL,
status          VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING/PUBLISHED/FAILED
retry_count      INTEGER NOT NULL DEFAULT 0,
next_retry_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
CONSTRAINT uq_outbox_event UNIQUE (event_id);

-- PROCESSED_EVENT (consumer dedup)
event_id         UUID PRIMARY KEY,
event_type       VARCHAR(64),
processed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
payment_id       UUID;

-- RECONCILIATION
recon_run_id     UUID PRIMARY KEY,
settlement_file_id UUID NOT NULL,
started_at       TIMESTAMPTZ NOT NULL,
completed_at     TIMESTAMPTZ,
status           VARCHAR(32) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
summary          JSONB;

recon_result_id  UUID PRIMARY KEY,
recon_run_id     UUID NOT NULL REFERENCES recon_run(recon_run_id),
payment_id       UUID,
outcome          VARCHAR(32) NOT NULL,                 -- MATCHED/MISMATCH/MISSING_BANK/etc
bank_reference   VARCHAR(255),
detail           TEXT,
created_at       TIMESTAMPTZ NOT NULL DEFAULT now();
```

### Constraints enforcing financial correctness

* `JOURNAL_LINE.amount_minor` signed; application asserts per-entry sum = 0 inside the `@Transactional` that inserts the entry (a Postgres trigger could also fail the insert, but app-level check keeps tests clearer).
* `IDEMPOTENCY` PK `(merchant_id, idempotency_key)` — duplicate inserts raise `PSQLException` caught by `ChargeService` and translated to cached-result read.
* `OUTBOX` + `PAYMENT` inserted in **same DB transaction** → atomic.
* All `@Version` fields on `Payment`, `JournalEntry` for optimistic locking on status updates.

### Concurrency handling

* Isolation: `READ_COMMITTED` (Postgres default). `SELECT ... FOR UPDATE` on the payment row when transitioning status & on idempotency row during insert.
* Double-spend: idempotency key + `SUCCESS` immutability. Two concurrent charges for the same key serialize on the PG unique PK; the second gets `PSQLException` → reads the stored terminal result.
* Deadlocks: consistent lock order idempotency → payment → journal → outbox; any `PSQLException` with SQL state `40P01` is retried ≤3 times with jittered backoff.

---

## 9. Transaction & consistency design

| Operation | TX scope | Row locks | Eventual? |
|---|---|---|---|
| Idempotency reservation | TX1 | `SELECT FOR UPDATE` idempotency row | No |
| Journal debit (reserve) | TX1 | — | No |
| Payment status → terminal | TX2 | `FOR UPDATE` payment row + `@Version` check | No |
| Journal credit (post) | TX2 | — | No |
| Outbox insert | TX2 | — | No |
| Kafka send | Outbox processor (async) | — | Yes (at least once) |

### Outbox durability

* Outbox row **written in TX2** that commits the terminal payment.
* Processor polls `status='PENDING' ORDER BY next_retry_at`, marks `PUBLISHED`, sends Kafka with `acks=all`.
* If Kafka is unreachable → row stays `PENDING`, `retry_count++`, `next_retry_at` back-off (1s → 32s jittered).
* Processor is `@Scheduled(fixedDelay=500ms)` + **restart-on-boot** to drain after crash.

### Why Redis is not financial truth

Redis is an **L1 cache** for idempotency: a cache miss simply falls through to PostgreSQL, which is authoritative. Redis can be flushed/cold/restarted without affecting correctness — only performance. **[POINT OF FAILURE]** — cache stampede mitigated by per-key single-flight via PG unique constraint, not Redis locks.

### Exactly-once limits

* **Payment processing is exactly-once** for the gateway (idempotency key + unique constraints).
* **Event delivery is at-least-once** — consumer dedup via `PROCESSED_EVENT` primary key, not idempotent producers. We explicitly **do not claim exactly-once financial accounting** across the gateway↔ERP boundary because the ERP owns its invoice state.

---

## 10. Kafka event contracts

Topic: **`payment.status`** (key=`payment_id`, 6 partitions, 7-day retention, `cleanup.policy=delete`).

### `payment.succeeded.v1`

```jsonc
{
  "eventId": "evt_3f...",
  "eventType": "payment.succeeded",
  "eventVersion": "v1",
  "occurredOn": "2026-09-13T17:22:10Z",
  "correlationId": "corr-001",
  "paymentId": "p_7e3a9b",
  "merchantId": "m_5f2e",
  "billRef": "INV-2024-00743",
  "customerRef": "c_8812",
  "amount": 125000,
  "currency": "INR",
  "paymentMethod": "UPI",
  "providerRef": "upi_txn_99",
  "status": "SUCCEEDED",
  "statusAt": "2026-09-13T17:22:10Z"
}
```

### Other events

* `payment.failed.v1` — `failureReason` field added.
* `payment.requires_reconciliation.v1` — emitted for `UNKNOWN` state; `reason: TIMEOUT|UNKNOWN_OUTCOME`.
* `settlement.completed.v1` — published by reconciliation job; payload: `{settlementFileId, totalRecords, matched, discrepancies[]}`.
* `reconciliation.discrepancy.v1` — `outcome` ∈ `MISSING_BANK| BANK_SUCCESS_INTERNAL_FAILURE|MISMATCH|DUPLICATE_BANK_REF|UNKNOWN_REF`.

### Strategy

* **Partitioning**: by `payment_id` UUID hash → payments for same bill spread across partitions (avoid hot-spot). ERP consumers in same group rebalance on restart.
* **Retry + DLQ**: 5 retries, exponential (1s→32s jittered). After exhausting, message routed to `payment.status.DLQ`. **[POINT OF FAILURE]** — DLQ must be monitored by alerting, not silently discarded.
* **Consumer offset**: committed **after** `PROCESSED_EVENT` insert succeeds (manual ack / `@KafkaListener` + `AckMode.MANUAL`).
* **Compensating for lost events**: ERP polls `GET /payments` on boot for any payments in `PENDING/UNKNOWN` state within the last N hours and reconciles.

---

## 11. Settlement & reconciliation

### 11.1 Settlement file (simulated)

CSV, generated by `SettlementFileGenerator` (a scheduled job that produces a batch every few seconds for the test run):

```csv
settlement_date,provider_ref,amount_minor,currency,customer_ref,bill_ref
2026-09-13,upi_txn_99,125000,INR,c_8812,INV-2024-00743
```

### 11.2 Matching engine (`ReconciliationJob`)

1. Load settlement file → staging.
2. `JOIN payments p ON p.provider_ref = file.provider_ref` (single SQL with `ON CONFLICT` upserts per-file idempotency).
3. Classify each row:

| Outcome | Condition | Action |
|---|---|---|
| `MATCHED` | both SUCCESS, amounts equal | no-op |
| `MISMATCH` | amounts differ | emit `reconciliation.discrepancy.v1` + alert |
| `MISSING_BANK` | gateway SUCCESS but not in file | emit discrepancy; **do not auto-refund** |
| `BANK_SUCCESS_INTERNAL_FAILURE` | file SUCCESS but gateway FAILED/UNKNOWN | transition payment → SUCCEEDED (bank authoritative for settlement), emit `payment.succeeded.v1` if was UNKNOWN |
| `DUPLICATE_BANK_REF` | file has duplicate `provider_ref` | reject duplicate row, emit discrepancy |
| `UNKNOWN_REF` | file `provider_ref` not in gateway | log; could be a payment from a different gateway — no auto-create |

### 11.3 Idempotency

Runs keyed on `settlement_file_id` PK on `recon_run`. A rerun only re-processes rows whose hash differs from a previous result → idempotent.

### 11.4 Why no auto-retry on UNKNOWN

Auto-charging a customer whose outcome is `UNKNOWN` can double-bill when the original provider-side debit succeeded but a timeout occurred. Reconciliation resolves `BANK_SUCCESS_INTERNAL_FAILURE` → flip gateway to SUCCEEDED instead. The customer sees the charge once; customer-service handles refunds for genuine duplicates.

---

## 12. Observability

### 12.1 Propagation (MDC)

Every log contains: `correlationId`, `requestId`, `merchantId`, `paymentId`, `providerRef` (post-provider), and on Kafka side `kafkaTopic`, `kafkaPartition`, `kafkaOffset`, `consumerGroupId`.

### 12.2 Metrics (Prometheus via `/actuator/prometheus`)

* `payment_charge_requests_total{status, paymentMethod, outcome}`
* `payment_state_duration_seconds{outcome}` (Timer)
* `idempotency_conflicts_total{reason}` — KEY_REUSED_DIFFERENT_PAYLOAD, IN_FLIGHT
* `provider_errors_total{paymentMethod, errorType}` — TIMEOUT, HTTP_5XX, CONNECT
* `db_lock_wait_seconds{table}` — from `pg_stat_activity` poll (scheduled job)
* `outbox_backlog_count` (Gauge)
* `outbox_publish_failures_total`
* `kafka_consumer_lag{topic, partition}`
* `dlq_messages_total{topic}`
* `reconciliation_discrepancies_total{outcome}`
* `reconciliation_runs_total{status}`

### 12.3 Point-of-failure matrix

| Failure | Symptom | Source of truth | Recovery |
|---|---|---|---|
| Postgres down | 503/500, locks held on retry | — | Retry with backoff; circuit-break provider calls |
| Redis down | idempotency falls to PG; slight latency increase | PG idempotency table | Transparent — Redis is cache-only |
| Kafka down | payments succeed; outbox backlog grows | `outbox` table | Processor retries exponentially; alert on backlog > 10k |
| Provider timeout | `UNKNOWN` returned; payment stuck | PG payment row | Background poll job resolves; reconciliation reconciles |
| Outbox double-publishing | duplicate ERP credit | ERP idempotency key | ERP dedups; `outbox` UNIQUE on `event_id` prevents re-insert |
| Same payment submitted twice | 409 or cached success | idempotency table | Idempotency key guarantees single accounting |
| ERP missed event | invoice not updated | Kafka topic (7 day) + ERP poll fallback | ERP poll on startup; Kafka replay if offsets retained |

---

## 13. Interview preparation

**Q1 — Idempotency key reuse with different payload?**
A: We hash the payload and store it. Same key + different hash + non-terminal → `409 CONFLICT`. If terminal → return the stored response regardless (the key already locked the outcome; rejecting would be a worse UX and the ledger is already balanced).

**Q2 — Why immutable ledger over `balance` column?**
A: (1) audit trail of every credit/debit; (2) reconciliation against external statements maps to specific journal entries, not an aggregate; (3) regulators (PCI-DSS, SOX) require traceability; (4) balances are cheap — they're a `SUM` query or a materialized view derived from journals.

**Q3 — Outbox pattern — why not publish Kafka in the same PG transaction?**
A: Kafka is a separate system — XA/saga adds huge complexity. Outbox makes the publish atomic with the payment commit at the *database* layer; the poll-based processor gives at-least-once delivery with idempotent consumer de-dedup.

**Q4 — Timeout ≠ failure argument?**
A: The provider might have debited the customer. Retrying a *different* idempotency key could double-charge. Returning `UNKNOWN` + polling + bank reconciliation is the financially-safe path.

**Q5 — How does the gateway prevent double-spend under concurrency?**
A: The idempotency key + `UNIQUE (merchant_id, idempotency_key)` on the `IDEMPOTENCY` table. Two threads inserting concurrently → one wins, the other gets `PSQLException` → reads existing terminal row and returns. No distributed lock needed; Postgres PK is the arbiter.

**Q6 — Kafka delivery guarantee claim?**
A: We deliver **at-least-once**; consumers dedup via `PROCESSED_EVENT (event_id)`. We do *not* claim end-to-end exactly-once because the ERP owns its own invoice table and the gateway can't atomically commit there too.

**Q7 — `SELECT FOR UPDATE` vs Redis distributed lock?**
A: Redis locks are probabilistic (expiry races, network partition). Postgres row locks under `READ_COMMITTED` with advisory/xact locks give true serializability guarantees needed for money. Redis is retained only for *fast path* idempotency cache.

**Q8 — Reconciliation handles `BANK_SUCCESS_INTERNAL_FAILURE` how?**
A: Bank is authoritative for settlement. We *transition* the gateway payment from `UNKNOWN/FAILED` → `SUCCEEDED` and emit `payment.succeeded.v1` so the ERP learns the truth. We never "re-authorize" — that risks double billing.

**Q9 — Scaling bottleneck?**
A: Outbox poll loop + single consumer group partition lag on `payment.status`. Remedy: partition by `payment_id` hash (6→N), multiple consumer instances. Also `db_lock_wait` metrics surface contention on hot `(merchant_id, idempotency_key)`.

**Q10 — Partial payment handled?**
A: Each `charge` is independent; the ERP owns outstanding balance. A second charge for the same `billRef` is treated as a distinct payment (new idempotency key). ERP sums its side.

---

## 14. Open design decisions (unresolved for Stage 1)

| # | Decision | Recommendation |
|---|---|---|
| 1 | Authorization-vs-capture separation? | **Deferred to Stage 4**; v1 is synchronous capture. |
| 2 | Currency other than INR? | Schema supports `CHAR(3)`; conversion rules in `Money` for scales 0/2/3. |
| 3 | Idempotency response retention before PG TTL cleanup? | 24 h in PG, 5 min in Redis. |
| 4 | Outbox partitioning / sharding? | Single table now; PostgreSQL partitioning by `created_at` month in Stage 4. |
| 5 | ERP webhook vs Kafka as default? | Kafka in tests; webhook code path implemented but **optional**. |

---

## 15. Recommended implementation order (stages)

1. **Stage 2 — Foundations**: Pom, Flyway migrations, `Money`/`Currency`, `PaymentProcessor` SPI, simulated provider mocks.
2. **Stage 3 — Payment orchestration**: `Payment` aggregate, state machine, `ChargeService`, idempotency (Redis+PG), `POST /charge`, `GET /payments`.
3. **Stage 4 — Ledger & outbox**: `LedgerService`, journaling, `OutboxProcessor`, Kafka producer.
4. **Stage 5 — Kafka contracts**: consumer side, `PROCESSED_EVENT` dedup, DLQ config.
5. **Stage 6 — Reconciliation**: file generator, match engine, discrepancy events.
6. **Stage 7 — Observability + tests**: MDC filter, Micrometer, Testcontainers integration tests (concurrency, idempotency, DLQ, restart recovery).

> **Stopping rule for Stage 1**: This document is complete. No Java classes, migrations, controllers, or business logic exist yet. Implementation starts after your GO on Stage 2.
