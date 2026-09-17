# Project Concepts — Payment Gateway & Settlement Core Engine

> **Status legend used throughout this document:**
> - **IMPLEMENTED** — Present in code and compiles/runs.
> - **PARTIALLY IMPLEMENTED** — Code exists but is incomplete, or blocked by a known issue.
> - **DESIGNED BUT NOT IMPLEMENTED** — Documented in design but no code (or stub only).
> - **DEFERRED** — Explicitly out of scope for the current stage.
> - **UNKNOWN / NEEDS VERIFICATION** — Not confirmed by code inspection.

---

## A. Java and Backend Fundamentals

### A.1 Java 21 Features Actually Used

**What it is:** Java 21 is the target LTS release. The project uses compiler release 21 (`maven.compiler.release=21`).

**Why we need it:** Virtual threads (a Java 19+ preview stabilized in 21) allow the application to handle thousands of concurrent blocking I/O operations (PostgreSQL, Redis, provider HTTP) with a small carrier-thread pool. Pattern matching for `instanceof` (Java 16+) simplifies the idempotency outcome branching.

**How it works:**
- Virtual threads: Enabled via `spring.threads.virtual.enabled=true` in `application.yml` (line 10). Spring Boot 3.3 auto-configures a `TaskExecutor` that spawns virtual threads for request handling.
- Pattern matching: Used in `PaymentController.createPayment` (lines 103–116): `if (outcome instanceof IdempotencyOutcome.ReplayOutcome replay)` — this is Java 16+ pattern matching for `instanceof`, available in Java 21.
- Records: `ProviderResult` (line 18), `ChargeResult` (line 5), `PaymentLifecycleEvent` (line 8), `IdempotencyOutcome.ReplayOutcome` (line 23) are all Java 16+ records.
- Switch expressions: `PaymentStateEngine.transition` uses a switch expression with `->` syntax returning a boolean (line 83-96).

**Where it appears:**
- `application.yml:10` — `spring.threads.virtual.enabled: true`
- `PaymentController.java:103` — `instanceof` pattern matching
- `ProviderResult.java:18` — record declaration
- `ChargeResult.java:5` — record declaration
- `PaymentLifecycleEvent.java:8` — record declaration
- `PaymentStatus.java:57` — switch expression with `->`
- `PaymentStateEngine.java:83` — switch expression

**Example:**
```java
// Virtual threads enabled — blocking I/O multiplexed onto few carriers
// application.yml:
spring:
  threads:
    virtual:
      enabled: true

// Pattern matching (PaymentController.java:103)
if (outcome instanceof IdempotencyOutcome.ReplayOutcome replay) {
    Payment payment = chargeService.getPayment(UUID.fromString(replay.paymentId()));
    ...
}

// Switch expression (PaymentStatus.java:57)
public boolean isTerminal() {
    return switch (this) {
        case SUCCEEDED, FAILED, VOIDED, REFUNDED -> true;
        default -> false;
    };
}
```

**Common failure scenario:** Virtual threads are JVM-managed, not OS-thread-managed. Under extreme CPU contention (>10k concurrent virtual threads each doing CPU-bound work), scheduler overhead increases. However, for a payment gateway whose ceiling is I/O-bound provider calls, the trade-off is favorable.

**Interview explanation:** "Java 21 gives us virtual threads for free I/O concurrency and records + pattern matching for concise, type-safe domain objects. We use `RELEASE` 21 in the compiler and enable virtual threads via Spring Boot's `spring.threads.virtual.enabled` flag."

**Common interviewer follow-up questions:**
- *Can virtual threads cause issues with thread-locals?* — Yes, MDC propagation requires `ThreadLocal` context to be explicitly bound to carrier threads. Spring Boot's `MDCTaskDecorator` or a custom `ThreadFactory` is needed for log correlation across async boundaries.
- *What's the difference between a virtual thread and a platform thread?* — Platform threads are OS threads (1:1 mapping); virtual threads are JVM-managed fibers (M:N mapping), scheduled by the JVM onto a small pool of carrier threads.

---

### A.2 Records, Enums, Immutability, Value Objects

**What it is:** Records are immutable data carriers (Java 16+). Value objects are objects defined by their structural equality rather than identity.

**Why we need it:** In a payment domain, immutability prevents accidental state mutation across thread boundaries. Value objects like `Money`, `Currency`, `ProviderResult`, and `PaymentLifecycleEvent` are inherently immutable.

**How it works:** A Java record generates `equals()`, `hashCode()`, `toString()`, and accessors automatically. The domain `Payment` aggregate is *not* a record (it has mutable state fields like `status`, `providerReference`), but it delegates to immutable value objects.

**Where it appears:**
- `ProviderResult.java:18` — `public record ProviderResult(Type type, String providerReference, String failureCode, String failureReason)`
- `ChargeResult.java:5` — `public record ChargeResult(Payment payment, boolean replayed, int responseStatus)`
- `PaymentLifecycleEvent.java:8` — 18-field record representing the Kafka event envelope
- `IdempotencyOutcome.ReplayOutcome` (lines 23–41) — inner record-style class
- `Currency.java` — enum with scale per currency
- `PaymentStatus.java` — enum with `isTerminal()` and `isAwaitingResolution()` methods
- `PaymentEventType.java` — enum with `wireValue` and `version`
- `PaymentMethodType.java` — enum
- `PasswordId.java` — value object wrapping UUID
- `IdempotencyKey.java` — immutable value object with validation

**Example:**
```java
// ProviderResult.java — immutable record
public record ProviderResult(
        Type type,
        String providerReference,
        String failureCode,
        String failureReason) {

    public enum Type {
        SUCCESS, DECLINED, TECHNICAL_FAILURE, UNKNOWN
    }
...
}
```

**Common failure scenario:** If a record is used where mutation is expected, the compiler will reject mutation (records are final). In the `Payment` aggregate, mutable fields are explicitly marked as non-final and updated via methods like `markProcessing()`, `applyProviderResult()`.

**Interview explanation:** "We use records for all immutable domain objects — `ProviderResult`, `PaymentLifecycleEvent`, `ChargeResult` — because they are data carriers that never change. The `Payment` aggregate itself is a mutable class (not a record) because its status transitions over its lifecycle. Value objects like `Money` and `Currency` enforce invariants at construction time."

**Common interviewer follow-up questions:**
- *Why isn't Payment a record?* — Because it has mutable lifecycle state (`status`, `providerReference`, `failureCode`, retry metadata). Records are for immutable data. The `Payment` class uses private mutable fields with controlled mutation methods (`markProcessing()`, `applyProviderResult()`).
- *How do you enforce validation in value objects?* — In constructors and factory methods. `Money.of("10.001", INR)` throws because scale doesn't match currency. `IdempotencyKey.of("")` throws because it's blank.

---

### A.3 BigDecimal and Monetary Precision

**What it is:** `BigDecimal` provides arbitrary-precision decimal arithmetic, required for financial calculations.

**Why we need it:** Floating-point types (`double`, `float`) cannot represent decimal fractions exactly. `0.1 + 0.2 != 0.3` in IEEE 754. For payment amounts, this rounding error is unacceptable.

**How it works:** The `Money` value object wraps a `String` amount and a `Currency` enum. `toMinorUnits()` converts to a `long` (e.g., ₹1,250.00 → 125,000 paise). The database stores minor units as `BIGINT` (or `DECIMAL(18,2)` with an integer constraint enforced by application code). `longValueExact()` is used on read to fail loudly on fractional minor-unit values.

**Where it appears:**
- `Money.java:18` — `BigDecimal amount` field
- `Money.toMinorUnits()` — scales by currency and returns `long`
- `Money.fromMinorUnits(long, Currency)` — reconstructs from minor units
- `PaymentEntity.fromDomain()` line 135 — `BigDecimal.valueOf(payment.getAmount().toMinorUnits())`
- `PaymentEntity.toDomain()` line 186 — validates integer minor-unit, uses `longValueExact()`
- `Currency.java` — enum with `scale` and `isSupported()`

