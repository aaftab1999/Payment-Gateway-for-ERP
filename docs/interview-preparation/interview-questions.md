# Interview Questions & Answers

> All questions are based on the actual repository implementation. "Strong answer"
> references real classes, methods, and line numbers where applicable.
> Implementation status is noted per topic.

---

## A. Project Overview and Architecture

### Question 1: Walk me through this payment gateway project. What does it do?

**What the interviewer is testing:** Understanding of the overall system, architecture, and value proposition.

**Strong answer:** "This is a Spring Boot 3.3.3 / Java 21 payment gateway monolith that
processes payments from an external ERP system. The ERP owns invoices and sends a
`POST /api/v1/payments` request with a bill reference and amount. The gateway creates a
payment record, submits it to a simulated payment processor, applies the result, and
publishes lifecycle events to Kafka via a transactional outbox. Key safety guarantees:
idempotency via a PostgreSQL unique constraint, the UNKNOWN state for ambiguous provider
outcomes, and two-phase transactions to avoid holding locks during provider I/O.

The architecture follows hexagonal principles: `domain/` (pure Java, zero framework
deps), `application/` (orchestration with `@Transactional`), `infrastructure/` (JPA,
Kafka, simulated provider)."

**Important technical details:**
- Package structure: `com.paymentgateway.settlement.{api, domain, application, infrastructure, config, observability}`
- `PaymentGatewaySettlementApplication.java` is the entry point
- PostgreSQL is the source of truth; Redis is cache-only
- Kafka topics: `payment.events` (6 partitions), `payment.events.DLQ` (1 partition)

**Likely follow-up:** "What's the difference between the gateway and the ERP?"
**Answer:** "The ERP owns invoices, customer records, and bill balances. The gateway
owns payment initiation, state machine, provider integration, idempotency, and event
publication. The gateway does NOT update ERP tables."

**Common weak answer to avoid:** "It processes payments" (without explaining the
domain boundaries, the gateway-vs-ERP split, or the safety guarantees).

---

### Question 2: How is the codebase organized? What architectural pattern does it follow?

**What the interviewer is testing:** Knowledge of clean architecture / hexagonal architecture,
package-by-feature vs package-by-layer.

**Strong answer:** "It follows Hexagonal (Ports and Adapters) architecture, layered
as: `domain/` → `application/` (ports) → `infrastructure/` (adapters). The `Payment`
aggregate in `domain/payment/` has zero Spring imports — no `@Entity`, no `@Transactional`.
`application.port.PaymentProcessor` is the port (interface); `SimulatedPaymentProcessor`
in `infrastructure/external/provider/` is the adapter. The `application.service.ChargeService`
orchestrates the workflow with explicit `TransactionTemplate` control."

**Important technical details:**
- `docs/architecture.md:124` states: "Clean Hexagonal Architecture — `domain` has
  zero framework imports"
- `Payment.java` has no `@Entity`, `@Service`, or `@Component` annotations
- `PaymentProcessor.java:39` is in the `application/port` package (the port)
- `SimulatedPaymentProcessor.java:49` is in `infrastructure/external/provider` (the adapter)

**Likely follow-up:** "Why use records in the domain?"
**Answer:** "For immutable value objects like `ProviderResult`, `PaymentLifecycleEvent`,
and `ChargeResult`. Records give us `equals()`, `hashCode()`, `toString()`, and
accessors for free, and they're final — no defensive copying needed."

**Common weak answer to avoid:** "It's MVC" (without mentioning clean/hexagonal
architecture and the domain being framework-free).

---

### Question 3: How does the ERP interact with the gateway?

**What the interviewer is testing:** Understanding of the external system boundary and
data ownership.

**Strong answer:** "The ERP calls `POST /api/v1/payments` with `Idempotency-Key`,
`billRef` (the ERP's invoice reference), amount, and a payment token. The gateway
processes the payment and either returns the result synchronously (HTTP 201/200) or
publishes events to the `payment.events` Kafka topic. The ERP can consume these events
or poll `GET /api/v1/payments/{id}`.

**Key boundaries:** The gateway NEVER owns or modifies ERP invoice data. `billRef`
is an opaque correlation reference. The ERP tracks outstanding balances; the gateway
only records that a payment was attempted against a bill reference."

**Important technical details:**
- `PaymentController.createPayment` (line 76) — the entry point
- `Payment.amount` is a one-time charge, not a partial payment tracker — the ERP owns "outstanding"
- Kafka consumer group: `external-erp-payment-events`
- `docs/architecture.md:249` — "Kafka consume `payment.events` — Preferred high-volume path"

**Likely follow-up:** "What happens if the ERP misses a Kafka event?"
**Answer:** "The ERP's consumer must persist `eventId` before applying effects. If it
crashes, it can replay from Kafka (7-day retention) or poll `GET /payments/{id}` for
payments in UNKNOWN/PENDING state on startup. The gateway never pushes to the ERP's
invoice tables."

**Common weak answer to avoid:** "The ERP polls the gateway" (without explaining
that events are also pushed via Kafka, and that the gateway never touches ERP tables).

---

## B. Payment Lifecycle and State Machine

### Question 4: What are the payment states, and how do they transition?

**What the interviewer is testing:** Understanding of the domain state machine and
its correctness guarantees.

**Strong answer:** "The states are defined in `PaymentStatus.java` (enum, 8 values).
The state machine is enforced by `PaymentStateEngine.transition()` (line 61-105) using
an exhaustive `switch`.

**Non-terminal states:** CREATED, PROCESSING, UNKNOWN, REQUIRES_RECONCILIATION
**Terminal states:** SUCCEEDED, FAILED, VOIDED, REFUNDED

**Valid transitions:**
- `CREATED → PROCESSING` (SUBMITTED_TO_PROVIDER)
- `PROCESSING → SUCCEEDED` (PROVIDER_SUCCESS)
- `PROCESSING → FAILED` (PROVIDER_DECLINED, PROVIDER_TECHNICAL_FAILURE)
- `PROCESSING → UNKNOWN` (PROVIDER_UNKNOWN_OUTCOME)
- `PROCESSING → REQUIRES_RECONCILIATION` (PROVIDER_TECHNICAL_FAILURE — but note: code
  maps TECHNICAL_FAILURE → FAILED, not REQUIRES_RECONCILIATION; this transition is
  allowed by the state engine but not produced by `applyProviderResult`)
- `UNKNOWN → SUCCEEDED|FAILED` (RECONCILIATION_MATCHED|FAILED)
- `UNKNOWN → PROCESSING` (RETRY_SUBMITTED — recovery retry)
- `REQUIRES_RECONCILIATION → SUCCEEDED|FAILED` (reconciliation)
- `REQUIRES_RECONCILIATION → PROCESSING` (RETRY_SUBMITTED — recovery retry)

**No backwards transitions from terminal states.** Any invalid transition throws
`IllegalStateTransitionException`."

**Important technical details:**
- `PaymentStatus.java:57-66` — `isTerminal()`, `isAwaitingResolution()` methods
- `PaymentStateEngine.java:83-96` — exhaustive switch
- `Payment.java:149-176` — `applyProviderResult()` maps `ProviderResult.Type` to `PaymentStatus`
- 20 unit tests in `PaymentStateEngineTest.java`

**Likely follow-up:** "Why can UNKNOWN go back to PROCESSING?"
**Answer:** "Because recovery can re-submit an uncertain payment with the same
provider idempotency key. The simulated provider (and real providers like Stripe)
deduplicate by that key, so the retry returns the original result — no double-charge."

**Common weak answer to avoid:** "It's a simple state machine" (without explaining
WHY certain transitions are allowed, particularly the non-terminal nature of UNKNOWN).

---

### Question 5: Why is UNKNOWN not a terminal state?

**What the interviewer is testing:** Understanding of the payment semantics — the
difference between "timeout" and "failure."

**Strong answer:** "A provider timeout does not mean the payment failed. The provider
may have debited the customer's account but timed out before sending a response. If
we marked it as FAILED, a retry with a *different* idempotency key would double-charge.
UNKNOWN keeps the payment in a non-terminal state — the recovery scheduler re-submits
with the SAME provider idempotency key, which the provider deduplicates. Only
after a confirmed outcome (via polling or reconciliation) does UNKNOWN transition
to SUCCEEDED or FAILED."

**Important technical details:**
- `Payment.java:149-158` — `applyProviderResult` checks `this.status.isTerminal()` first
- `ProviderResult.Type.UNKNOWN` → `PaymentStatus.UNKNOWN` (line 297 in Payment.java)
- `PaymentStatus.java:46` — "Provider returned an ambiguous result"
- `docs/architecture.md:281` — "Why timeout ≠ failure"

**Likely follow-up:** "How do you resolve UNKNOWN?"
**Answer:** "Two ways: (1) Recovery scheduler re-submits with the same provider
idempotency key (UNKNOWN → PROCESSING → terminal), or (2) a reconciliation job
(Stage 6) polls the provider's status endpoint and calls `Payment.resolveReconciliation()`."

**Common weak answer to avoid:** "It's a timeout" (without explaining why timeout ≠
failure and the double-charge risk).

---

### Question 6: How does the system prevent invalid state transitions?

**What the interviewer is testing:** Knowledge of domain-driven design invariants.

**Strong answer:** "Two layers:
1. **Domain layer:** `PaymentStateEngine.transition()` (line 61) validates every
   transition via an exhaustive `switch`. Invalid transitions throw
   `IllegalStateTransitionException`. This is unit-tested in
   `PaymentStateEngineTest` with 20 tests covering all valid and invalid paths.
2. **Persistence layer:** The payment entity's status column is a VARCHAR(32), and
   the `@Version` column prevents lost updates. The state machine is enforced
   programmatically — there's no database-level state constraint (deferred to a
   trigger in production)."