**Example:**
```java
// Money.java
public long toMinorUnits() {
    return amount.multiply(BigDecimal.valueOf(10L).pow(currency.scale()))
                 .longValueExact();  // fails on overflow
}
```
Database: `amount_minor BIGINT NOT NULL CHECK (amount_minor > 0)` — stored as paise/cents.

**Common failure scenario:** If someone uses `double` for amounts, `0.1` is stored as `0.10000000000000000555...`. The `Money` class prevents this entirely by accepting only `String` and `BigDecimal`, never `double`.

**Interview explanation:** "We use `BigDecimal` for all monetary amounts in the domain. Amounts are validated for currency-appropriate scale at construction. On persistence, `toMinorUnits()` converts to a `long` integer count (e.g., 125,000 paise for ₹1,250.00), stored in PostgreSQL as a `BIGINT`. On read, `longValueExact()` fails loudly if the stored value is fractional — catching data corruption rather than silently truncating."

**Common interviewer follow-up questions:**
- *Why store minor units as BIGINT instead of DECIMAL?* — Minor units are always integers (paise, cents). A `BIGINT` is cheaper to store, index, and compare. The `DECIMAL(18,2)` on the entity is a cosmetic column width; the application layer enforces that the value is an exact integer.
- *What about currency conversion?* — Not implemented. `Currency` enum supports INR (scale 2), JPY (scale 0), BHD (scale 3), etc., but conversion logic is deferred.

---

### A.4 Checked vs Unchecked Exceptions

**What it is:** Java distinguishes between checked exceptions (must be declared or caught) and unchecked exceptions (runtime).

**Why we need it:** The project consistently uses unchecked exceptions for domain logic errors and infrastructure errors, avoiding checked-exception boilerplate in service-layer signatures.

**How it works:** All custom exceptions extend `RuntimeException`. `IllegalStateTransitionException` (extends `RuntimeException`) is thrown by `PaymentStateEngine.transition`. `EntityNotFoundException` (Jakarta Persistence) is thrown when a payment isn't found. `IdempotencyKeyConflictException` extends `RuntimeException`.

**Where it appears:**
- `IllegalStateTransitionException.java` — extends `RuntimeException`
- `IdempotencyKeyConflictException.java` — extends `RuntimeException`
- `PaymentGatewayExceptionHandler.java` — centralized exception-to-HTTP-status mapping
- `ChargeService.java:200` — catches `Exception` from `processor.process()` and converts to `ProviderResult.technicalFailure()`

**Example:**
```java
// IllegalStateTransitionException.java
public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(String message, PaymentStatus current, PaymentStatus target) {
        super(message);
        ...
    }
}

// ChargeService.java:200
try {
    providerResult = processor.process(...);
} catch (Exception e) {
    log.error("Provider call failed for payment {}: {}", paymentId, e.getMessage(), e);
    providerResult = ProviderResult.technicalFailure("PROVIDER_EXCEPTION", ...);
}
```

**Common failure scenario:** If the state machine threw a checked exception, every method in the call chain (`charge` → `applyProviderResult` → `Payment.markProcessing()`) would need `throws` declarations, bloating signatures. Using unchecked exceptions keeps the domain clean while still surfacing errors.

**Interview explanation:** "We use unchecked exceptions throughout — `IllegalStateTransitionException`, `IdempotencyKeyConflictException`, and infrastructure exceptions like `EntityNotFoundException`. This avoids checked-exception boilerplate in service signatures. The centralized `PaymentGatewayExceptionHandler` translates these into appropriate HTTP responses."

**Common interviewer follow-up questions:**
- *Why not propagate the provider exception up?* — Because a provider timeout or connection error is a *technical failure*, not necessarily a payment failure. The gateway catches it, converts it to `ProviderResult.technicalFailure()`, which maps to `FAILED` status (safe because no money moved). A timeout maps to `UNKNOWN` (not `FAILED`) because the provider may have debited.

---

### A.5 Thread Safety

**What it is:** Ensuring that shared mutable state is correctly synchronized under concurrent access.

**Why we need it:** The gateway handles concurrent requests (duplicate requests, concurrent idempotency attempts, recovery sweeps). Payment state must never be corrupted by a race condition.

**How it works:**
1. **Database-level serialization:** The PostgreSQL unique constraint on `(merchant_id, idempotency_key)` serializes concurrent duplicate requests at the database level — not at the Java level. `SELECT FOR UPDATE` locks the idempotency row during reservation.
2. **Entity optimistic locking:** `@Version` on `PaymentEntity` prevents lost updates. If two threads try to update the same payment, one gets `ObjectOptimisticLockingFailureException`.
3. **Pessimistic locking:** `PaymentRepository.findAndLockByPaymentId()` uses `@Lock(PESSIMISTIC_WRITE)` which issues `SELECT ... FOR UPDATE`.
4. **In-process safety:** `SimulatedPaymentProcessor` uses `ConcurrentHashMap` for its provider idempotency cache. This is safe for concurrent access.

**Where it appears:**
- `PaymentEntity.java:117` — `@Version` annotation
- `PaymentRepository.java:39` — `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `findAndLockByPaymentId`
- `ChargeService.java:254-268` — retry loop on `ObjectOptimisticLockingFailureException`
- `SimulatedPaymentProcessor.java:55` — `ConcurrentHashMap` for idempotency cache
- `IdempotencyRepository.java:34` — `@Lock(PESSIMISTIC_WRITE)` on `lockByKey`

**Example:**
```java
// PaymentRepository.java:39
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<PaymentEntity> findAndLockByPaymentId(UUID paymentId);