**Important technical details:**
- `PaymentStateEngine.java:98-102` — throws when `isValid == false`
- `Payment.java` methods like `markProcessing()` delegate to the engine
- `Payment.java:150` — terminal check before applying results

**Likely follow-up:** "Could a race condition cause an invalid transition?"
**Answer:** "No — TX2 acquires `SELECT FOR UPDATE` on the payment row before
transitioning. Only one thread can hold the lock. Even if two threads enter
TX2, the second waits, then re-reads the current state and finds it already
terminal — the idempotent apply is a no-op."

**Common weak answer to avoid:** "The database enforces it" (it doesn't — it's
enforced in the domain layer; the DB just stores the VARCHAR status).

---

## C. Java and Spring Boot

### Question 7: Why did you choose Java 21? What features do you use?

**What the interviewer is testing:** Knowledge of modern Java features and their
practical application.

**Strong answer:** "Three key Java 21 features:
1. **Virtual threads** — Enabled via `spring.threads.virtual.enabled=true` in
   `application.yml:10`. Blocking I/O (PostgreSQL, Redis, HTTP to providers) is
   multiplexed onto a small number of carrier threads. A payment gateway is I/O-bound,
   so this lets us handle thousands of concurrent requests without thread pool tuning.
2. **Records** — `ProviderResult`, `PaymentLifecycleEvent`, `ChargeResult` are records.
   They're immutable, concise, and have value semantics.
3. **Pattern matching for instanceof** — Used in `PaymentController.java:103`:
   `if (outcome instanceof IdempotencyOutcome.ReplayOutcome replay)`. This eliminates
   casting boilerplate.

The compiler is configured with `<release>21</release>` in `pom.xml:23`."

**Important technical details:**
- `pom.xml:23` — `<java.version>21</java.version>`
- `application.yml:10` — `spring.threads.virtual.enabled: true`
- `PaymentController.java:103` — pattern matching
- `ProviderResult.java:18` — `public record ProviderResult(...)`

**Likely follow-up:** "Have you hit any issues with virtual threads?"
**Answer:** "The main concern is `ThreadLocal` leakage — MDC needs explicit
propagation. Spring Boot 3.3 handles this via `TaskDecorator` on the executor.
For `TransactionTemplate` calls (which we use), virtual threads work transparently
because the JVM manages carrier thread assignment."

**Common weak answer to avoid:** "Records and pattern matching" (without mentioning
virtual threads and WHY this matters for a payment gateway's throughput).

---

### Question 8: How do you handle dependency injection and what pattern do you follow?

**What the interviewer is testing:** Spring DI knowledge and design principles.

**Strong answer:** "Constructor injection exclusively — every `@Service` and
`@RestController` has a single constructor with `final` fields. Spring Boot
3.3 doesn't need `@Autowired` on constructors when there's exactly one. This
makes dependencies explicit and testable.

The key architectural decision is **ports and adapters**:
`PaymentProcessor` is an interface in `application/port/` (the port).
`SimulatedPaymentProcessor` in `infrastructure/external/provider/` implements it
(the adapter). `ChargeService` depends on the port, not the adapter — making it
trivially mockable in tests with `@MockBean`."

**Important technical details:**
- `ChargeService.java:82-92` — constructor with 5 final dependencies
- `PaymentProcessor.java:39` — `public interface PaymentProcessor`
- `SimulatedPaymentProcessor.java:49` — `implements PaymentProcessor`
- `PaymentOutboxTransactionIntegrationTest.java:79` — `@MockBean private PaymentProcessor processor`

**Likely follow-up:** "Why not field injection?"
**Answer:** "Field injection breaks immutability, makes testing harder (can't
construct without reflection), and hides the dependency graph. Constructor
injection makes dependencies explicit in the class signature."

**Common weak answer to avoid:** "Spring injects dependencies with @Autowired"
(without mentioning constructor injection and the port/adapter pattern).

---

### Question 9: How is transaction management handled? Why two transactions?

**What the interviewer is testing:** Understanding of transaction boundaries and
distributed system design.

**Strong answer:** "We use `TransactionTemplate` for explicit two-phase control:
- **TX1 (creation):** Creates the payment in `CREATED`, appends the
  `PaymentCreated` outbox event, and reserves the idempotency key — all atomically.
  This is in `ChargeService.chargeWithOutcome()` at line 147.
- **Provider call:** Happens OUTSIDE both transactions. No DB lock is held during
  the potentially slow HTTP round-trip to the provider.
- **TX2 (apply result):** Locks the payment (`SELECT FOR UPDATE`), transitions
  the state, appends processing + result outbox events, and finalizes the
  idempotency record — all atomically. Line 210.

**Why not one transaction?** Holding `SELECT FOR UPDATE` during a provider HTTP
call would block other operations on the same payment row and risk transaction
timeouts. Splitting lets TX1 commit quickly (payment is durable before provider
call), then TX2 applies the result atomically."

**Important technical details:**
- `ChargeService.java:80` — `TransactionTemplate transactionTemplate`
- `ChargeService.java:147` — TX1 `transactionTemplate.execute(status -> {...})`
- `ChargeService.java:210` — TX2 `transactionTemplate.execute(status -> {...})`
- `ChargeService.java:211` — `applyProviderResult()` does `findAndLockByPaymentId()`
- `PaymentRepository.java:39` — `@Lock(PESSIMISTIC_WRITE)` on `findAndLockByPaymentId`
- `PaymentOutboxTransactionIntegrationTest.java:152-169` — verifies rollback atomicity

**Likely follow-up:** "What happens if TX2 fails after the provider succeeds?"
**Answer:** "The payment stays in CREATED. The idempotency key is reserved
but not finalized (provisional state: response_status=202, body='{}'). Recovery
re-submits to the provider (with the same idempotency key) and TX2 retries.
If the provider already processed it, the simulated provider returns the same
cached result."

**Common weak answer to avoid:** "I use @Transactional" (without explaining WHY
explicit TransactionTemplate is needed, or what happens during the provider call
between the two transactions).

---

### Question 10: How does validation work in this project?

**What the interviewer is testing:** Bean Validation knowledge and separation of concerns.

**Strong answer:** "Two layers:
1. **Structural validation** on the request DTO: `CreatePaymentRequest.java` uses
   `@NotBlank`, `@Size(max=255)`, `@Pattern` (for currency ISO-4217). Triggered by
   `@Valid` on the controller parameter (line 76). Violations return `400
   VALIDATION_FAILED`.
2. **Business-rule validation** in the domain: `Money.of(amountStr, currency)`
   throws `IllegalArgumentException` if the amount is non-positive or the scale
   doesn't match the currency. `Currency.fromCode()` throws for unsupported codes.
   These are caught by `PaymentGatewayExceptionHandler` and returned as `400
   INVALID_REQUEST`."

**Important technical details:**
- `CreatePaymentRequest.java` — record with `@NotBlank`, `@Size`, `@Pattern`
- `PaymentController.java:76` — `@Valid @RequestBody`
- `Money.java` — validates non-negative and scale
- `PaymentGatewayExceptionHandler.java` — handles `MethodArgumentNotValidException`
- `payment-api.md:234` — error codes table

**Likely follow-up:** "Why validate in the domain if the DTO already validates?"
**Answer:** "Defense in depth. The DTO validates HTTP-level constraints (format,
presence). The domain validates semantic constraints (business rules). A service
could be called directly without going through HTTP — the domain invariant
must hold regardless of entry point."

**Common weak answer to avoid:** "Spring validates the request" (without
distinguishing structural vs business validation, or mentioning the DTO).

---

## D. Transactions and Database

### Question 11: Why use both optimistic and pessimistic locking?

**What the interviewer is testing:** Understanding of concurrency control strategies
and their trade-offs.

**Strong answer:** "They serve different purposes:
- **Pessimistic locking (`SELECT FOR UPDATE`)** is used for the *read-modify-write*
  cycle during state transitions. `PaymentRepository.findAndLockByPaymentId()`
  (line 39) with `@Lock(PESSIMISTIC_WRITE)` ensures exclusive access to the row
  during TX2. This prevents two threads from simultaneously reading CREATED,
  both transitioning to PROCESSING, and the second overwriting the first's
  transition.