// ChargeService.java:323
} catch (ObjectOptimisticLockingFailureException e) {
    if (attempt == 3) throw e;
    // retry with backoff
}
```

**Common failure scenario:** Without `@Version`, two concurrent transactions could both read `version=5`, apply different transitions, and the second commit would silently overwrite the first (lost update). With `@Version`, the second commit fails because the version in the `WHERE` clause no longer matches.

**Interview explanation:** "Thread safety is achieved through the database, not Java synchronized blocks. The idempotency table's unique constraint serializes concurrent duplicates at the DB level. The `@Version` column provides optimistic locking on payment updates. `SELECT FOR UPDATE` provides pessimistic locking for recovery. The only in-memory concurrency is the `ConcurrentHashMap` in `SimulatedPaymentProcessor`'s idempotency cache."

**Common interviewer follow-up questions:**
- *Why not use `synchronized` on the service method?* — Because `synchronized` only works within a single JVM. In a multi-instance deployment, two requests hitting different instances would both enter the method. Database-level locking is the correct tool for distributed concurrency.
- *Why use both optimistic and pessimistic locking?* — Optimistic locking (`@Version`) catches lost updates after the fact with a fast retry. Pessimistic locking (`SELECT FOR UPDATE`) prevents concurrent access during a critical section (e.g., applying a provider result). They serve different purposes.

---

### A.6 Transactions

**What it is:** Database transactions provide ACID properties (Atomicity, Consistency, Isolation, Durability).

**Why we need it:** Payment operations must be atomic — either the payment is fully created AND the idempotency is reserved, or neither happens. Partial state would lead to double-charging or lost idempotency.

**How it works:** The `ChargeService` uses `TransactionTemplate` for explicit two-phase transactions:
- **TX1 (creation):** Persist `PaymentEntity` in `CREATED` state + `OutboxEventEntity` for `PaymentCreated` + reserve idempotency key. All in one transaction. If it rolls back, all three roll back together.
- **Provider call (outside transaction):** No DB lock held during the HTTP round-trip.
- **TX2 (apply result):** Lock the payment, transition status, append result outbox events, finalize idempotency. All in one transaction.

**Where it appears:**
- `ChargeService.java:80` — `TransactionTemplate transactionTemplate` constructor injection
- `ChargeService.java:147` — `transactionTemplate.execute(status -> {...})` for TX1
- `ChargeService.java:210` — `transactionTemplate.execute(status -> {...})` for TX2
- `ChargeService.java:345` — `@Transactional(readOnly = true)` on `getPayment`
- `PaymentRecoveryService.java:70` — `@Transactional` on `sweep`
- `PaymentController.java:117` — catches unique-violation as a transaction boundary signal

**Example:**
```java
// ChargeService.java:147
payment = transactionTemplate.execute(status -> {
    Payment created = Payment.create(typedPaymentId, ...);
    created.ensureProviderIdempotencyKey();
    PaymentEntity entity = PaymentEntity.fromDomain(created);
    paymentRepository.saveAndFlush(entity);   // (1) persist payment
    OutboxEventEntity createdEvent = outboxEventService.append(...);  // (2) append outbox
    IdempotencyOutcome outcome = idempotencyService.reserve(...);     // (3) reserve key
    return created;
});
```

**Common failure scenario:** If the provider call inside TX1 succeeded but TX2 rolls back (e.g., database deadlock), the payment remains in `CREATED` and the recovery scheduler will retry it. The idempotency key is already reserved, so a client retry is safe (it replays the original).

**Interview explanation:** "We use `TransactionTemplate` for explicit control over two transaction boundaries. TX1 creates the payment and reserves the idempotency key atomically — if either fails, both roll back. TX2 applies the provider result with a `SELECT FOR UPDATE` lock. The provider call happens *outside* both transactions to avoid holding locks during slow I/O."

**Common interviewer follow-up questions:**
- *Why not use `@Transactional` annotation instead of `TransactionTemplate`?* — Because we need fine-grained control over the two-phase boundaries. The provider call must happen *between* TX1 and TX2, which is impossible with a single `@Transactional` method.
- *What happens to the outbox event if the transaction rolls back?* — It rolls back with the transaction. The test `paymentRollbackAlsoRollsBackOutboxInsertion` verifies this.

---

### A.7 Concurrency

**What it is:** Multiple threads/requests accessing shared resources simultaneously.

**Why we need it:** The gateway must handle concurrent requests — duplicate sends, concurrent idempotency attempts, recovery sweeps running while payments are being processed.

**How it works:**
1. **Concurrent duplicate requests:** Two requests with the same `Idempotency-Key` arrive simultaneously. Both attempt to insert into the `idempotency` table. The `SELECT FOR UPDATE` lock serializes them — the first inserts, the second sees the existing row.
2. **Concurrent payments for different keys:** No contention — different rows, different locks.
3. **Recovery vs live traffic:** Recovery locks the payment row with `FOR UPDATE`. If a live request is processing the same payment, recovery waits for the lock.
4. **Outbox publisher concurrency:** Multiple `OutboxPublisher` workers use `FOR UPDATE SKIP LOCKED` in the `findDue` query. Each worker claims a row via a CAS on `lock_owner`.

**Where it appears:**
- `IdempotencyRepository.lockByKey()` — `@Lock(PESSIMISTIC_WRITE)`
- `PaymentRepository.findAndLockByPaymentId()` — `@Lock(PESSIMISTIC_WRITE)`
- `OutboxEventRepository.findDue()` — native query with `FOR UPDATE SKIP LOCKED`
- `OutboxPublisher.publishOne()` — `claim()` with CAS on `lock_owner`
- `RecoveryScheduler.runRecoverySweep()` — single-threaded (`fixedDelay`)

**Example:**
```sql
-- OutboxEventRepository.findDue() — native query
SELECT o.* FROM outbox o
WHERE o.status = 'PENDING'
  AND o.next_attempt_at <= :now
  AND NOT EXISTS (
      SELECT 1 FROM outbox earlier
      WHERE earlier.aggregate_id = o.aggregate_id
        AND earlier.event_order < o.event_order
        AND earlier.status = 'PENDING'
  )
ORDER BY o.event_order ASC, o.created_at ASC, o.id ASC
FOR UPDATE SKIP LOCKED
```

**Common failure scenario:** Without `SKIP LOCKED`, concurrent workers would block on each other's locks, serializing all publishing. With `SKIP LOCKED`, each worker immediately picks the next available unlocked row.

**Interview explanation:** "Concurrency is managed at four levels: (1) the idempotency table's unique constraint serializes duplicate requests at the DB level, (2) `@Version` optimistic locking catches lost updates, (3) `SELECT FOR UPDATE` provides pessimistic locking for state transitions, and (4) `FOR UPDATE SKIP LOCKED` allows parallel outbox publishing without blocking."

**Common interviewer follow-up questions:**
- *What happens if two recovery sweeps run simultaneously?* — They'd process different payments (each locks its own row via `FOR UPDATE`). The `fixedDelay` configuration on `RecoveryScheduler` prevents overlapping sweeps.
- *How do you handle the outbox ordering problem?* — The `findDue` query uses a `NOT EXISTS` subquery to skip a payment's later events when an earlier event for the same payment is still pending. This guarantees per-payment ordering even with multiple workers.

---

### A.8 Dependency Injection

**What it is:** Spring's DI container manages bean lifecycles and injects dependencies.

**Why we need it:** Decouples the domain (`Payment`) from infrastructure (JPA, Kafka), allows mocking in tests (`@MockBean`), and enables profile-based configuration.

**How it works:** Spring Boot's component scanning discovers `@Service`, `@Repository`, `@Component`, `@Configuration` annotations. Constructor injection is the preferred pattern (no `@Autowired` needed on constructors in Spring Boot 3.3+ when there's a single constructor).

**Where it appears:**
- `ChargeService.java:71-92` — `@Service` with 5 constructor-injected dependencies
- `PaymentController.java:44-57` — `@RestController` with `ChargeService` and `IdempotencyService` injected
- `SimulatedPaymentProcessor.java:49` — `@Component`
- `KafkaOutboxConfiguration.java:23` — `@Configuration` with `@Bean` methods
- `PaymentOutboxTransactionIntegrationTest.java:79` — `@MockBean` for `PaymentProcessor`

**Example:**
```java
@Service
public class ChargeService {
    private final PaymentRepository paymentRepository;
    private final PaymentProcessor processor;
    private final IdempotencyService idempotencyService;
    private final OutboxEventService outboxEventService;
    private final TransactionTemplate transactionTemplate;

    public ChargeService(PaymentRepository paymentRepository,
                         PaymentProcessor processor,
                         IdempotencyService idempotencyService,
                         OutboxEventService outboxEventService,
                         PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.processor = processor;
        ...
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }
}
```

**Common failure scenario:** If a dependency is missing (e.g., `IdempotencyRepository` not available when JPA is excluded), Spring fails fast at startup with `NoSuchBeanDefinitionException`. The `ApplicationContextTest` currently fails for this reason (see Known Issues).

**Interview explanation:** "We follow constructor injection throughout — it makes dependencies explicit and enables easy testing with `@MockBean`. The `PaymentProcessor` interface is a port (clean architecture), implemented by `SimulatedPaymentProcessor` in the infrastructure layer. The domain (`Payment`, `PaymentStateEngine`) has zero framework dependencies."

**Common interviewer follow-up questions:**
- *Why is `TransactionTemplate` injected via constructor rather than `@Transactional`?* — For the two-phase transaction model. We need to explicitly control where TX1 ends and TX2 begins, with the provider call outside both.

---

## B. Spring Boot

### B.1 Application Structure

**What it is:** The project follows Hexagonal (Ports and Adapters) / Clean Architecture, layered from domain to infrastructure.

**Why we need it:** Separates business logic from framework concerns. The domain can be unit-tested without Spring or a database.

**How it works:**
```
com.paymentgateway.settlement
├── PaymentGatewaySettlementApplication.java    # Entry point
├── api/                                          # REST boundaries
│   ├── controller/                               # PaymentController, InternalHealthController
│   └── dto/                                      # CreatePaymentRequest, PaymentResponse, etc.
├── domain/                                       # Pure domain (no framework deps)
│   ├── payment/                                  # Payment, Money, PaymentStatus, etc.
│   ├── idempotency/                              # IdempotencyKey, IdempotencyKeyConflictException
│   └── event/                                    # PaymentEventType, PaymentLifecycleEvent
├── application/                                  # Use cases & orchestration
│   ├── port/                                     # PaymentProcessor, IdempotencyService, IdempotencyOutcome
│   └── service/                                 # ChargeService, PaymentRecoveryService, OutboxEventService
├── infrastructure/                               # Adapters
│   ├── persistence/                              # Entities, repositories, Flyway
│   ├── messaging/                                # Kafka, OutboxPublisher, OutboxMetrics
│   ├── external/provider/                        # SimulatedPaymentProcessor
│   ├── scheduling/                               # RecoveryScheduler
│   └── idempotency/                              # PostgresIdempotencyStore
├── config/                                       # OutboxProperties, RecoveryProperties, etc.
├── observability/                                # CorrelationIdFilter, RequestLoggingFilter
└── common/                                       # PaymentGatewayExceptionHandler
```

**Where it appears:** `docs/architecture.md:91-122` documents the package structure.

**Example:** The `Payment` aggregate in `domain/payment/` has zero Spring imports. `ChargeService` in `application/service/` is the orchestration layer that wires everything together.

**Common failure scenario:** If infrastructure classes leak into the domain (e.g., `@Entity` on a domain object), the domain becomes untestable without Spring. The current codebase avoids this by keeping `PaymentEntity` in infrastructure and mapping to/from the domain `Payment`.

**Interview explanation:** "We use a hexagonal architecture: domain is pure Java with no framework dependencies, application layer orchestrates workflows with `@Transactional`, infrastructure adapts to Spring Data JPA, Kafka, and the simulated provider. This makes the state machine and `Money` math trivially unit-testable."

**Common interviewer follow-up questions:**
- *How would you split this monolith into microservices?* — The bounded contexts (payment, ledger, reconciliation, outbox) map to microservice boundaries. Only package relocations needed; domain interfaces remain stable.

---

### B.2 Spring Boot Configuration Properties

**What it is:** Type-safe configuration bound to YAML properties.

**Why we need it:** Externalizes environment-specific settings (timeouts, retry limits) without code changes.

**How it works:** `@ConfigurationProperties(prefix="app.recovery")` binds to `RecoveryProperties`. `@ConfigurationProperties(prefix="app.outbox")` binds to `OutboxProperties`.

**Where it appears:**
- `RecoveryProperties.java:14-15` — `@Component @ConfigurationProperties(prefix = "app.recovery")`
- `OutboxProperties.java` — `@ConfigurationProperties(prefix = "app.outbox")`
- `application.yml:56-73` — `app:` section with `outbox` and `erp` config

**Example:**
```yaml
app:
  outbox:
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