- **Optimistic locking (`@Version`)** catches lost updates in other paths —
  for example, if recovery and a live request both try to update the same payment
  without an explicit `SELECT FOR UPDATE`. The version column increments on each
  commit; if the version in the `WHERE` clause doesn't match, the update affects
  0 rows and JPA throws `ObjectOptimisticLockingFailureException`.

The code retries optimistic lock failures 3 times with backoff in
`ChargeService.applyProviderResult()` (line 254-337)."

**Important technical details:**
- `PaymentEntity.java:117` — `@Version` on `version` column
- `PaymentRepository.java:39-40` — `@Lock(PESSIMISTIC_WRITE)` on `findAndLockByPaymentId`
- `ChargeService.java:323-336` — retry loop for `ObjectOptimisticLockingFailureException`
- `docs/architecture.md:412` — "Isolation: READ_COMMITTED"

**Likely follow-up:** "Which is better for high-concurrency systems?"
**Answer:** "Optimistic is better when conflicts are rare (most updates succeed
on first try — fast path). Pessimistic is better when conflicts are likely and
you can afford to block. We use pessimistic for state transitions (conflicts
are possible) and optimistic as a safety net."

**Common weak answer to avoid:** "They're the same thing" or "optimistic is always
better" (without understanding the read-modify-write problem).

---

### Question 12: What isolation level does PostgreSQL use, and is it sufficient?

**What the interviewer is testing:** Database theory knowledge and its application
to financial systems.

**Strong answer:** "PostgreSQL defaults to `READ_COMMITTED`, which is sufficient
for our use case because:
1. We use `SELECT FOR UPDATE` for pessimistic locking on payment rows and
   idempotency rows — this provides row-level exclusivity within the transaction.
2. We use `@Version` for optimistic locking as a safety net.
3. We use a consistent lock ordering (idempotency → payment → journal → outbox)
   to prevent deadlocks.

We do NOT use `REPEATABLE_READ` or `SERIALIZABLE` because:
- The serialization overhead is unnecessary — our explicit locking strategy
  already prevents the anomalies we care about (dirty reads, lost updates,
  phantom reads in our specific access patterns).
- Higher isolation levels can cause more deadlocks and lower throughput.

`READ_COMMITTED` with explicit row locks is the industry standard for financial
systems because it gives you the precision of locking individual rows without
the overhead of full transaction serialization."

**Important technical details:**
- `docs/architecture.md:412` — "Isolation: READ_COMMITED (Postgres default)"
- No custom isolation level set in `application.yml` or `TransactionTemplate`
- Lock ordering documented in `docs/architecture.md:414`

**Likely follow-up:** "What anomalies can READ_COMMITTED cause?"
**Answer:** "Non-repeatable reads and phantom reads within the same transaction.
However, our two-phase transaction model minimizes the window — each transaction
is short and focused. We also use `saveAndFlush()` to force writes before
committing, ensuring consistency."