**Common failure scenario:** If `app.outbox.enabled` is set to `false`, the `OutboxPublisher` bean is conditionally created (`@ConditionalOnProperty`). Payments still commit; outbox rows are still written; the publisher just doesn't run.

**Interview explanation:** "All operational tunables are in `application.yml` under the `app` namespace. `RecoveryProperties` controls retry limits and timeouts; `OutboxProperties` controls Kafka polling and backoff. Both use `@ConfigurationProperties` for type safety."

**Common interviewer follow-up questions:**
- *How do you handle config changes in production without restart?* — Not implemented. Could be added with Spring Cloud Config or `@RefreshScope`, but this is deferred.

---

### B.3 Profiles

**What it is:** Environment-specific configuration profiles (`local`, `test`).

**Why we need it:** Local development uses Docker Compose (hardcoded ports); tests use Testcontainers (dynamic ports).

**How it works:** Spring Boot profile precedence: command-line args > env vars > `application-{profile}.yml` > `application.yml` > compiled defaults.

**Where it appears:**
- `application.yml` — shared defaults (port 8080, virtual threads, logging)
- `application-local.yml` (DEFERRED — not yet confirmed in working tree)
- `application-test.yml` — modified in current session (see `git diff`)
- `docs/local-development.md:122-132` — profile precedence table

**Example:** Tests use `@SpringBootTest(properties = {"app.outbox.enabled=false"})` to override settings per-test.

**Common failure scenario:** The `ApplicationContextTest` fails to load because it excludes `DataSourceAutoConfiguration` (to avoid needing a DB), but `PostgresIdempotencyStore` requires `IdempotencyRepository` which depends on JPA being available. See **Known Issues**.

**Interview explanation:** "We have `local` (Docker Compose) and `test` (Testcontainers) profiles. The `application.yml` has shared defaults; profile-specific overrides handle connection strings and ports."

**Common interviewer follow-up questions:**
- *How do Testcontainers tests work without hardcoded ports?* — `@ServiceConnection` on the container dynamically injects the JDBC URL and Kafka bootstrap server into Spring's datasource/Kafka configs. No hardcoded ports.

---

### B.4 Validation

**What it is:** Bean Validation (Jakarta Validation) for request body validation.

**Why we need it:** Rejects malformed requests before they reach the business logic.

**How it works:** `@Valid` on the controller method parameter triggers validation. Annotations like `@NotBlank`, `@Size`, `@Pattern` on `CreatePaymentRequest` fields. Violations are caught by `PaymentGatewayExceptionHandler` and returned as `400 VALIDATION_FAILED`.

**Where it appears:**
- `CreatePaymentRequest.java` — `@NotBlank`, `@Pattern`, `@Size` annotations
- `PaymentController.java:76` — `@Valid @RequestBody`
- `PaymentGatewayExceptionHandler.java` — handles `MethodArgumentNotValidException`

**Example:**
```java
public record CreatePaymentRequest(
    @NotBlank(message = "merchantId must not be blank")
    @Size(max = 255, message = "merchantId must be <= 255 chars")
    String merchantId, ...
)
```

**Common failure scenario:** A request with `amount: "-5.00"` triggers `IllegalArgumentException` in `Money.of`, which is caught by `PaymentGatewayExceptionHandler` and returned as `400 INVALID_REQUEST`.

**Interview explanation:** "Request validation uses Bean Validation 3.0 (`@Valid`, `@NotBlank`, `@Pattern`). Validation failures return structured `400` responses. Business-rule validation (unsupported currency, negative amount) is done in the `Money`/`Currency` constructors and also returns `400`."

**Common interviewer follow-up questions:**
- *Why not validate in the controller directly?* — Separation of concerns. The DTO validates structural constraints (not blank, correct length); the domain validates semantic constraints (scale match, non-negative).

---

### B.5 Exception Handling

**What it is:** Centralized exception-to-HTTP-response mapping.

**Why we need it:** Ensures consistent error responses and never leaks stack traces or database internals.

**How it works:** `@RestControllerAdvice` on `PaymentGatewayExceptionHandler` intercepts exceptions thrown by controllers.

**Where it appears:**
- `PaymentGatewayExceptionHandler.java` — `@RestControllerAdvice`
- Handles: `EntityNotFoundException` → 404, `IdempotencyKeyConflictException` → 409, `IllegalStateTransitionException` → 409, `MethodArgumentNotValidException` → 400

**Example:**
```java
@ExceptionHandler(IdempotencyKeyConflictException.class)
public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyKeyConflictException e) {
    ApiError error = new ApiError(409, "IDEMPOTENCY_KEY_CONFLICT", ...);
    return ResponseEntity.status(409).body(error);
}
```

**Common failure scenario:** If an unexpected `RuntimeException` escapes, it returns `500 INTERNAL_ERROR` with a correlation ID but no stack trace.

**Interview explanation:** "All exceptions are handled by `PaymentGatewayExceptionHandler` using `@RestControllerAdvice`. The handler never exposes stack traces — responses include a correlation ID for log lookup. Database PSQL exceptions are caught and translated to business outcomes."

**Common interviewer follow-up:** *How do you handle database deadlock?* — In `PaymentRepository.findForRecovery`, a `PSQLException` with SQL state `40P01` (deadlock) is retried up to 3 times with jittered backoff. This is handled in the service layer, not the exception handler.

---

### B.6 Transaction Management

**Already covered in A.6.** See section A.6 above for the two-phase transaction model using `TransactionTemplate`.

---

### B.7 Scheduling

**What it is:** `@Scheduled` annotation for periodic background jobs.

**Why we need it:** Recovery and outbox publishing must run periodically even when no requests arrive.

**How it works:** `RecoveryScheduler.runRecoverySweep()` is annotated `@Scheduled(fixedDelay = 60000L, initialDelay = 30000L)`. `OutboxPublisher.publishDueEvents()` is annotated `@Scheduled`.

**Where it appears:**
- `RecoveryScheduler.java:53` — `@Scheduled(fixedDelay = 60000L, initialDelay = 30000L)`
- `OutboxPublisher.java:53` — `@Scheduled(fixedDelayString = "${app.outbox.polling-interval:1s}")`

**IMPLEMENTATION GAP:** `docs/stage-5-status.md:71` notes that `@EnableScheduling` is **NOT present anywhere** → scheduled methods do not auto-fire. This is a **KNOWN ISSUE**. Tests invoke publishers directly. See Known Issues section below.

**Interview explanation:** "Schedulers use `@Scheduled` — `RecoveryScheduler` runs every 60s with a 30s initial delay; `OutboxPublisher` polls every 1s (configurable). However, `@EnableScheduling` is missing from the main application class, so scheduled methods won't auto-fire until it's added. In tests, publishers are invoked directly."

---

### B.8 Actuator

**What it is:** Spring Boot Actuator endpoints for production monitoring.

**Why we need it:** Health checks, metrics, and info endpoints for observability.

**How it works:** `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` on port 8081.

**Where it appears:**
- `application.yml:26-44` — management endpoints config
- `InternalHealthController.java` — custom `/internal/health` endpoint
- Micrometer metrics in `OutboxMetrics.java` and `ErpConsumerMetrics.java`

**Example:** `GET /actuator/health` returns `{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}`.

**Common failure scenario:** If the database is down, `/actuator/health` returns `503` with component-level status.

**Interview explanation:** "Actuator is enabled on port 8081 (separate from the API on 8080) with `health`, `info`, `metrics`, and `prometheus` endpoints exposed. We also expose a custom `/internal/health` for K8s liveness/readiness probes."

---

### B.9 Logging and MDC

**What it is:** Structured logging with Mapped Diagnostic Context (MDC) for correlation.

**Why we need it:** Payment tracing across request boundaries, provider calls, and recovery sweeps.

**How it works:** `CorrelationIdFilter` generates/resolves the correlation ID and puts it in the MDC. `RequestLoggingFilter` logs request details with scrubbed sensitive fields.

**Where it appears:**
- `CorrelationIdFilter.java` — sets `correlationId` in MDC and request attributes
- `RequestLoggingFilter.java` — logs request method, URI, status; scrubs `paymentToken`, `password`, etc. from DEBUG body logging
- `application.yml:52-54` — log pattern includes `correlationId=%X{correlationId}`

**Example log line:**
```
2026-09-14 04:10:05.472 [quicke-worker-1] INFO  [traceId= correlationId= corr-001 paymentId= paymentId= merchantId=] o.s.w.s.DispatcherServlet - Completed 201
```

**Common failure scenario:** Without MDC cleanup, correlation IDs from one request leak into the next on the same thread. The filter uses an `OncePerRequestFilter` with `finally` block to clear MDC.

**Interview explanation:** "MDC is populated by `CorrelationIdFilter` with `correlationId`, `merchantId`, `paymentId` — all visible in every log line via the configured pattern. `RequestLoggingFilter` scrubs sensitive fields (`paymentToken`, `password`) from body logging, which is DEBUG-only."

---

## C. Payment Domain

### C.1 Payment Aggregate

**What it is:** The `Payment` class is the aggregate root — it owns the payment lifecycle and enforces invariants.

**Why we need it:** Ensures payment state transitions are valid and all mutations happen through the aggregate, preserving consistency.

**How it works:** The aggregate is created via `Payment.create()` (sets status to `CREATED`). Transitions are enforced by delegating to `PaymentStateEngine.transition()`. The aggregate has mutable fields (`status`, `providerReference`, `failureCode`, retry metadata) updated through controlled methods.

**Where it appears:**
- `Payment.java:37-416` — the aggregate
- `Payment.create()` (line 110) — factory method
- `Payment.reconstitute()` (line 374) — rebuilds from persistence (bypasses validation)
- `Payment.markProcessing()` (line 131) — `CREATED → PROCESSING`
- `Payment.applyProviderResult()` (line 149) — `PROCESSING → terminal/intermediate`
- `Payment.resolveReconciliation()` (line 245) — `UNKNOWN → SUCCEEDED|FAILED`
- `Payment.markRetrySubmitted()` (line 269) — `UNKNOWN → PROCESSING` (retry)
- `Payment.markRetryExhausted()` (line 285) — records exhaustion without status change
- `Payment.ensureProviderIdempotencyKey()` (line 218) — deterministic `prov_<paymentId>`

**IMPLMEMENTED status:** All of the above is implemented and unit-tested in `PaymentTest.java` and `PaymentStateEngineTest.java` (20 tests, 0 failures per `docs/payment-state-machine.md:143`).

**Example:**
```java
// Payment.java:149
public void applyProviderResult(final ProviderResult result) {
    if (this.status.isTerminal()) {
        if (this.providerReference == null && result.providerReference() != null) {
            this.providerReference = result.providerReference();
        }
        return;  // idempotent: no-op on already-terminal payment
    }
    PaymentStatus target = mapResultToStatus(result.type());
    PaymentStateEngine.TransitionReason reason = mapResultToReason(result.type());
    this.status = PaymentStateEngine.transition(this.status, target, reason);
    ...
}
```

**Common failure scenario:** Calling `applyProviderResult()` on a terminal payment is a safe no-op. Calling it on a `CREATED` payment (skipping `PROCESSING`) would throw `IllegalStateTransitionException` because `CREATED → SUCCEEDED` is not valid.

**Interview explanation:** "The `Payment` aggregate is the source of truth. It enforces the state machine internally via `PaymentStateEngine`. All mutations go through methods like `markProcessing()` and `applyProviderResult()`. `reconstitute()` bypasses validation because the payment has already been through the state machine under locks before persistence."

---

### C.2 Money Value Object

**IMPLEMENTED.** See A.3 above. `Money` wraps `BigDecimal` with a `Currency` enum. Scale is validated at construction. `toMinorUnits()` returns `long`. `fromMinorUnits(long, Currency)` reconstructs. Stored in DB as `DECIMAL(18,2)` with integer validation on read via `longValueExact()`.

---

### C.3 Currency

**IMPLEMENTED.** Enum in `Currency.java` with scales: INR/USD/EUR/GBP/AED (scale 2), JPY (scale 0), BHD/KWD/JOD/OMR (scale 3). `fromCode(String)` validates ISO-4217. `isSupported()` checks if a conversion path exists.

---

### C.4 Payment Lifecycle

**IMPLEMENTED.** The lifecycle is:
```
CREATED → PROCESSING → (SUCCEEDED | FAILED | UNKNOWN | REQUIRES_RECONCILIATION)
UNKNOWN/REQUIRES_RECONCILIATION → (SUCCEEDED | FAILED) via reconciliation
                                  → PROCESSING via retry (recovery)
```
Terminal: `SUCCEEDED`, `FAILED`, `VOIDED`, `REFUNDED` (VOIDED/REFUNDED are Stage 4+, not reachable from current code paths).

---

### C.5 State Machine

**IMPLEMENTED.** `PaymentStateEngine.transition()` (lines 61-105) uses an exhaustive `switch` to validate transitions. `TransitionReason` enum (lines 108-121) provides audit context. `PaymentStatus` enum (lines 31-67) has `isTerminal()` and `isAwaitingResolution()`.

**Valid transitions:**
- `CREATED → PROCESSING` (SUBMITTED_TO_PROVIDER)
- `PROCESSING → SUCCEEDED` (PROVIDER_SUCCESS)
- `PROCESSING → FAILED` (PROVIDER_DECLINED, PROVIDER_TECHNICAL_FAILURE)
- `PROCESSING → UNKNOWN` (PROVIDER_UNKNOWN_OUTCOME)
- `PROCESSING → REQUIRES_RECONCILIATION` (PROVIDER_TECHNICAL_FAILURE)
- `UNKNOWN → SUCCEEDED` (RECONCILIATION_MATCHED)
- `UNKNOWN → FAILED` (RECONCILIATION_FAILED)
- `UNKNOWN → PROCESSING` (RETRY_SUBMITTED)
- `REQUIRES_RECONCILIATION → SUCCEEDED` (RECONCILIATION_MATCHED)
- `REQUIRES_RECONCILIATION → FAILED` (RECONCILIATION_FAILED)
- `REQUIRES_RECONCILIATION → PROCESSING` (RETRY_SUBMITTED)