**Common weak answer to avoid:** "I just use the default" (without explaining
WHY it's sufficient or what anomalies are mitigated).

---

## E. Idempotency

### Question 13: Why is idempotency required in a payment system?

**What the interviewer is testing:** Fundamental understanding of distributed systems
and payment semantics.

**Strong answer:** "Because network failures are inevitable in distributed systems.
The client sends a request, the server processes it, but the response is lost
(network partition, load balancer timeout, client crash). The client retries.
Without idempotency, the retry would:
1. Create a second payment record (duplicate charge)
2. Call the provider a second time (double-debit)

With idempotency, the retry detects the existing result (via the idempotency key)
and returns the original response without re-processing. This is the foundation of
reliable distributed transactions — the client must be able to retry safely.

In our implementation, the idempotency key is `(merchant_id, idempotency_key)`
stored in PostgreSQL with a unique constraint. The constraint is the ultimate
arbiter — even if application-level checks race, the database enforces uniqueness."

**Important technical details:**
- `IdempotencyEntity.java:23` — `@Table(name = "idempotency")`
- `V3__idempotency_provider_retry.sql:44` — `UNIQUE (merchant_id, idempotency_key)`
- `PaymentController.java:95-135` — idempotency reservation + conflict handling

**Likely follow-up:** "What if the idempotency key expires?"
**Answer:** "The idempotency record has an `expires_at` column. After expiry, a
new request with the same key would create a new payment. This is acceptable
because expired keys are old enough that the original payment is likely completed
or failed — the risk window has passed."

**Common weak answer to avoid:** "To prevent duplicate requests" (without
explaining the network failure scenario and the double-charge risk).

---

### Question 14: Why is a database unique constraint necessary? Why not just check in the application?

**What the interviewer is testing:** Understanding of database-level vs
application-level guarantees.

**Strong answer:** "Application-level checks have a race condition:
```
Thread A: SELECT idem_key → not found → INSERT
Thread B: SELECT idem_key → not found (before A commits) → INSERT
```
Both threads see 'not found' and both insert. The application check alone cannot
prevent this — you'd need a distributed lock, which is complex and error-prone
(Redis locks can expire, network partitions can cause false positives).

The database unique constraint provides **atomicity** — PostgreSQL's row-level
locking on the index ensures only one INSERT succeeds. The other gets SQL state
23505 (unique violation). We catch this in the controller (line 117-135) and
resolve it by calling `replay()` to return the existing result.

This is the principle of 'database as the source of truth' — the application
code may have races, but the constraint always wins."

**Important technical details:**
- `V3__idempotency_provider_retry.sql:44` — `UNIQUE (merchant_id, idempotency_key)`
- `PaymentController.java:117-123` — catches `RuntimeException`, checks for "23505" or "unique"
- `PaymentController.java:124-135` — `idempotencyService.replay()`

**Likely follow-up:** "Is the replay from the cached response_body or from the payment?"
**Answer:** "Currently, the controller rebuilds the response from the `Payment` domain
object (line 106-108), not from the cached `response_body` JSON. This is a deliberate
trade-off — the response is semantically correct but not byte-for-byte identical.
The cached `response_body` exists but isn't returned directly yet (see 'PARTIALLY
IMPLEMENTED' status)."

**Common weak answer to avoid:** "The application checks first" (without explaining
the race condition and why DB constraints are the source of truth).

---

### Question 15: Why is a Java synchronized block insufficient for idempotency?

**What the interviewer is testing:** Understanding of JVM vs distributed boundaries.

**Strong answer:** "A `synchronized` block only works within a single JVM. In a
multi-instance deployment (horizontal scaling behind a load balancer), two requests
with the same idempotency key can hit *different* application instances. Each
instance has its own JVM memory — `synchronized` on instance A does not block
instance B. Both would proceed to process the payment, causing double-charging.

The PostgreSQL unique constraint works across all instances because they all
share the same database. It serializes at the database level, not the JVM level.

Additionally, `synchronized` blocks tie up the request thread (which, with virtual
threads, is less of a concern but still doesn't solve the multi-instance problem).
Database-level locking via `SELECT FOR UPDATE` or unique constraint is the
correct cross-instance solution."

**Important technical details:**
- `docs/idempotency-design.md:62-64` — "PostgreSQL row locks under READ_COMMITTED
  give true serializability guarantees"
- `docs/architecture.md:438` — "The unique constraint is the source of truth; the
  row lock only reduces the window"

**Likely follow-up:** "What about Redis distributed locks?"
**Answer:** "Redis locks are probabilistic — they can expire (network partition
between unlock and expiry), they don't survive Redis restarts, and Redlock
has well-known edge cases. PostgreSQL row locks under READ_COMMITTED give
true serializability. Redis is used in our design as an L1 *cache* for
fast-path idempotency lookups, not as the source of truth."

**Common weak answer to avoid:** "synchronized works fine" (without understanding
distributed systems).

---

### Question 16: What happens if the same idempotency key is reused with a different payload?

**What the interviewer is testing:** Understanding of the idempotency conflict
resolution strategy.

**Strong answer:** "The behavior depends on the existing record's state:
1. **Non-terminal existing record:** The `PostgresIdempotencyStore.reserve()`
   (line 53-80) finds the existing row, compares the `request_hash`. If different,
   and the record is non-terminal, it returns a `ConflictOutcome` → controller
   throws `IdempotencyKeyConflictException` → HTTP 409.

2. **Terminal existing record:** If the existing record is terminal (is_terminal=true),
   the store returns a `ReplayOutcome` regardless of fingerprint mismatch
   (line 67-74). This is intentional — if the payment already completed
   (SUCCEEDED/FAILED), returning the original result is safer than rejecting
   with a 409. The user experience is better: 'your payment went through' rather
   than 'key conflict.'"

**Important technical details:**
- `PostgresIdempotencyStore.java:55-74` — same request vs terminal mismatch vs conflict
- `IdempotencyEntity.java:51-52` — `isTerminal` field
- `PaymentController.java:103-116` — handles all three outcomes

**Likely follow-up:** "What HTTP status does each case return?"
**Answer:** "Same payload + non-terminal → 409; different payload + non-terminal →
409; any payload + terminal → 200 (replay). The 409 response has
`error: IDEMPOTENCY_KEY_CONFLICT`."

**Common weak answer to avoid:** "It always returns 409" (without the terminal
exception, which is a deliberate UX choice).

---

## F. Concurrency

### Question 17: How do two concurrent requests with the same idempotency key behave?

**What the interviewer is testing:** Understanding of race conditions and database-level
serialization.

**Strong answer:** "Both enter `PaymentController.createPayment()` concurrently.
Both compute the same fingerprint. Both call `idempotencyService.reserve()`:

1. `PostgresIdempotencyStore.reserve()` does `SELECT FOR UPDATE lockByKey()`. Since
   no row exists yet, both get `Optional.empty()`.
2. Both proceed to `insertReservation()`. PostgreSQL serializes these INSERTs
   on the unique index. One wins (commits), the other gets SQL state 23505
   (unique violation).
3. The winning thread proceeds: TX1 commits (payment + outbox + idempotency),
   provider call, TX2 commits.
4. The losing thread's controller (line 117-123) catches the `RuntimeException`,
   checks for '23505' or 'unique' in the message, and calls
   `idempotencyService.replay()` (line 124).
5. `replay()` finds the winning thread's committed idempotency row and returns
   a `ReplayOutcome`.
6. The controller returns HTTP 200 with the cached payment.

**Result:** Exactly one payment is created, exactly one provider call is made.
The losing thread gets the cached result."

**Important technical details:**
- `PostgresIdempotencyStore.java:50-51` — `lockByKey(merchantId, key.value())`
  uses `@Lock(PESSIMISTIC_WRITE)`
- `IdempotencyRepository.java:47-63` — `insertReservation` native INSERT
- `PaymentController.java:117-135` — catches and replays on 23505
- `IdempotencyEntity.java:44` — `UNIQUE (merchant_id, idempotency_key)`

**Likely follow-up:** "What if both threads pass the SELECT and both try INSERT?"
**Answer:** "That's exactly the scenario the unique constraint handles. Both
SELECTs return empty (no row yet), both try INSERT. PostgreSQL's unique index
is the arbiter — only one INSERT commits; the other gets 23505. The controller
catches this and replays."

**Common weak answer to avoid:** "One waits for the other" (without explaining
the 23505 unique violation and replay mechanism).

---

### Question 18: How would you test concurrency?

**What the interviewer is testing:** Practical testing approach for concurrent systems.

**Strong answer:** "We use a multi-pronged approach:
1. **Unit tests** for the state machine (`PaymentStateEngineTest`) — 20 tests
   covering all transitions and edge cases. These run without Spring or DB.
2. **Integration tests with Testcontainers** for the outbox and Kafka —
   `PaymentOutboxTransactionIntegrationTest`, `KafkaOutboxIntegrationTest`.
   These use PostgreSQL and Kafka containers with dynamic ports.
3. **Concurrency testing** is specifically covered in
   `KafkaOutboxIntegrationTest.twoPublisherWorkersDoNotConcurrentlyPublishOneRow()`
   (line 155) which runs two threads calling `publishDueEvents()` concurrently
   and asserts only one Kafka message is produced (via `countRecords` = 1).
4. **Mocked tests are insufficient for database locking** because they don't
   exercise the actual PostgreSQL row locks or unique constraint behavior.
   We need real DB tests for that — which is why we use Testcontainers."

**Important technical details:**
- `KafkaOutboxIntegrationTest.java:155` — concurrency test with `ExecutorService`
- `PaymentStateEngineTest.java` — pure unit tests (no Spring needed)
- `docs/architecture.md:414` — deadlock retry note

**Likely follow-up:** "Why not test concurrency with mocks?"
**Answer:** "Mocks can't simulate PostgreSQL's row-level locking, the 23505
unique violation, or `FOR UPDATE SKIP LOCKED` behavior. Mocks would pass tests
but fail in production. Database locking is a property of the database, not the
application — we must test against a real database (via Testcontainers)."

**Common weak answer to avoid:** "I use @Async and Thread.sleep" (without
explaining why real database concurrency tests are necessary).

---

## G. Provider Integration

### Question 19: Why can't a provider timeout be treated as a failure?

**What the interviewer is testing:** Deep understanding of payment semantics and
the double-charge problem.

**Strong answer:** "A provider timeout means: we sent the request, but we don't
know if the provider received and processed it. The provider may have:
1. Not received it (server crashed, network dropped) → safe to retry
2. Received and processed it (customer charged) but response was lost → retrying
   with a NEW idempotency key would **double-charge**
3. Still processing (slow provider) → retry returns the original result

If we treat timeout as `FAILED` and the client retries with a *different*
idempotency key, the provider sees two distinct charges and the customer's
card is debited twice. This is the classic 'at-most-once messaging is not enough,
and at-least-once risks duplicates' problem.

Our solution: transition to `UNKNOWN` (non-terminal). The provider idempotency
key (`prov_<paymentId>`) is reused on retry — real providers like Stripe and
Adyen deduplicate by this key, returning the original result. No second charge
occurs. The payment stays in UNKNOWN until a confirmed outcome is received."

**Important technical details:**
- `Payment.java:218-223` — `ensureProviderIdempotencyKey()` derives `prov_<paymentId>`
- `Payment.java:293-299` — `mapResultToStatus`: `UNKNOWN → PaymentStatus.UNKNOWN`
- `SimulatedPaymentProcessor.java:70-77` — replays cached result for same key
- `ProviderResult.java:55-57` — `isAmbiguous()` method

**Likely follow-up:** "How do you eventually resolve UNKNOWN?"
**Answer:** "Via recovery scheduler (re-submits with same provider key) or
reconciliation (Stage 6) — polling the provider's status endpoint. The key is
that we NEVER auto-charge with a different idempotency key."

**Common weak answer to avoid:** "Timeouts are always failures" (this would
cause double-charging in the real world).

---

### Question 20: Why must the provider idempotency key remain stable?

**What the interviewer is testing:** Understanding of provider-level idempotency
and the double-charge prevention mechanism.

**Strong answer:** "The provider idempotency key (`prov_<paymentId>`) is sent to
the provider so that if we retry the same payment attempt, the provider
recognizes it as a duplicate and returns the original result instead of
processing it again. This is how Stripe's `Idempotency-Key` header and Adyen's
`idempotencyKey` work.

If we generated a *new* key on each retry, the provider would treat each retry
as a new charge — if the first call actually succeeded (customer debited) but
we lost the response, the retry with a new key would **double-charge**.

In our code, `Payment.ensureProviderIdempotencyKey()` (line 218) generates the
key once and persists it. `recordProviderAttempt` (line 191) only sets it if
it's null — so retries reuse the same key. The `SimulatedPaymentProcessor`
mirrors this: it caches the first result per key and replays it."

**Important technical details:**
- `Payment.java:218-223` — deterministic key: `"prov_" + this.paymentId.toString()`
- `Payment.java:198-200` — `recordProviderAttempt` only sets if null
- `V3__idempotency_provider_retry.sql:67-69` — unique constraint on `provider_idempotency_key`
- `SimulatedPaymentProcessor.java:107-111` — `putIfAbsent` for caching

**Likely follow-up:** "What if the key collides — two payments get the same prov key?"
**Answer:** "The `uq_payment_provider_idempotency_key` unique constraint prevents
this. Since the key is derived from the payment ID (UUID), collisions are
astronomically unlikely."

**Common weak answer to avoid:** "We just generate a random key each time"
(this would cause double-charging on retry after timeout).

---

### Question 21: How does the system prevent double-charging?

**What the interviewer is testing:** Understanding of the end-to-end safety net.

**Strong answer:** "Multiple layers:

1. **Client idempotency key:** The `Idempotency-Key` header + PostgreSQL unique
   constraint on `(merchant_id, idempotency_key)` ensures at most one payment
   per key. A concurrent or retried request with the same key gets a `ReplayOutcome`,
   not a new payment.

2. **Provider idempotency key:** `prov_<paymentId>` is sent to the provider on
   every attempt. If the provider already processed this key (e.g. after a timeout
   retry), it returns the original result — no second charge.

3. **UNKNOWN state:** When a provider call times out, we don't know if it succeeded,
   so we DON'T retry with a new key. We transition to UNKNOWN and recovery
   re-submits with the SAME provider key.

4. **Terminal state check:** `Payment.applyProviderResult()` (line 149-158)
   checks `this.status.isTerminal()` first. A duplicate provider callback for
   an already-terminal payment is a safe no-op.

5. **Database constraints:** `uq_payment_provider_ref` (unique, WHERE NOT NULL)
   ensures a provider reference can't be associated with two payments.

The key insight: we never retry a *confirmed* result with a *different* key."

**Important technical details:**
- `Payment.java:149-158` — idempotent apply with terminal check
- `IdempotencyEntity.java:23` — `@Table(name = "idempotency")` with unique constraint
- `PaymentEntity.java:79` — `providerReference` is `unique = true`

**Likely follow-up:** "What if the idempotency table itself has a bug?"
**Answer:** "The provider idempotency key is an independent safety net. Even if
the gateway's idempotency logic fails, the provider's own idempotency mechanism
would prevent a double-charge. Defense in depth."

**Common weak answer to avoid:** "We check if the payment already has a provider
reference" (without explaining the idempotency key + UNKNOWN state mechanism).

---

## H. Failure Handling and Recovery

### Question 22: What happens if the database commits but Kafka is unavailable?

**What the interviewer is testing:** Understanding of the outbox pattern and
decoupling of concerns.

**Strong answer:** "The payment transaction commits independently of Kafka. The
outbox row is written atomically with the payment state change in the same
transaction (TX1 or TX2). If Kafka is down, the outbox row stays in `PENDING`
status. The `OutboxPublisher` (scheduled, line 55) retries with exponential
backoff (bounded by `retry-max-backoff: 30s`). After 10 failed attempts
(`max-attempts: 10`), it routes to the DLQ (`payment.events.DLQ`).

The payment itself is durable and correct — the ERP can poll `GET /payments/{id}`
as a fallback. Kafka availability does NOT affect payment processing. This is
the core benefit of the transactional outbox pattern: you decouple business
transactionality from event publishing."

**Important technical details:**
- `OutboxPublisher.java:55` — `@Scheduled(fixedDelayString = "${app.outbox.polling-interval:1s}")`
- `OutboxPublisher.java:104-135` — retry + DLQ logic
- `V4__payment_outbox.sql:14` — `status` check constraint
- `docs/stage-5-outbox-kafka-erp.md:169-171` — "Kafka availability is not required"

**Likely follow-up:** "How do you ensure no events are lost?"
**Answer:** "The outbox row is the durable record. Kafka sends are retried
with backoff. The `ack` from Kafka is confirmed via `.get(sendTimeout)`.
Only after receiving the ack is the row marked `PUBLISHED`. If the app
crashes between the Kafka ack and the DB update, the row stays PENDING
and is re-published on restart — consumers deduplicate by `event_id`."

**Common weak answer to avoid:** "We use transactions with Kafka" (Kafka can't
participate in the same DB transaction — that's why we use the outbox pattern).

---

### Question 23: Why use a transactional outbox instead of publishing Kafka directly?

**What the interviewer is testing:** Understanding of distributed transaction
challenges and the outbox pattern.

**Strong answer:** "You can't have a single ACID transaction spanning PostgreSQL
and Kafka — they're separate systems. XA transactions are complex, slow, and
not well-supported. If you publish to Kafka *before* committing the DB
transaction, and the DB commit fails, you've published an event for a payment
that doesn't exist. If you publish *after* the DB commit, a crash between
commit and publish loses the event.

The transactional outbox solves this: the outbox row is written in the same
transaction as the payment state change. If the transaction commits, the row
is durable. The background publisher reads committed rows and publishes them.
If Kafka is down, rows accumulate in the `outbox` table — they're not lost.
This gives us at-least-once delivery with a durable buffer."

**Important technical details:**
- `ChargeService.java:156-164` — outbox append in TX1, in same `TransactionTemplate`
- `V4__payment_outbox.sql` — outbox table with FK to payment
- `OutboxEventEntity.java:8` — `@Table(name = "outbox")` with `aggregate_id REFERENCES payment`
- `docs/stage-5-outbox-kafka-erp.md:163-166` — at-least-once explanation

**Likely follow-up:** "How do consumers handle duplicate events?"
**Answer:** "By deduplicating on `event_id` (UUID). The `OutboxEventEntity.eventId`
is unique. Consumers persist processed event IDs before applying effects —
duplicate events are detected and ignored. Our test fixture
`ErpPaymentEventFixture` does exactly this."

**Common weak answer to avoid:** "Kafka is always available" (it's not — the
outbox decouples payment correctness from Kafka availability).

---

## I. Kafka and Transactional Outbox

### Question 24: What does at-least-once delivery mean?

**What the interviewer is testing:** Understanding of messaging delivery guarantees.

**Strong answer:** "At-least-once means an event may be delivered zero or more
times — but never less than once. In practice: every committed outbox row will
eventually be published to Kafka. The risk is duplicates, not loss.

We achieve this by:
1. Marking a row `PUBLISHED` only AFTER receiving Kafka's acknowledgement
   (`kafkaTemplate.send(...).get(timeout)`) (line 86).
2. If the ack is received but the DB update to `PUBLISHED` crashes, the row
   stays `PENDING` and is re-published on the next poll.
3. Consumers deduplicate by `event_id`.

This is in contrast to:
- **At-most-once:** fire-and-forget — messages may be lost if the producer
  crashes.
- **Exactly-once:** requires distributed transactions or idempotent producers
  with idempotent consumers — we explicitly do NOT claim this.

Our `docs/stage-5-outbox-kafka-erp.md:163` states: 'Not exactly-once.
Delivery is at-least-once.'"

**Important technical details:**
- `OutboxPublisher.java:86-88` — `.get(timeout)` before `markPublished`
- `OutboxEventEntity.java:24` — `event_id` is `unique = true`
- Producer config: `ENABLE_IDEMPOTENCE_CONFIG=true`, `ACKS_CONFIG="all"`

**Likely follow-up:** "How is this different from Kafka's idempotence?"
**Answer:** "Kafka idempotence (`enable.idempotence=true`) prevents duplicate
records within a single producer session for a given partition. But it doesn't
give end-to-end business exactly-once — the consumer must still deduplicate
because the producer can crash between sending and the outbox row being marked
PUBLISHED."

**Common weak answer to avoid:** "It means exactly-once" (these are
fundamentally different guarantees).

---

### Question 25: How do consumers handle duplicate events?

**What the interviewer is testing:** Understanding of consumer-side deduplication.

**Strong answer:** "Consumers deduplicate by `event_id` (a UUID generated
for each event). The flow:
1. Read the event from Kafka.
2. Check if `event_id` has been processed (via a local `processed_events` table
   or in-memory `Set` for the test fixture).
3. If already processed → skip (return without applying business effect).
4. If new → persist `event_id` in the dedup store, THEN apply the business effect.

The ordering (check-then-persist-then-apply) is critical. If the consumer
crashes between steps 3 and 4, restarting re-processes the event — but the
dedup check catches it.

In `KafkaOutboxIntegrationTest.erpFixtureConsumesCorrelatedEventsAndIgnoresDuplicates()`
(line 214), the `ErpPaymentEventFixture` uses a `Set<UUID>` of processed
event IDs. The first call returns `true` (applied), the second returns
`false` (ignored as duplicate)."

**Important technical details:**
- `ErpPaymentEventFixture.java` — in-test fixture (NOT a production consumer)
- `KafkaOutboxIntegrationTest.java:224-229` — tests `process()` returns `true` then `false`
- `ErpPaymentEventFixture.ErpInvoiceState` — PAID, REVIEW_REQUIRED, PAYMENT_FAILED

**Likely follow-up:** "Should the dedup check happen before or after the business effect?"
**Answer:** "Before persisting to the dedup store, but the persistence must
happen in the same transaction as the business effect. Otherwise, you could
apply the effect, crash, and on restart re-apply it. The pattern is:
check dedup → begin transaction → apply effect + insert dedup record → commit.
The commit is atomic."

**Common weak answer to avoid:** "Kafka guarantees exactly-once" (it doesn't —
consumers must deduplicate; Kafka's idempotent producer only prevents
intra-session duplicates).

---

### Question 26: How is event ordering preserved?

**What the interviewer is testing:** Understanding of Kafka partitioning and
ordering guarantees.

**Strong answer:** "Ordering is guaranteed **per payment** (per partition key),
NOT globally across all payments.

The mechanism:
1. **Same Kafka partition:** All events for a single payment use the payment UUID
   as the `event_key` (line 85 in `OutboxPublisher.java`). Kafka routes all
   messages with the same key to the same partition.
2. **Sequential publishing:** The `findDue` query (line 19-47 in
   `OutboxEventRepository.java`) uses a `NOT EXISTS` subquery to skip later
   events for a payment while earlier events are still pending. This prevents
   out-of-order publishing.
3. **Partition ordering:** Within a partition, Kafka preserves the order of
   messages as they were produced.

Cross-payment ordering is NOT guaranteed — events for payment A and payment B
may be interleaved. But within one payment, events are always in `event_order`
sequence."

**Important technical details:**
- `OutboxPublisher.java:85` — `event.getEventKey()` = payment UUID string
- `OutboxEventRepository.java:19-47` — `findDue` with `NOT EXISTS` subquery
- `V4__payment_outbox.sql:23` — `UNIQUE (aggregate_id, aggregate_id, event_order)`
- `PaymentLifecycleEvent.java:13` — `eventOrder` field

**Likely follow-up:** "What if you need global ordering?"
**Answer:** "You'd need a single partition — but that kills parallelism. For
payments, global ordering isn't needed — each payment is independent. If you
needed cross-payment ordering (e.g. settlement batches), you'd use a different
partitioning strategy or a sequencing service."

**Common weak answer to avoid:** "Kafka preserves global order" (it only
guarantees per-partition order).

---

### Question 27: What happens if the outbox publisher crashes mid-batch?

**What the interviewer is testing:** Understanding of transactional boundaries
and crash recovery.

**Strong answer:** "The `publishDueEvents()` method (line 55) is annotated
`@Transactional` (line 54). Each call to `publishOne()` marks a row as
PUBLISHED or FAILED within that transaction. If the publisher crashes mid-batch:

1. Rows that were already marked PUBLISHED remain PUBLISHED.
2. Rows that were being processed (claimed but not yet marked) have their
   `lock_owner` and `locked_until` set but not cleared. On restart,
   the `claim()` method's `WHERE (lock_owner IS NULL OR locked_until < :claimedAt)`
   condition allows another worker to reclaim stale leases.
3. Rows still PENDING (not yet claimed) are picked up on the next poll.

The `@Transactional` annotation ensures that each `publishOne()` call's status
update is atomic — a crash either commits the PUBLISHED/FAILED update or
rolls it back (leaving the row PENDING)."

**Important technical details:**
- `OutboxPublisher.java:54` — `@Transactional` on `publishDueEvents()`
- `OutboxPublisher.java:75` — `claim()` with `locked_until` lease
- `OutboxEventRepository.java:59-70` — `claim` with `WHERE (lock_owner IS NULL OR locked_until < :claimedAt)`
- `V4__payment_outbox.sql:21-23` — `lock_owner`, `locked_until` columns

**Likely follow-up:** "Is the whole batch atomic?"
**Answer:** "No — the `@Transactional` wraps the entire `publishDueEvents()`
method, so if one `publishOne()` throws, the whole transaction rolls back.
But `publishOne()` catches exceptions internally (line 91) and calls
`handleFailure()` instead of propagating. So a failure on one event doesn't
abort the batch — it's handled inline. However, the status update for the
failed event and the retry scheduling for the next event happen in the same
transaction — so if the app crashes between them, both are rolled back
and retried on restart."

**Common weak answer to avoid:** "The batch is atomic" (without explaining
the lease recovery and per-event handling).

---

## J. API Design

### Question 28: Why is the Idempotency-Key header mandatory?

**What the interviewer is testing:** API design principles for distributed systems.

**Strong answer:** "Because payment creation is not naturally idempotent.
Unlike a GET request, `POST /payments` creates a resource — calling it twice
creates two resources. Without an idempotency key, if the client's HTTP client
has a transport timeout and retries, or if the load balancer retries, the
customer's card could be charged twice.

By making the key mandatory, we force clients to think about retry safety
upfront. The client generates a unique key (e.g. UUID) per payment command
and reuses it for retries. The gateway stores the key + response, so retries
return the original result without re-processing.

This is the same pattern used by Stripe (`Idempotency-Key` header), Adyen,
and other payment providers."

**Important technical details:**
- `PaymentController.java:81` — `@RequestHeader(value = "Idempotency-Key", required = true)`
- `docs/payment-api.md:28` — "Idempotency-Key: Stable command key"
- `PaymentController.java:95-96` — `IdempotencyKey.of(idempotencyKey)`

**Likely follow-up:** "What scope should the key have?"
**Answer:** "Merchant-scoped. The `(merchant_id, idempotency_key)` composite
unique constraint means two different merchants can reuse the same key value
without conflict. The key is only unique within a merchant's context."

**Common weak answer to avoid:** "It's for caching responses" (it's for
preventing duplicate processing, not just caching).

---

### Question 29: What HTTP status codes does the API return, and why?

**What the interviewer is testing:** REST API design and status code semantics.

**Strong answer:** "The API follows REST conventions with domain-specific
semantics:
- **201 Created:** New payment processed. Returned on first request.
- **200 OK:** Idempotent replay. The same key+body was already processed — we
  return the cached result.
- **202 Accepted:** Asynchronous result. The payment is in UNKNOWN state — the
  outcome is uncertain. The client should check back later. (DEFERRED in
  current implementation — the controller maps to 201 even for UNKNOWN.)
- **400 Bad Request:** Structural validation failure (missing fields, bad format)
  or business rule failure (unsupported currency, negative amount).
- **404 Not Found:** Payment not found by ID.
- **409 Conflict:** Idempotency key conflict (same key, different payload, non-terminal)
  or illegal state transition.
- **500 Internal Server Error:** Unexpected server error.

The `ChargeResult.responseStatus` (computed in `ChargeService.statusCodeFor`)
encodes this, but the controller currently maps only `replayed ? 200 : 201`
(line 151)."

**Important technical details:**
- `ChargeService.java:437-443` — `statusCodeFor()` computes 200/201/202
- `PaymentController.java:151` — currently only checks `replayed`
- `docs/stage-5-status.md:70` — "HTTP status for UNKNOWN (202) — DEFERRED"

**Likely follow-up:** "Why not return 202 for UNKNOWN?"
**Answer:** "It's deferred. The `statusCodeFor` method correctly computes 202
for UNKNOWN, but the controller doesn't use it yet — it maps only based on
`replayed`. Returning 202 would require the controller to inspect the payment
status from the `ChargeResult` and map accordingly. This is a Stage 5 handoff
item."

**Common weak answer to avoid:** "202 means it's queued" (202 for payment
means the outcome is uncertain, not that the request is queued).

---

## K. Testing

### Question 30: How is the state machine tested?

**What the interviewer is testing:** Unit testing approach and coverage mentality.

**Strong answer:** "The state machine is tested in `PaymentStateEngineTest.java`
— 20 unit tests covering:
- All 7 valid transitions (CREATED→PROCESSING, PROCESSING→SUCCEEDED/FAILED/UNKNOWN/REQUIRES_RECONCILIATION,
  UNKNOWN→SUCCEEDED/FAILED, REQUIRES_RECONCILIATION→SUCCEEDED/FAILED)
- Self-transition rejection (PROCESSING→PROCESSING)
- Terminal state enforcement (SUCCEEDED→anything throws)
- Null current status throws
- `VOIDED` and `REFUNDED` are terminal and unreachable in current code
- `REQUIRES_RECONCILIATION → UNKNOWN` is rejected (only terminal exits allowed)

These are pure unit tests — no Spring context, no database. The
`PaymentStateEngine.transition()` method is a pure function:
`(current, target, reason) → PaymentStatus`."

**Important technical details:**
- `PaymentStateEngineTest.java:10` — `class PaymentStateEngineTest` (no `@SpringBootTest`)
- `PaymentStateEngineTest.java:171` — `assertThat(PaymentStatus.SUCCEEDED.isTerminal()).isTrue()`
- Uses AssertJ: `assertThatThrownBy(...).isInstanceOf(IllegalStateTransitionException.class)`

**Likely follow-up:** "What's missing from the test coverage?"
**Answer:** "The recovery path's provider re-submission (`processor.process()`
during recovery) is not tested because the current `PaymentRecoveryService`
does not call `processor.process()`. The tests focus on state transitions
and outbox event generation, not on the actual provider call during recovery."