---

### C.6 Terminal States

**IMPLEMENTED.** `PaymentStatus.isTerminal()` returns `true` for `SUCCEEDED`, `FAILED`, `VOIDED`, `REFUNDED`. The state engine rejects any transition *from* a terminal state with `IllegalStateTransitionException("Cannot transition from terminal status ...")`.

---

### C.7 Unknown Payment Outcomes

**IMPLEMENTED.** When `ProviderResult.Type.UNKNOWN` is returned, the payment transitions to `UNKNOWN` status. The `reasonCode` is `PROVIDER_UNKNOWN_OUTCOME`. The `providerReference` remains null. The payment is non-terminal and eligible for recovery/retry.

---

### C.8 Provider Attempts

**PARTIALLY IMPLEMENTED.** `ProviderAttempt.java` is a record that binds `providerIdempotencyKey` to a specific attempt. However, it is a value object carried on the `Payment` aggregate — the authoritative copy lives in `payment.provider_idempotency_key` column. The `Payment.recordProviderAttempt()` method (line 191) increments `attemptCount` and records the result, but the `ProviderAttempt` record itself is not persisted as a separate table.

**Interview explanation:** "We track `attemptCount` on the payment row for retry budgeting. `ProviderAttempt` is a value object describing one attempt, but we don't persist individual attempt records — the aggregate itself holds the count."

---

### C.9 Retry Exhaustion

**IMPLEMENTED.** `Payment.markRetryExhausted(String reason)` (line 285) records the exhaustion reason in `lastFailureReason` without changing status. `PaymentRecoveryService.recoverPayment()` (line 134) checks `attemptCount >= maxRetries` and calls `markRetryExhausted()`, then appends a `PaymentRetryExhausted` outbox event. The payment remains in its current non-terminal state for manual investigation.

---

## D. Database and Persistence

### D.1 PostgreSQL

**IMPLEMENTED.** PostgreSQL 16 (per `docker-compose.yml:3`). Used as the financial source of truth.

---

### D.2 JPA/Hibernate

**IMPLEMENTED.** Spring Data JPA (`spring-boot-starter-data-jpa`). Entities use `@Entity`, `@Table`, `@Column`, `@Version`, `@Lock`. `ddl-auto: validate` in `application.yml:15` — schema is managed by Flyway, not Hibernate.

---

### D.3 Entities

**IMPLEMENTED.** `PaymentEntity.java`, `IdempotencyEntity.java`, `OutboxEventEntity.java` map to their respective tables.

---

### D.4 Repositories

**IMPLEMENTED.** Spring Data JPA interfaces:
- `PaymentRepository.java` — `findByPaymentId`, `findAndLockByPaymentId` (`@Lock(PESSIMISTIC_WRITE)`), `findByBillRefAndMerchantId`, `findForRecovery` (native JPQL query), count methods
- `IdempotencyRepository.java` — `lockByKey` (`@Lock(PESSIMISTIC_WRITE)`), `findByMerchantIdAndIdempotencyKey`, `insertReservation` (native INSERT)
- `OutboxEventRepository.java` — `findDue` (native query with `FOR UPDATE SKIP LOCKED`), `nextEventOrder`, `claim`, `markPublished`, `markRetryOrFailed`, `markDeadLettered`

---

### D.5 Transactions

**IMPLEMENTED.** See A.6.

---

### D.6 Isolation

**IMPLEMENTED.** PostgreSQL defaults to `READ_COMMITTED`. The project explicitly relies on this for `SELECT FOR UPDATE` row locks. No custom isolation level is set. `READ_COMMITTED` is documented in `docs/architecture.md:412`.

---

### D.7 Optimistic Locking

**IMPLEMENTED.** `@Version` on `PaymentEntity.version` (line 117). If two concurrent transactions update the same payment row, the second commit fails with `ObjectOptimisticLockingFailureException`, which `ChargeService.applyProviderResult()` retries up to 3 times (line 254-337).

---

### D.8 Pessimistic Locking

**IMPLEMENTED.** `@Lock(LockModeType.PESSIMISTIC_WRITE)` on:
- `PaymentRepository.findAndLockByPaymentId` (line 39) — locks a payment row during state transition
- `IdempotencyRepository.lockByKey` (line 34) — locks the idempotency row during reservation

These issue `SELECT ... FOR UPDATE` in PostgreSQL.

---

### D.9 SELECT FOR UPDATE

**IMPLEMENTED.** Used via JPA `@Lock(PESSIMISTIC_WRITE)` and via native queries in the outbox repository (`FOR UPDATE SKIP LOCKED`).

---

### D.10 Unique Constraints

**IMPLEMENTED.**
- `idempotency(merchant_id, idempotency_key)` — primary key, serializes concurrent requests
- `outbox(event_id) UNIQUE` / `uq_outbox_payment_order` on `(aggregate_id, event_order)`
- `uq_payment_provider_idempotency_key` on `provider_idempotency_key WHERE NOT NULL`
- `uq_payment_provider_ref UNIQUE (provider_ref) WHERE provider_ref IS NOT NULL`

---

### D.11 Indexes

**IMPLEMENTED.** See migrations V2, V3, V4. Key indexes:
- `idx_payment_bill_ref` — lookup by ERP bill reference
- `idx_payment_merchant` — tenant-scoped queries
- `idx_payment_status` — batch processing by status
- `idx_payment_recovery_*` — recovery queries
- `idx_outbox_due` — polling pending events
- `idx_outbox_aggregate_order` — per-payment event ordering

---

### D.12 Flyway Migrations

**IMPLEMENTED.** Migrations:
- `V1__initial_schema.sql` — (need to verify; mentioned but not read)
- `V2__payment_ledger_outbox.sql` — (mentioned in docs/architecture.md but not confirmed in working tree)
- `V3__idempotency_provider_retry.sql` — idempotency table, retry metadata columns
- `V4__payment_outbox.sql` — outbox table, sequence, indexes

Flyway 11.3.1 (overridden in pom.xml:39-50). `baseline-on-migrate: true` (per `docs/foundation-decisions.md`).

**UNKNOWN:** `V1__` and `V2__` migration files exist but were not read. Need verification.

---

### D.13 Monetary Database Representation

**IMPLEMENTED.** Amounts stored as integer minor units (`BIGINT`). `PaymentEntity.fromDomain` → `BigDecimal.valueOf(payment.getAmount().toMinorUnits())`. `PaymentEntity.toDomain` validates `amountMinor.scale() == 0` after stripping trailing zeros, then uses `longValueExact()`. Database column is `DECIMAL(18,2)` but the application asserts integer-only.

---

### D.14 Version Columns

**IMPLEMENTED.** `@Version` on `PaymentEntity.version` (line 117) and documented for `JournalEntry` (deferred to Stage 4). On `PaymentEntity`, it's a `Long`.

---

## E. Idempotency

### E.1 API Idempotency

**IMPLEMENTED.** The `Idempotency-Key` header is mandatory for `POST /api/v1/payments`. `PaymentController.createPayment` (line 81) requires it. `IdempotencyKey.of()` validates length and non-blankness.

---

### E.2 Idempotency-Key

**IMPLEMENTED.** Client-supplied, merchant-scoped. Stored in `IdempotencyEntity.idempotencyKey` (VARCHAR(255)). PK is `(merchant_id, idempotency_key)`.

---

### E.3 Request Fingerprint

**IMPLEMENTED.** SHA-256 of canonical request fields, with the payment token masked. Computed in both `PaymentController.requestFingerprint()` (line 202) and `ChargeService.requestFingerprint()` (line 374). Stored as `request_hash` (VARCHAR(64)) in the idempotency table.

---

### E.4 Same Key with Same Payload

**IMPLEMENTED.** Controller (line 100-108) calls `idempotencyService.reserve()`. If `ReplayOutcome`, returns the cached response (HTTP 200 if replayed, HTTP 201 if new).

---

### E.5 Same Key with Different Payload

**IMPLEMENTED.** If `ConflictOutcome` (different fingerprint, non-terminal), controller throws `IdempotencyKeyConflictException` → HTTP 409. If the existing record is terminal, returns the stored response anyway (line 67-74 in `PostgresIdempotencyStore`).