**Common weak answer to avoid:** "I test with Spring context" (domain logic
should be tested without framework overhead).

---

## L. Observability

### Question 31: How do you trace a payment across the system?

**What the interviewer is testing:** Distributed tracing and correlation.

**Strong answer:** "Two mechanisms:
1. **MDC correlation ID:** `CorrelationIdFilter` (line 1) generates or resolves
   the `X-Correlation-Id` header and stores it in the MDC. Every log line
   includes `correlationId=%X{correlationId}` (from `application.yml:53`).

2. **Payment ID in events:** The `PaymentLifecycleEvent` record (line 8)
   carries `correlationId`, `paymentId`, `causationId`, and `eventId`. These
   flow through the outbox to Kafka, allowing the ERP consumer to trace the
   payment lifecycle.

3. **Metrics:** Micrometer counters like `payment_charge_requests_total`,
   `payment_state_duration_seconds`, `idempotency_conflicts_total` provide
   aggregate observability."

**Important technical details:**
- `CorrelationIdFilter.java` — sets correlationId in MDC
- `application.yml:53` — log pattern: `[traceId=%X{traceId} correlationId=%X{correlationId} paymentId=%X{paymentId} merchantId=%X{merchantId}]`
- `PaymentLifecycleEvent.java:25-26` — `correlationId`, `causationId`, `eventId`
- `OutboxMetrics.java` — Micrometer counters