---

### E.6 Database-Level Uniqueness

**IMPLEMENTED.** `UNIQUE (merchant_id, idempotency_key)` on the `idempotency` table. This is the ultimate arbiter — even if the application-level check races, the DB constraint ensures at most one reservation.

---

### E.7 Concurrent Duplicate Requests

**IMPLEMENTED.** Two concurrent requests with the same key:
1. Both call `reserve()`.
2. `PostgresIdempotencyStore.reserve()` does `SELECT FOR UPDATE lockByKey()` — if no row exists, both proceed to `insertReservation()`.
3. One insert wins; the other gets SQL state 23505 (unique violation).
4. Controller (line 117-135) catches the violation, calls `replay()`, and returns the cached response.

---

### E.8 Replaying the Original Result

**PARTIALLY IMPLEMENTED.** The idempotency table stores `response_body` (JSON), `response_status` (int), and `is_terminal` (boolean). However, the controller currently rebuilds the replay response from the `Payment` domain object (line 106-108) rather than returning the cached JSON byte-for-byte. See `docs/stage-5-status.md:79`: "Idempotency response replay body (byte-exact) — NOT IMPLEMENTED."

---

### E.9 Provider Idempotency

**IMPLEMENTED.** `Payment.ensureProviderIdempotencyKey()` (line 218) derives a deterministic key: `"prov_" + paymentId.toString()`. Sent to the provider via `PaymentProcessor.process(..., providerIdempotencyKey)`. `SimulatedPaymentProcessor` caches the first result per key and replays it for subsequent calls (line 70-77).

---

### E.10 Why Application-Level synchronized Methods Are Insufficient

**IMPLEMENTED conceptually.** The documentation in `docs/idempotency-design.md:62-64` explains: "PostgreSQL row locks under `READ_COMMITTED` give true serializability guarantees for money. Redis locks are probabilistic (expiry races, network partition)." The unique constraint is the source of truth; `synchronized` would only work within one JVM.

---

## F. Provider Processing

### F.1 Provider Abstraction

**IMPLEMENTED.** `PaymentProcessor.java` is the port interface (line 39-42). `SimulatedPaymentProcessor.java` is the adapter. Future providers (Stripe, etc.) implement the same interface.

---

### F.2 Simulated Provider

**IMPLEMENTED.** `SimulatedPaymentProcessor.java` — in-process, deterministic outcomes based on token prefix. Uses `ConcurrentHashMap` for provider idempotency cache.

---

### F.3 Stable Provider Idempotency Key

**IMPLEMENTED.** Derived from payment ID: `"prov_" + paymentId`. Persisted in `payment.provider_idempotency_key` column (nullable initially, set by `ensureProviderIdempotencyKey()`). Unique constraint prevents double-charging.

---

### F.4 Provider Timeout

**IMPLEMENTED.** The `SimulatedPaymentProcessor` doesn't simulate actual timeouts — it returns `ProviderResult.unknown()` immediately for `timeout:` tokens. This maps to `PaymentStatus.UNKNOWN`. A real provider would throw a timeout exception; `ChargeService` (line 200) catches `Exception` from `processor.process()` and converts to `ProviderResult.technicalFailure()`.

---

### F.5 Uncertain Provider Result

**IMPLEMENTED.** `ProviderResult.Type.UNKNOWN` → `PaymentStatus.UNKNOWN`. The payment stays non-terminal. `reasonCode` = `PROVIDER_UNKNOWN_OUTCOME`.

---

### F.6 Duplicate Provider Request

**IMPLEMENTED.** If the same `providerIdempotencyKey` is sent twice, `SimulatedPaymentProcessor` replays the cached result (line 70-77). In `ChargeService`, the idempotency check prevents a second provider call for the same `Idempotency-Key`.

---

### F.7 Provider Reference

**IMPLEMENTED.** `ProviderResult.providerReference()` is stored on `Payment.providerReference`. Database unique constraint `uq_payment_provider_ref` (WHERE NOT NULL) prevents duplicates.

---

### F.8 Why a Timeout Does Not Necessarily Mean Payment Failure

**IMPLEMENTED.** Documented in `docs/architecture.md:281` and `docs/payment-domain.md:106`. The provider may have debited the customer but timed out before responding. Treating timeout as failure would double-charge on retry. `UNKNOWN` persists the payment in a non-terminal state. `Payment.applyProviderResult()` maps `Type.UNKNOWN` → `PaymentStatus.UNKNOWN` (not `FAILED`).

---

## G. Recovery

### G.1 CREATED Stuck State

**IMPLEMENTED.** `PaymentRecoveryService.sweep()` (line 78) finds payments with `status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'REQUIRES_RECONCILIATION')` and `createdAt < cutoff`. `RecoveryProperties.createdTimeoutMs` (default 60s) determines the cutoff.

---

### G.2 PROCESSING Stuck State

**IMPLEMENTED.** Same query includes `PROCESSING`. `processingTimeoutMs` (default 300s) controls eligibility.

---

### G.3 Application Crash

**IMPLEMENTED.** Since all state is persisted in PostgreSQL, recovery runs on restart via `@Scheduled`. `RecoveryScheduler.runRecoverySweep()` finds payments in non-terminal states and re-attempts them.

**KNOWN ISSUE:** `@EnableScheduling` is not present on the main application class, so scheduled methods don't auto-fire. See `docs/stage-5-status.md:71`.

---

### G.4 Retry Metadata

**IMPLEMENTED.** `Payment.attemptCount`, `lastAttemptAt`, `nextRetryAt`, `lastFailureReason` — all persisted on the payment row (columns added in V3 migration).

---

### G.5 Exponential Backoff

**IMPLEMENTED.** In `PaymentRecoveryService.recoverPayment()` (line 161-163):
```java
long backoff = Math.min(
    properties.getBaseBackoffMs() * (1L << payment.getAttemptCount()),
    properties.getMaxBackoffMs());
```
Default: 1s base, 30s max.

---

### G.6 Retry Limits

**IMPLEMENTED.** `RecoveryProperties.maxRetries` (default 3). `PaymentRecoveryService` checks `attemptCount >= maxRetries` (line 134) and calls `markRetryExhausted()`.

---

### G.7 Scheduled Recovery

**IMPLEMENTED (partially runnable).** `RecoveryScheduler.runRecoverySweep()` is annotated `@Scheduled(fixedDelay = 60000L, initialDelay = 30000L)`. **BUT** `@EnableScheduling` is missing — see Known Issues.

---

### G.8 UNKNOWN State

**IMPLEMENTED.** `PaymentRecoveryService.recoverPayment()` handles `UNKNOWN` status (line 184): calls `payment.markRetrySubmitted()` → `UNKNOWN → PROCESSING`, re-submits to provider with the same idempotency key.

---

### G.9 REQUIRES_RECONCILIATION State

**IMPLEMENTED.** Recovery handles it the same as `UNKNOWN` (line 185). It's a non-terminal state awaiting reconciliation. However, there is no active reconciliation/polling job implemented — recovery re-submits to the provider instead of polling the provider's status endpoint. The reconciliation *job* that reads settlement files is deferred to Stage 6.

---

### G.10 Safe vs Unsafe Retries

**IMPLEMENTED.** Retries are safe because:
1. The provider idempotency key is reused (`prov_<paymentId>`) — `SimulatedPaymentProcessor` replays the cached result.
2. `Payment.applyProviderResult()` is idempotent — if the payment is already terminal, it's a no-op.
3. The `@Version` column prevents lost updates.

Unsafe scenarios (documented but not code-tested):
- Retrying with a *new* idempotency key would be unsafe (double-charge risk).
- Retrying after `PROVIDER_TECHNICAL_FAILURE` (no money moved) is safe — it's treated as `FAILED`.

---

## H. Stage 5 Concepts

### H.1 Kafka

**IMPLEMENTED (infrastructure).** `spring-kafka` dependency in `pom.xml:94-97`. Configuration in `KafkaOutboxConfiguration.java`. Topics: `payment.events` (6 partitions), `payment.events.DLQ` (1 partition).

**PARTIALLY IMPLEMENTED (auto-start):**
- Producer: fully wired and tested (`KafkaOutboxIntegrationTest`).
- Consumer factory: defined (`erpPaymentEventConsumerFactory`) but **no `@KafkaListener` bean**. See `docs/stage-5-status.md:78`.

---

### H.2 Transactional Outbox

**IMPLEMENTED.** `OutboxEventEntity.java`, `OutboxEventRepository.java`, `OutboxEventService.java`, `V4__payment_outbox.sql` migration. Events are written in the same transaction as payment state changes (TX1 and TX2 in `ChargeService`).

---

### H.3 Outbox Table

**IMPLEMENTED.** `outbox` table created by `V4__payment_outbox.sql` with:
- `id` (PK UUID), `event_id` (UUID UNIQUE), `aggregate_id` (FK to payment)
- `event_type`, `event_version`, `event_payload` (JSONB), `event_key`, `event_order`
- `status` (PENDING/PUBLISHED/FAILED/DEAD_LETTERED)
- `attempt_count`, `available_at`, `next_attempt_at`, `created_at`, `published_at`
- `last_error`, `lock_owner`, `locked_until`
- `uq_outbox_payment_order` UNIQUE on `(aggregate_id, event_order)`

---

### H.4 Event Envelope

**IMPLEMENTED.** `PaymentLifecycleEvent.java` is a 18-field record:
```java
record PaymentLifecycleEvent(
    UUID eventId, String eventType, int eventVersion, long eventOrder,
    UUID paymentId, String merchantId, String erpReference, long amountMinor,
    String currency, PaymentStatus paymentStatus, PaymentStatus previousPaymentStatus,
    String providerReference, String failureCode, Instant occurredAt,
    Instant nextAttemptAt, Integer retryAttempt,
    UUID correlationId, UUID causationId, String reasonCode)
```

---

### H.5 Event Versioning

**IMPLEMENTED.** `PaymentEventType.java` defines 7 event types, all at version 1:
- `PAYMENT_CREATED` → "PaymentCreated"
- `PAYMENT_PROCESSING_STARTED` → "PaymentProcessingStarted"
- `PAYMENT_SUCCEEDED` → "PaymentSucceeded"
- `PAYMENT_FAILED` → "PaymentFailed"
- `PAYMENT_UNKNOWN` → "PaymentUnknown"
- `PAYMENT_RETRY_SCHEDULED` → "PaymentRetryScheduled"
- `PAYMENT_RETRY_EXHAUSTED` → "PaymentRetryExhausted"

The `eventVersion` field is on both `PaymentEventType` and `PaymentLifecycleEvent`.

---

### H.6 At-Least-Once Delivery

**IMPLEMENTED (by design).** Documented in `docs/stage-5-outbox-kafka-erp.md:129-133`. The outbox table is the source of truth — a row is marked `PUBLISHED` only after Kafka acknowledges the send. A crash between Kafka ack and DB update causes re-publishing. Consumers must deduplicate by `event_id`.

---

### H.7 Duplicate Event Handling

**IMPLEMENTED (by design).** The `event_id` column is `UNIQUE` — the publisher cannot re-insert a published event. For consumer-side dedup, `KafkaOutboxIntegrationTest.erpFixtureConsumesCorrelatedEventsAndIgnoresDuplicates` (line 214) tests the `ErpPaymentEventFixture` which deduplicates by `eventId`.

---

### H.8 Event Ordering

**IMPLEMENTED.** Per-payment ordering is guaranteed by:
- `event_order` from `outbox_event_order_seq` sequence
- `findDue` query uses `NOT EXISTS` subquery to skip later events while earlier ones for the same payment are still pending
- Kafka `event_key` = payment UUID → same partition → ordered delivery

---

### H.9 Publisher Retries

**IMPLEMENTED.** `OutboxPublisher.handleFailure()` (line 100-135):
- Increments `attempt_count`
- Applies exponential backoff (bounded by `retry-max-backoff`)
- If `attempt_count >= max_attempts` (default 10), sends to DLQ
- Marks `DEAD_LETTERED` on successful DLQ send, `FAILED` on DLQ failure

---

### H.10 ERP Integration

**PARTIALLY IMPLEMENTED.**
- **ERP as event consumer:** The `ErpPaymentEventFixture.java` (test fixture) consumes from `payment.events` and simulates invoice state updates. This is a *test double*, not a production consumer.
- **Production ERP consumer:** NOT IMPLEMENTED. `KafkaOutboxConfiguration.erpPaymentEventConsumerFactory` (line 81-92) defines a consumer factory, but no `@KafkaListener` bean exists.
- **ERP as API caller:** The ERP calls `POST /api/v1/payments` with `billRef`. This is the main integration path.
- **Polling fallback:** `GET /api/v1/payments/{id}` is available for ERP polling.
- **Webhook/callback:** DEFERRED. `callbackUrl` is not in the request DTO.

**docs/stage-5-status.md:78:** "ERP consumer (Kafka `@KafkaListener`) — NOT IMPLEMENTED"

---

## Implementation Status Summary

| Area | Status |
|------|--------|
| Payment aggregate & state machine | **IMPLEMENTED** |
| Money/Currency value objects | **IMPLEMENTED** |
| Two-phase transaction (TX1/TX2) | **IMPLEMENTED** |
| API idempotency (key, fingerprint, conflict) | **IMPLEMENTED** |
| Database-level idempotency (unique constraint) | **IMPLEMENTED** |
| Optimistic + pessimistic locking | **IMPLEMENTED** |
| Provider abstraction + simulated processor | **IMPLEMENTED** |
| Provider idempotency key | **IMPLEMENTED** |
| Provider timeout → UNKNOWN | **IMPLEMENTED** |
| Recovery scheduler + retry/backoff | **IMPLEMENTED** (but needs `@EnableScheduling`) |
| Retry budget exhaustion | **IMPLEMENTED** |
| Kafka producer | **IMPLEMENTED** |
| Kafka consumer (ERP) | **NOT IMPLEMENTED** |
| Transactional outbox | **IMPLEMENTED** |
| Outbox publisher + retry/DLQ | **IMPLEMENTED** |
| Idempotent replay (byte-exact response) | **PARTIALLY IMPLEMENTED** (rebuilds from Payment, not cached JSON) |
| Settlement / reconciliation | **DEFERRED** (Stage 6) |
| Refunds / chargebacks | **DEFERRED** |
| Ledger / journal entries | **DEFERRED** |
| HTTP 202 for UNKNOWN | **DEFERRED** (controller maps only 200/201) |

## Verification Notes

1. **`chargeWithOutcome` signature mismatch:** `ChargeService.chargeWithOutcome()` (10 args at line 137) accepts `paymentId` as the last parameter. But `ChargeResult` constructor at line 220 is `new ChargeResult(updated, false)` — a 2-arg constructor. However, `ChargeResult` is a 3-arg record `ChargeResult(Payment, boolean, int)`. This is a **compile error** per `docs/stage-5-status.md:29`.
2. **`ReplayDuringReservationException`:** Referenced at `ChargeService.java:172,181` but **not defined** anywhere. **Compile error** per `docs/stage-5-status.md:28`.
3. **`chargeWithOutcome` arg count:** `ChargeService.chargeWithOutcome` at line 137 passes 10 args, but the method signature (line 106) takes 9. **Compile error** per `docs/stage-5-status.md:30`.
4. **`PaymentController` at line 137:** Calls `chargeWithOutcome` with `idempotencyKey, paymentId` (10 args) but the method takes 9. **Compile error** per `docs/stage-5-status.md:31`.
5. **`@EnableScheduling`:** Missing from the main application class. Scheduled methods won't fire.
6. **`ApplicationContextTest`:** Fails to load due to missing `IdempotencyRepository` bean (excludes `DataSourceAutoConfiguration`).
7. **Stale pg_data volume:** Docker Compose postgres has a Flyway checksum mismatch for V3.
8. **V1 and V2 migrations:** Were not read during inspection — assumed to exist based on documentation but not verified.

These are documented accurately per the source code inspection. See `docs/stage-5-status.md` for the full recovery checklist.