**Likely follow-up:** "How do you correlate across service boundaries?"
**Answer:** "The `correlationId` in the event envelope allows the ERP to
correlate gateway events with their own invoice processing. The `causationId`
links events chronologically (e.g. `PaymentSucceeded` causally follows
`PaymentProcessingStarted`). In a microservices deployment, this would be
propagated via B3/Zipkin trace headers."

**Common weak answer to avoid:** "I use request IDs in logs" (without
explaining cross-system correlation via event envelopes).

---

## M. Security and Production Readiness

### Question 32: What are the current production-readiness gaps?

**What the interviewer is testing:** Honest self-assessment and risk awareness.

**Strong answer:** "Several known gaps:
1. **`@EnableScheduling` is missing** — `RecoveryScheduler` and `OutboxPublisher`
   `@Scheduled` methods won't auto-fire. Tests invoke directly, but a running
   app won't recover or publish events automatically.
2. **The code has compile errors** — `ReplayDuringReservationException` is
   referenced but not defined (line 172, 181 in `ChargeService.java`). The
   `ChargeResult` constructor arity is wrong (line 220). These are Stage 5
   handoff items.
3. **`ApplicationContextTest` is broken** — it excludes JPA config, so
   `IdempotencyRepository` isn't available, and the application context fails to load.
4. **No production ERP consumer** — only `ErpPaymentEventFixture` (test double).
5. **HTTP 202 for UNKNOWN not wired** — controller returns 201 regardless of status.
6. **Byte-exact replay not implemented** — controller rebuilds from Payment, not cached JSON.
7. **No settlement/reconciliation/ledger/refunds** — all deferred to Stage 6+.
8. **Recovery doesn't call the provider** — it transitions to PROCESSING but
   doesn't re-submit to the processor."

**Important technical details:**
- `docs/stage-5-status.md:26-32` — compile errors list
- `docs/stage-5-status.md:71` — `@EnableScheduling` missing
- `docs/stage-5-status.md:94-101` — `ApplicationContextTest` broken

**Likely follow-up:** "Which of these would you fix first?"
**Answer:** "The compile errors — nothing runs until the build is fixed.
Then `@EnableScheduling` — without it, recovery and event publishing
don't work in production. The `ApplicationContextTest` would be next for
CI/CD reliability."

**Common weak answer to avoid:** "It's production-ready" (it has multiple
known issues that would be caught in code review).

---

### Question 33: How is the payment token handled? What security measures are in place?

**What the interviewer is testing:** PCI-DSS awareness and data protection.

**Strong answer:** "The payment token is never persisted or returned:
1. **Never persisted:** `PaymentEntity.paymentToken` is annotated `@Transient`
   (line 60). The JPA entity does not map this column. Even if the database
   is compromised, tokens are not there.
2. **Never returned:** `PaymentDtoMapper.toResponse()` does not include the
   token. `PaymentController` returns `PaymentResponse` which omits it.
3. **Masked in logs:** Both `PaymentController.maskToken()` (line 225) and
   `SimulatedPaymentProcessor.maskToken()` (line 135) extract only the prefix
   (e.g. `success:`) and replace the rest with `*****`.
4. **Masked in fingerprints:** The SHA-256 fingerprint uses `maskToken()` so
   the hash never contains the full token (line 211 in PaymentController,
   line 390 in ChargeService).
5. **Masked in events:** `PaymentLifecycleEvent` does not include the token
   at all — the event payload only contains `providerReference`, `failureCode`,
   etc."

**Important technical details:**
- `PaymentEntity.java:60` — `@Transient private String paymentToken`
- `PaymentController.java:225-229` — `maskToken()` method
- `ChargeService.java:390` — `maskToken()` in fingerprint
- `PaymentLifecycleEvent.java` — no token field

**Likely follow-up:** "Would you encrypt the token at rest?"
**Answer:** "The token is a simulated value. In production with real card
data, we'd use tokenization (the PSP handles PCI compliance) and never
store raw PANs or CVV. The `@Transient` ensures no token data reaches the
database."

**Common weak answer to avoid:** "I hash the token" (hashing doesn't help —
a hash is still PII if the token space is small; better to never store it).

---

## N. System Design Follow-ups

### Question 34: How would you scale the recovery scheduler?

**What the interviewer is testing:** Horizontal scaling and distributed systems.

**Strong answer:** "Currently `RecoveryScheduler` is single-threaded with
`@Scheduled(fixedDelay = 60000L)`. To scale:
1. **Sharding by merchant:** Partition recovery jobs by `merchant_id` hash.
   Different instances handle different merchant shards. This is safe because
   payments are independent across merchants.
2. **Lock-free claiming:** The existing `findAndLockByPaymentId` uses
   `SELECT FOR UPDATE` — if two instances try to recover the same payment,
   one waits. With merchant sharding, this contention is eliminated.
3. **Dynamic interval:** Use `fixedDelay` with a shorter interval when
   the outbox backlog is low, longer when it's high.
4. **Parallel sweeps within a shard:** Process multiple payments per sweep
   using a thread pool, but ensure each payment is locked individually.

The `maxPerSweep` config (default 100) controls batch size. The
`countForRecovery()` metric helps monitor the backlog."

**Important technical details:**
- `RecoveryScheduler.java:53` — `@Scheduled(fixedDelay = 60000L, initialDelay = 30000L)`
- `RecoveryProperties.java:46` — `maxPerSweep = 100`
- `PaymentRecoveryService.java:84` — `Math.min(candidates.size(), properties.getMaxPerSweep())`

**Likely follow-up:** "What about the outbox publisher?"
**Answer:** "Same pattern — shard by partition key. The `FOR UPDATE SKIP LOCKED`
query already supports parallel publishing. Just add `@Scheduled` on multiple
instances with different merchant filters, or use a message-driven approach
where each partition has a dedicated consumer."

**Common weak answer to avoid:** "Add more threads" (without explaining the
locking and sharding strategy).

---

### Question 35: How would you migrate from the simulated provider to a real provider?

**What the interviewer is testing:** Adapter pattern and practical migration.

**Strong answer:** "The `PaymentProcessor` interface (line 39) is the abstraction.
To add a real provider (e.g. Stripe):
1. Create `StripePaymentProcessor implements PaymentProcessor` in
   `infrastructure/external/provider/`.
2. Register it as a Spring `@Component` or `@Bean` keyed by `PaymentMethodType`.
3. Route: `PaymentProcessor` is injected into `ChargeService` (line 84).
   Currently, there's a single `SimulatedPaymentProcessor` for all methods.
   We'd need a `Map<PaymentMethodType, PaymentProcessor>` to select by method.
4. The `process()` signature already accepts `providerIdempotencyKey` — real
   providers accept this as a header (`Idempotency-Key` for Stripe, `idempotencyKey`
   for Adyen).
5. Real providers use HTTP — we'd add a `WebClient` or `RestTemplate` with
   timeouts, retry policies, and circuit breakers.
6. The `Payment` domain model doesn't change — the state machine, idempotency,
   and outbox logic are provider-agnostic.

The key safety invariant: the provider idempotency key must be sent on every
retry. Real providers deduplicate by this key."

**Important technical details:**
- `PaymentProcessor.java:39-42` — `process(String paymentToken, long amountMinor, String currency, UUID correlationId, String providerIdempotencyKey)`
- `ChargeService.java:77` — `private final PaymentProcessor processor;`
- `docs/architecture.md:267` — "ChargeService selects processor by method type via a Map"
- `docs/stage-5-status.md:7` — "Sealed interface" (designed but the current `PaymentProcessor` is a plain interface, not sealed)

**Likely follow-up:** "What if the real provider has different response codes?"
**Answer:** "The adapter translates provider-specific responses into our
`ProviderResult` enum (SUCCESS, DECLINED, TECHNICAL_FAILURE, UNKNOWN). The
adapter handles provider-specific HTTP parsing; the domain only sees the
normalized result."

**Common weak answer to avoid:** "I just swap the implementation" (without
explaining the idempotency key mapping and the adapter's responsibility to
normalize provider responses).

---

### Question 36: How would you handle refunds and chargebacks?

**What the interviewer is testing:** Future planning and domain modeling.

**Strong answer:** "Both are deferred to Stage 4+:

**Refunds:** A refund is a new payment record with a negative direction (or a
`REFUNDED` terminal state on the original payment). The `PaymentStatus.REFUNDED`
enum exists (line 55) but is not reachable from current code paths. We'd need:
1. A `POST /api/v1/payments/{id}/refunds` endpoint.
2. A new payment or a `REFUNDED` transition from `SUCCEEDED`.
3. The provider's refund API call (Stripe: `POST /refunds`).
4. Audit trail: refunds must be traceable to the original payment.

**Chargebacks:** A chargeback is a provider-initiated dispute. We'd need:
1. A `CHANGEBACK` status or a new `Dispute` aggregate.
2. Webhook handling (deferred — `callbackUrl` is not in the request DTO).
3. Evidence submission to the provider.
4. Reconciliation against the settlement file (Stage 6).

The key design constraint: never auto-refund. A chargeback that succeeds
in the bank but fails in our system should be resolved via reconciliation,
not automatically reversed."

**Important technical details:**
- `PaymentStatus.java:53-55` — `REFUNDED` enum exists but `SUCCEEDED → REFUNDED`
  is not wired in `applyProviderResult`
- `docs/architecture.md:103` — "Authorization-vs-capture separation? Deferred to Stage 4"
- `docs/payment-api.md:250` — "Webhook/callback: DEFERRED"

**Likely follow-up:** "How would you prevent double-refunding?"
**Answer:** "Same idempotency pattern — the refund request has its own
idempotency key. The `payment_id` + `refund_id` composite key prevents
duplicates."

**Common weak answer to avoid:** "Just add a negative amount" (violates
the positive-amount invariant; refunds should be explicit operations).

---

### Question 37: How would you introduce a double-entry ledger?

**What the interviewer is testing:** Financial domain modeling and consistency.

**Strong answer:** "A double-entry ledger requires:
1. **Chart of accounts:** `LEDGER_ACCOUNT` table (defined in
   `docs/architecture.md:349-363` but NOT in migrations — deferred to Stage 4).
2. **Journal entries:** `JOURNAL_ENTRY` and `JOURNAL_LINE` tables (defined in docs
   but NOT implemented — deferred to Stage 4).
3. **Posting:** When a payment reaches `SUCCEEDED`, we post:
   - Debit: Cash (asset) +125000
   - Credit: Merchant Revenue (liability) -125000
   The entry must balance (sum = 0).
4. **Atomicity:** The journal entry is posted in TX2 — the same transaction that
   transitions the payment to SUCCEEDED. If the journal post fails, TX2 rolls
   back and the payment stays in PROCESSING.
5. **Reconciliation:** Journal entries are reconciled against settlement files
   (Stage 6) and bank statements.

The `Payment.journalEntryId` field (line 54, null in current code) would
reference the journal entry posted for this payment."

**Important technical details:**
- `Payment.java:54` — `private UUID journalEntryId; // null until ledger posted (Stage 4+)`
- `PaymentEntity.java:91-92` — `journal_entry_id` column exists
- `Payment.java:358-360` — `setJournalEntryId(UUID)` method
- `docs/architecture.md:349-363` — ledger schema (documentation, NOT in migrations)

**Likely follow-up:** "What if the ledger and payment disagree?"
**Answer:** "That's a reconciliation discrepancy. The reconciliation job
(Stage 6) compares the ledger against settlement files and bank statements.
Discrepancies are emitted as `reconciliation.discrepancy.v1` events for
manual review."

**Common weak answer to avoid:** "I add a balance column" (violates
audit-trail requirements; balances are derived, not stored).

---

### Question 38: What are the current production-readiness gaps?

**What the interviewer is testing:** Honest self-assessment and risk awareness.

**Strong answer:** "Major gaps:
1. **Compile errors** — the Stage 5 handoff left 5 compiler errors. Nothing runs.
2. **`@EnableScheduling` missing** — recovery and outbox publishing don't auto-fire.
3. **`ApplicationContextTest` broken** — CI can't validate context loading.
4. **No production ERP consumer** — only a test fixture.
5. **HTTP 202 for UNKNOWN** — controller returns 201 instead, misleading async callers.
6. **No circuit breaker** on provider calls — a slow provider could tie up threads.
7. **No rate limiting** — a burst could overwhelm the provider.
8. **No authentication/authorization** — endpoints are open.
9. **No payment reversal/cancellation API** (VOIDED/REFUNDED states exist but aren't reachable).
10. **Recovery doesn't call the provider** — it transitions to PROCESSING but doesn't re-submit.
11. **No refund/chargeback handling** — all deferred."

**Important technical details:**
- `docs/stage-5-status.md:26-32` — compile errors
- `docs/stage-5-status.md:71,103-105` — `@EnableScheduling` missing
- `docs/stage-5-status.md:94-98` — `ApplicationContextTest` broken
- `docs/architecture.md:547-555` — point-of-failure matrix

**Likely follow-up:** "Which gap is most critical?"
**Answer:** "The compile errors — fix those first. Then `@EnableScheduling`,
because without it, the system silently doesn't do recovery or event publishing
in a running instance."

**Common weak answer to avoid:** "It's production-ready" (it has multiple
known issues that would be caught in code review).

---

### Question 39: How would you investigate a stuck PROCESSING payment?

**What the interviewer is testing:** Debugging methodology and operational skills.

**Strong answer:** "Step by step:

1. **Check the payment row:**
   ```sql
   SELECT payment_id, merchant_id, bill_ref, status, created_at, updated_at,
          attempt_count, provider_idempotency_key, provider_reference,
          failure_code, failure_reason, next_retry_at
   FROM payment WHERE payment_id = 'xxx';
   ```
   - If `attempt_count > 0`: recovery has tried before.
   - If `provider_idempotency_key IS NULL`: the key was never set (bug).
   - If `created_at` is old: the payment has been stuck for a while.

2. **Check idempotency:**
   ```sql
   SELECT * FROM idempotency WHERE payment_id = 'xxx';
   ```
   - If `is_terminal=false`: the payment never finalized idempotency (crashed
     after TX1 but before TX2).

3. **Check outbox events:**
   ```sql
   SELECT event_type, event_order, status, created_at FROM outbox
   WHERE aggregate_id = 'xxx' ORDER BY event_order;
   ```
   - Should see PaymentCreated → PaymentProcessingStarted.
   - If no ProcessingStarted: TX2 never ran (crash between TX1 and TX2).
   - If ProcessingStarted but no result event: provider call or TX2 failed.

4. **Check provider logs:** Use `provider_idempotency_key` to query the provider's
   API for the transaction status. If the provider shows SUCCESS but the
   gateway shows PROCESSING, the gateway's TX2 crashed after the provider
   responded but before committing.

5. **Recovery action:** If the provider confirms success but the gateway is
   stuck in PROCESSING, manually trigger recovery or apply the result via
   a reconciliation job."

**Important technical details:**
- `Recover
yProperties.java` — `processingTimeoutMs = 300000L` (5 min)
- `PaymentRepository.findForRecovery()` — includes PROCESSING in the recovery query

**Likely follow-up:** "How do you monitor for stuck payments?"
**Answer:** "We have `countAwaitingProcessing()` and `countAwaitingReconciliation()`
metrics. If `countAwaitingProcessing` is non-zero for more than
`processingTimeoutMs` (5 min), alert. The recovery scheduler picks these up,
but with `@EnableScheduling` missing, they won't actually be processed until
that's fixed."

**Common weak answer to avoid:** "I check the payment status" (without
explaining the full diagnostic path across idempotency, outbox, and provider).

---

## O. Behavioral / Project Ownership Questions

### Question 40: Tell me about a technical decision you made on this project and why you made it.

**What the interviewer is testing:** Engineering judgment and decision-making.

**Strong answer:** "The decision to use `TransactionTemplate` for two-phase
transactions instead of a single `@Transactional` method. The challenge: we need
the provider call to happen *between* the creation transaction and the result
transaction. With `@Transactional`, the entire method would be one transaction —
we'd hold a database lock during the provider HTTP call, risking timeouts and
deadlocks.

With `TransactionTemplate`, TX1 creates the payment and commits immediately.
The provider call happens outside any transaction. TX2 then locks the payment,
applies the result, and commits. This pattern:
1. Minimizes lock hold time
2. Allows immediate client acknowledgment that the payment was created
3. Decouples the slow provider call from DB transactions

The trade-off is more code complexity, but the safety and performance benefits
are worth it for a financial system."

**Important technical details:**
- `ChargeService.java:80` — `TransactionTemplate` injection
- `ChargeService.java:147` — TX1
- `ChargeService.java:210` — TX2

**Likely follow-up:** "Could you have used event-driven architecture instead?"
**Answer:** "Yes — an event-driven approach where TX1 publishes a 'PaymentCreated'
event, a separate service calls the provider, and publishes a 'ProviderResult'
event that triggers TX2. But for synchronous payment processing (the current
design), the two-phase approach is simpler and gives the client an immediate
response."

**Common weak answer to avoid:** "I used TransactionTemplate because it was
the pattern suggested" (should explain the reasoning).

---

### Question 41: What would you do differently if you started this project over?

**What the interviewer is testing:** Retrospective thinking and learning.

**Strong answer:** "Three things:
1. **Fix the compile errors first.** The Stage 5 handoff left the build broken.
   I'd ensure the build is green before any handoff, with CI gates.
2. **Add `@EnableScheduling` immediately.** The missing annotation means
   recovery and event publishing silently don't work in a running app.
   I'd add it and the `RecoveryScheduler` test first, even if Kafka isn't ready.
3. **Wire the recovery-to-provider loop.** Currently, recovery re-enters
   PROCESSING but doesn't call `processor.process()`. I'd make recovery the
   authoritative retry mechanism, calling the provider with the stable
   idempotency key. The current gap means stuck UNKNOWN payments never get
   resolved by recovery alone.

The fundamental design (two-phase transactions, idempotency via DB constraint,
UNKNOWN state, outbox pattern) is sound — I wouldn't change those."

**Important technical details:**
- `docs/stage-5-status.md:71` — `@EnableScheduling` missing
- `docs/stage-5-status.md:26-32` — compile errors
- `PaymentRecoveryService.java:124-215` — recovery doesn't call processor

**Likely follow-up:** "What's the most important thing you learned?"
**Answer:** "That in financial systems, 'correct' is better than 'fast'. The
UNKNOWN state, the idempotency key, and the two-phase transaction all add
complexity — but they prevent the catastrophic failure mode of double-charging.
I'd rather have a system that's slow but never loses money."

**Common weak answer to avoid:** "I would have used a different framework"
(without addressing the domain-specific decisions).

---

## Question Count: 41 questions

All questions reference actual classes, methods, and line numbers from the repository.
Implementation status is accurately reflected for each topic.