# Code Walkthrough

> This document traces the exact execution path through the actual source code.
> Class names, method names, and line numbers reference the real repository.
> **Note:** The code has compile errors (see Verification Notes below) — this
> walkthrough reflects the *intended* design, not a currently-compilable build.

---

## 1. API Request Enters Controller

**File:** `api/controller/PaymentController.java`
**Method:** `createPayment` (line 76-153)

```java
@PostMapping
public ResponseEntity<PaymentResponse> createPayment(
        @Valid @RequestBody final CreatePaymentRequest body,       // line 77
        final HttpServletRequest servletRequest,                  // line 78
        @RequestHeader(value = "X-Correlation-Id", required = false) final String correlationId,  // line 79
        @RequestHeader(value = "X-Base-Url", required = false) final String baseUrl,             // line 80
        @RequestHeader(value = "Idempotency-Key", required = true) final String idempotencyKey) { // line 81
```

**Responsibility:**
- Resolves the correlation ID from `CorrelationIdFilter` (line 86) or generates one
- Validates the request body via `@Valid` Bean Validation
- Extracts and validates the mandatory `Idempotency-Key` header
- Orchestrates idempotency reservation, payment creation, and provider call

**Input:** HTTP request with JSON body + `Idempotency-Key` header
**Output:** `ResponseEntity<PaymentResponse>` — 201 Created / 200 OK / 409 Conflict
**Database effect:** See step 3 (TX1) and step 5 (TX2)
**Failure behavior:**
- Validation failure → `PaymentGatewayExceptionHandler` returns 400
- Idempotency conflict → 409
- Provider exception → caught, converted to technical failure
**Interview talking point:** "The controller is a thin orchestrator — it validates,
reserves idempotency, delegates to ChargeService, and maps results. All business
logic is in the service layer."

---

## 2. Request Validation

**File:** `api/dto/CreatePaymentRequest.java`

```java
public record CreatePaymentRequest(
    @NotBlank(message = "merchantId must not be blank")
    @Size(max = 255, message = "merchantId must be <= 255 chars")
    String merchantId,
    @NotBlank ... String customerRef,
    @NotBlank ... String billRef,
    @NotBlank ... String amount,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotBlank ... String paymentMethod,
    @NotBlank @Size(max = 500) String paymentToken
)
```

**Responsibility:** Structural validation (format, length, presence)

**Input:** JSON deserialized into the `CreatePaymentRequest` record
**Output:** Validated record (or `MethodArgumentNotValidException` if invalid)
**Database effect:** None
**Failure behavior:** `@Valid` on the controller parameter triggers validation
before the method body executes. Violations throw
`MethodArgumentNotValidException`, caught by `PaymentGatewayExceptionHandler`
→ 400 `VALIDATION_FAILED`.

**Note:** `paymentToken` is `@NotBlank` and `@Size(max = 500)` but is **never
persisted** (`PaymentEntity.paymentToken` is `@Transient`).

**Interview talking point:** "Validation is two-tier: structural (Bean Validation
on the DTO) and semantic (domain invariants like `Money.scale()` and
`Currency.fromCode()` throw `IllegalArgumentException` on invalid values)."

---

## 3. Idempotency Handling

**Files:**
- `domain/idempotency/IdempotencyKey.java` — value object (line 16-72)
- `application/port/IdempotencyService.java` — port interface (line 21-74)
- `infrastructure/idempotency/PostgresIdempotencyStore.java` — adapter (line 32-128)

### Step 1: Key Resolution and Fingerprint

```java
// PaymentController.java:96-98
IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
String fingerprint = requestFingerprint(body);
UUID paymentId = UUID.randomUUID();
```

**Responsibility:**
- `IdempotencyKey.of()` validates the key is non-blank and ≤ 255 chars (line 33-42)
- `requestFingerprint()` computes SHA-256 of canonical fields with token masked (line 202-219)

### Step 2: Reserve

```java
// PaymentController.java:100-101
IdempotencyOutcome outcome = idempotencyService.reserve(
    body.merchantId(), key, fingerprint, paymentId);
```

```java
// PostgresIdempotencyStore.java:44-91
@Transactional
public IdempotencyOutcome reserve(String merchantId, IdempotencyKey key,
                                  String requestFingerprint, UUID paymentId) {
    Optional<IdempotencyEntity> existing =
            idempotencyRepository.lockByKey(merchantId, key.value());  // SELECT FOR UPDATE

    if (existing.isPresent()) {
        IdempotencyEntity row = existing.get();
        boolean sameRequest = row.getRequestHash().equals(requestFingerprint);

        if (sameRequest) {
            return new IdempotencyOutcome.ReplayOutcome(
                row.getPaymentId().toString(), row.getResponseStatus(),
                row.getResponseBody(), row.isTerminal());  // line 60-64
        }
        if (row.isTerminal()) {
            return new IdempotencyOutcome.ReplayOutcome(
                row.getPaymentId().toString(), row.getResponseStatus(),
                row.getResponseBody(), true);  // line 70-74
        }
        return new IdempotencyOutcome.ConflictOutcome(
            key.value(), row.getPaymentId().toString(), row.getRequestHash());  // line 79-80
    }

    // No existing row — insert a provisional reservation
    idempotencyRepository.insertReservation(
        merchantId, key.value(), requestFingerprint, paymentId,
        202, "{}", false, NO_EXPIRY);  // line 84-86
    return new IdempotencyOutcome.Proceed();  // line 90
}
```

**Responsibility:**
- Serialize concurrent requests via `SELECT FOR UPDATE` on the idempotency row
- If existing row: return `ReplayOutcome` (same fingerprint or terminal) or `ConflictOutcome`
- If no row: insert a provisional reservation (status=202, body=`{}`, terminal=false)

**Input:** `merchantId`, `IdempotencyKey`, `requestFingerprint`, `paymentId`
**Output:** `IdempotencyOutcome` (one of `Proceed`, `ReplayOutcome`, `ConflictOutcome`)
**Database effect:** `SELECT FOR UPDATE` on `idempotency` table; if no row, `INSERT` a reservation
**Failure behavior:** The `INSERT` is covered by the unique constraint — concurrent inserts
cause SQL 23505, caught by the controller (line 117-135)

**Interview talking point:** "The unique constraint on `(merchant_id, idempotency_key)` is
the ultimate arbiter. Even if the `SELECT FOR UPDATE` misses (no row exists), the `INSERT`
will fail with 23505 for the second concurrent request."

---

## 4. Payment Creation (TX1)

**File:** `application/service/ChargeService.java`
**Method:** `chargeWithOutcome` (line 106-221)

### Step 1: Pre-TX1 Checks

```java
// ChargeService.java:117-129
PaymentMethodType method = PaymentMethodType.fromCode(paymentMethodStr);
Currency currency = Currency.fromCode(currencyCode);
if (!currency.isSupported()) {
    throw new IllegalArgumentException("Unsupported currency: " + currencyCode);
}
Money amount = Money.of(amountStr, currency);
IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
String fingerprint = requestFingerprint(merchantId, customerRef, billRef, ...);
PaymentId typedPaymentId = PaymentId.generate();
UUID paymentId = typedPaymentId.toUuid();
```

**Responsibility:** Validate currency, parse Money, generate payment ID
**Input:** Request fields from controller
**Output:** Domain objects (`Money`, `PaymentId`, `IdempotencyKey`, fingerprint)
**Database effect:** None
**Failure behavior:** `IllegalArgumentException` → 400 `INVALID_REQUEST`

### Step 2: Pre-TX1 Idempotency Check (Fast Path)

```java
// ChargeService.java:131-140
Optional<IdempotencyOutcome.ReplayOutcome> existing = idempotencyService.replay(
    merchantId, key);
if (existing.isPresent()) {
    UUID replayPaymentId = UUID.fromString(existing.get().paymentId());
    Payment replayedPayment = paymentRepository.findByPaymentId(replayPaymentId)
        .map(entity -> entity.toDomain(null))
        .orElseThrow(...);
    return new ChargeResult(replayedPayment, true, existing.get().responseStatus());
}
```

**Responsibility:** Check if the key was already finalized (terminal) — if so, return
the cached result without processing.

**Input:** `merchantId`, `key`
**Output:** `ChargeResult` (if replay) or proceeds to TX1
**Database effect:** Read-only query on `idempotency` table
**Failure behavior:** If no existing record, proceeds to TX1

**Interview talking point:** "There are two idempotency checks: a pre-TX1 fast-path
read (line 131) and the TX1 reservation (line 169). The fast path handles the common
case where the key is already finalized; the reservation handles concurrent first
requests."

### Step 3: TX1 — Create + Reserve

```java
// ChargeService.java:147-180
payment = transactionTemplate.execute(status -> {
    Payment created = Payment.create(                     // line 148-151
        typedPaymentId, merchantId, customerRef, billRef,
        amount, method, paymentToken, correlationId);
    created.ensureProviderIdempotencyKey();               // line 152

    PaymentEntity entity = PaymentEntity.fromDomain(created);  // line 154
    paymentRepository.saveAndFlush(entity);               // line 155 — INSERT payment

    OutboxEventEntity createdEvent = outboxEventService.append(  // line 156
        created, PaymentEventType.PAYMENT_CREATED, null,
        "PAYMENT_CREATED", null, null, null);

    IdempotencyOutcome outcome = idempotencyService.reserve(      // line 169
        merchantId, key, fingerprint, paymentId);
    if (outcome instanceof IdempotencyOutcome.ReplayOutcome replay) {
        throw new ReplayDuringReservationException(replay);        // line 172
    }
    if (outcome instanceof IdempotencyOutcome.ConflictOutcome conflict) {
        throw new IdempotencyKeyConflictException(...);             // line 175
    }
    return created;
});  // COMMIT
```

**Responsibility:** Atomically create the payment, append the first outbox event,
and reserve the idempotency key.

**Input:** Domain objects from step 1
**Output:** `Payment` aggregate in CREATED state
**Database effect:** `INSERT payment`, `INSERT outbox`, `INSERT/UPDATE idempotency`
**Failure behavior:**
- If any step fails, the entire transaction rolls back
- If `ReplayDuringReservationException` is thrown (line 172), the transaction rolls
  back, and the controller (line 181-188) catches it and returns the replayed result
- If `IdempotencyKeyConflictException` is thrown, rolls back → 409

**INTERVIEW TALKING POINT:** "TX1 commits the payment in CREATED before the
provider call. This means the payment is durable even if the provider call or
TX2 fails — recovery can pick it up."

**KNOWN ISSUE:** `ReplayDuringReservationException` is referenced but not defined
(line 172, 181). This is a compile error (see Verification Notes).

---

## 5. Provider Call (Outside Transaction)

**File:** `application/service/ChargeService.java`
**Method:** `chargeWithOutcome` (line 192-206)

```java
// ChargeService.java:192-206
ProviderResult providerResult;
try {
    providerResult = processor.process(
        payment.getPaymentToken(),          // e.g. "success:test"
        payment.getAmount().toMinorUnits(), // e.g. 125000
        payment.getAmount().getCurrency().name(),  // e.g. "INR"
        payment.getCorrelationId(),        // UUID
        payment.getProviderIdempotencyKey() // "prov_<paymentId>"
    );
} catch (Exception e) {
    log.error("Provider call failed for payment {}: {}", paymentId, e.getMessage(), e);
    providerResult = ProviderResult.technicalFailure(
        "PROVIDER_EXCEPTION",
        "Provider call failed: " + e.getClass().getSimpleName()
    );
}
```

**Responsibility:** Submit the payment to the provider and handle exceptions.

**Input:** Payment token, amount in minor units, currency, correlation ID,
provider idempotency key
**Output:** `ProviderResult` (SUCCESS, DECLINED, TECHNICAL_FAILURE, UNKNOWN)
**Database effect:** None — no DB transaction or lock
**Failure behavior:** Any `Exception` from `processor.process()` is caught and
converted to `ProviderResult.technicalFailure()`. This is the safety net for
network errors, connection resets, etc.

**Interview talking point:** "The provider call happens outside any transaction.
This is critical — we can't hold a `SELECT FOR UPDATE` lock during a potentially
slow HTTP call. The payment is already durable in CREATED state."

---

## 6. Transaction Boundary

**Status:** See step 4 (TX1) and step 7 (TX2) above.

- **TX1** uses `TransactionTemplate` (line 80, 147)
- **TX2** uses the same `TransactionTemplate` (line 210)
- The provider call (step 5) executes between TX1 commit and TX2 begin
- Both transactions use the injected `PlatformTransactionManager` via
  `new TransactionTemplate(transactionManager)` (line 92)

---

## 7. State Transition (TX2)

**File:** `application/service/ChargeService.java`
**Method:** `chargeWithOutcome` (line 210-218) and `applyProviderResult` (line 249-338)

### Step 1: TX2 Wrapper

```java
// ChargeService.java:210-218
Payment updated = transactionTemplate.execute(status -> {
    Payment result = applyProviderResult(paymentId, providerCallResult, key);
    boolean isTerminal = result.getStatus().isTerminal();
    String responseBody = buildResponseJson(result, correlationId);
    idempotencyService.finalize(
        merchantId, key, fingerprint,
        paymentId, statusCodeFor(result.getStatus()), responseBody, isTerminal);
    return result;
});
return new ChargeResult(updated, false);  // line 220
```

**Responsibility:** Apply the provider result in a transaction with
SELECT FOR UPDATE, finalize idempotency, and return the result.

**KNOWN ISSUE:** `new ChargeResult(updated, false)` is a 2-arg constructor
call, but `ChargeResult` is a 3-arg record `(Payment payment, boolean replayed,
int responseStatus)` (see `ChargeResult.java:5`). This is a compile error.

### Step 2: applyProviderResult

```java
// ChargeService.java:249-338
protected Payment applyProviderResult(UUID paymentId, ProviderResult providerResult,
                                      IdempotencyKey idempotencyKey) {
    for (int attempt = 1; attempt <= 3; attempt++) {
        try {
            PaymentEntity entity = paymentRepository.findAndLockByPaymentId(paymentId)  // SELECT FOR UPDATE
                .orElseThrow(...);
            Payment payment = entity.toDomain(null);

            if (payment.getStatus().isTerminal()) {
                log.info("Payment {} already terminal ({}) — idempotent apply",
                    paymentId, payment.getStatus());
                return payment;  // line 267 — idempotent no-op
            }

            PaymentStatus statusBeforeProcessing = payment.getStatus();
            payment.markProcessing();  // CREATED → PROCESSING

            UUID processingCausationId = outboxEventService
                .findFirstEventId(paymentId, PaymentEventType.PAYMENT_CREATED)
                .orElse(null);
            OutboxEventEntity processingEvent = outboxEventService.append(
                payment, PaymentEventType.PAYMENT_PROCESSING_STARTED,
                statusBeforeProcessing, "SUBMITTED_TO_PROVIDER",
                processingCausationId, null, null);

            PaymentStatus statusBeforeResult = payment.getStatus();
            payment.applyProviderResult(providerResult);  // PROCESSING → terminal/unknown

            if (payment.getStatus() != statusBeforeResult) {
                PaymentEventType eventType = PaymentEventType.forStatus(payment.getStatus());
                String reason = switch (providerResult.type()) {
                    case SUCCESS -> "PROVIDER_SUCCESS";
                    case DECLINED -> "PROVIDER_DECLINED";
                    case TECHNICAL_FAILURE -> "PROVIDER_TECHNICAL_FAILURE";
                    case UNKNOWN -> "PROVIDER_UNKNOWN_OUTCOME";
                };
                OutboxEventEntity resultEvent = outboxEventService.append(
                    payment, eventType, statusBeforeResult, reason,
                    processingEvent.getEventId(), null, null);
            }

            entity.updateFromDomain(payment);
            paymentRepository.flush();  // line 313
            return payment;

        } catch (ObjectOptimisticLockingFailureException e) {
            if (attempt == 3) throw e;  // line 324
            log.warn("Optimistic lock conflict on payment {} (attempt {}/3), retrying",
                paymentId, attempt);
            Thread.sleep(50L * attempt);  // line 331
        }
    }
    throw new IllegalStateException("Unreachable: retry loop exhausted");
}
```

**Responsibility:**
1. Acquire `SELECT FOR UPDATE` on the payment row (prevents concurrent transitions)
2. Check if already terminal (idempotent — no-op if so)
3. Transition CREATED → PROCESSING via `markProcessing()`
4. Append `PaymentProcessingStarted` outbox event
5. Apply provider result via `Payment.applyProviderResult()` (PROCESSING → terminal/unknown)
6. If status changed, append the result outbox event (Succeeded/Failed/Unknown)
7. Flush + update entity in same transaction

**Input:** `paymentId`, `ProviderResult`, `IdempotencyKey`
**Output:** Updated `Payment` aggregate
**Database effect:** `SELECT FOR UPDATE`, `UPDATE payment`, `INSERT outbox` (x2-3)
**Failure behavior:** Retries up to 3 times on optimistic lock failure; throws
if exhausted

**Interview talking point:** "The `SELECT FOR UPDATE` lock ensures only one
thread can transition a payment at a time. The terminal check makes it
idempotent — a duplicate provider result for an already-terminal payment is
a no-op."

---

## 8. Provider Result Handling

**File:** `domain/payment/Payment.java`
**Method:** `applyProviderResult` (line 149-176)

```java
public void applyProviderResult(final ProviderResult result) {
    if (this.status.isTerminal()) {
        // Duplicate provider result for an already-terminal payment
        if (this.providerReference == null && result.providerReference() != null) {
            this.providerReference = result.providerReference();
        }
        return;  // line 157 — idempotent no-op
    }

    PaymentStatus target = mapResultToStatus(result.type());
    PaymentStateEngine.TransitionReason reason = mapResultToReason(result.type());

    this.status = PaymentStateEngine.transition(
        this.status, target, reason);  // may throw IllegalStateTransitionException

    if (result.providerReference() != null) {
        this.providerReference = result.providerReference();
    }
    if (result.failureCode() != null) {
        this.failureCode = result.failureCode();
    }
    if (result.failureReason() != null) {
        this.failureReason = result.failureReason();
    }
    touch();
}
```

**Responsibility:** Map `ProviderResult` to a `PaymentStatus` transition and
apply it. Only the provider reference, failure code, and failure reason are
updated from the result.

**Input:** `ProviderResult` (immutable record)
**Output:** `void` (mutates `Payment.status`, `providerReference`, etc.)
**Database effect:** None directly — the caller (`applyProviderResult` in
ChargeService) persists the updated Payment via `entity.updateFromDomain(payment)`
**Failure behavior:** `PaymentStateEngine.transition()` throws
`IllegalStateTransitionException` for invalid transitions

**mapResultToStatus (line 293-299):**
```java
case SUCCESS -> PaymentStatus.SUCCEEDED;
case DECLINED -> PaymentStatus.FAILED;
case TECHNICAL_FAILURE -> PaymentStatus.FAILED;
case UNKNOWN -> PaymentStatus.UNKNOWN;
```

**Interview talking point:** "The terminal check at the top makes this
idempotent. A duplicate callback for an already-SUCCEEDED payment is a safe
no-op. The state engine enforces valid transitions — you can't go from
SUCCEEDED → FAILED."

---

## 9. Retry / Recovery Handling

**Files:**
- `infrastructure/scheduling/RecoveryScheduler.java` (line 53)
- `application/service/PaymentRecoveryService.java` (line 70-215)

### Step 1: Scheduler Trigger

```java
// RecoveryScheduler.java:53-68
@Scheduled(fixedDelay = 60000L, initialDelay = 30000L)
public void runRecoverySweep() {
    if (!properties.isEnabled()) return;
    Instant now = Instant.now();
    log.info("Starting recovery sweep: now={}", now);
    try {
        int processed = recoveryService.sweep(now);
        log.info("Recovery sweep complete: processed={}", processed);
    } catch (Exception e) {
        log.error("Recovery sweep failed: {}", e.getMessage(), e);
    }
}
```

**KNOWN ISSUE:** `@EnableScheduling` is missing from
`PaymentGatewaySettlementApplication.java`. The `@Scheduled` annotation has no
effect — scheduled methods will NOT fire in a running app.

### Step 2: Recovery Sweep

```java
// PaymentRecoveryService.java:70-101
@Transactional
public int sweep(final Instant now) {
    Instant cutoff = now.minusMillis(properties.getCreatedTimeoutMs());
    List<PaymentEntity> candidates = paymentRepository.findForRecovery(cutoff, now);
    if (candidates.isEmpty()) return 0;

    int processed = 0;
    int limit = Math.min(candidates.size(), properties.getMaxPerSweep());
    for (int i = 0; i < limit; i++) {
        PaymentEntity entity = candidates.get(i);
        try {
            recoverPayment(entity, now);
            processed++;
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict during recovery for payment {}",
                entity.getPaymentId());
        } catch (Exception e) {
            log.error("Recovery failed for payment {}: {}",
                entity.getPaymentId(), e.getMessage(), e);
        }
    }
    log.info("Recovery sweep complete: processed={}/{}", processed, limit);
    return processed;
}
```

**Responsibility:** Find non-terminal payments older than the cutoff,
process up to `maxPerSweep` of them, handle errors per-payment.

### Step 3: Recover Single Payment

```java
// PaymentRecoveryService.java:124-215
@Transactional
protected void recoverPayment(final PaymentEntity entity, final Instant now) {
    PaymentEntity locked = paymentRepository.findAndLockByPaymentId(entity.getPaymentId())
        .orElseThrow(...);
    Payment payment = locked.toDomain(null);

    // Check retry budget
    if (payment.getAttemptCount() >= properties.getMaxRetries()) {
        payment.markRetryExhausted("Retry budget exhausted after " +
            properties.getMaxRetries() + " attempts");
        locked.updateFromDomain(payment);
        outboxEventService.append(
            payment, PaymentEventType.PAYMENT_RETRY_EXHAUSTED,
            payment.getStatus(), "RETRY_EXHAUSTED",
            outboxEventService.findLatestEventId(payment.getPaymentId().toUuid()).orElse(null),
            payment.getAttemptCount(), null);
        return;
    }

    // Check if next retry is in the future
    if (payment.getNextRetryAt() != null && payment.getNextRetryAt().isAfter(now)) {
        return;  // Not yet due
    }

    // Ensure stable provider idempotency key
    payment.ensureProviderIdempotencyKey();

    PaymentStatus statusBeforeRetry = payment.getStatus();
    long backoff = Math.min(
        properties.getBaseBackoffMs() * (1L << payment.getAttemptCount()),
        properties.getMaxBackoffMs());
    Instant nextRetryAt = now.plusMillis(backoff);
    int retryAttempt = payment.getAttemptCount() + 1;

    // Append retry-scheduled event (if applicable)
    OutboxEventEntity scheduledEvent = null;
    if (statusBeforeRetry == PaymentStatus.CREATED 
            || statusBeforeRetry.isAwaitingResolution()) {
        scheduledEvent = outboxEventService.append(
            payment, PaymentEventType.PAYMENT_RETRY_SCHEDULED,
            statusBeforeRetry, "RETRY_SCHEDULED",
            outboxEventService.findLatestEventId(payment.getPaymentId().toUuid()).orElse(null),
            retryAttempt, nextRetryAt);
    }

    // Re-enter PROCESSING
    if (statusBeforeRetry == PaymentStatus.UNKNOWN ||
            statusBeforeRetry == PaymentStatus.REQUIRES_RECONCILIATION) {
        payment.markRetrySubmitted();  // UNKNOWN → PROCESSING
    } else if (statusBeforeRetry == PaymentStatus.CREATED ||
            statusBeforeRetry == PaymentStatus.PROCESSING) {
        payment.markProcessing();       // CREATED → PROCESSING
    }

    if (payment.getStatus() != statusBeforeRetry) {
        OutboxEventEntity processingEvent = outboxEventService.append(
            payment, PaymentEventType.PAYMENT_PROCESSING_STARTED,
            statusBeforeRetry, "RETRY_SUBMITTED",
            statusBeforeRetry == PaymentStatus.CREATED
                || statusBeforeRetry.isAwaitingResolution() ? scheduledEvent.getId() : null,
            retryAttempt, null);
    }

    payment.scheduleNextRetry(nextRetryAt);
    locked.updateFromDomain(payment);
    log.info("Scheduled recovery for payment {}: status={}, attemptCount={}, nextRetryAt={}",
        payment.getPaymentId(), payment.getStatus(),
        payment.getAttemptCount(), payment.getNextRetryAt());
}
```

**Responsibility:** Attempt to recover a single payment. Checks retry budget,
schedules backoff, re-enters PROCESSING, appends outbox events.

**Input:** `PaymentEntity` (locked), `Instant now`
**Output:** `void` (mutates payment via `locked.updateFromDomain(payment)`)
**Database effect:** `SELECT FOR UPDATE` on payment, `UPDATE payment`,
`INSERT outbox` (0-2 events)
**Failure behavior:** Per-payment try/catch in `sweep()` — one failure doesn't
block others

**KNOWN GAP:** Recovery does NOT call `processor.process()`. It re-enters
PROCESSING and schedules a retry, but the actual provider re-submission
is not wired in. Recovery can only change the payment status and schedule
retries — it doesn't attempt a new provider call.

**KNOWN GAP:** `attemptCount` is never incremented in `recoverPayment()`.
The `retryAttempt` local variable is computed but not set on the payment
(`payment.attemptCount` remains unchanged). This means retry exhaustion
will never trigger via the recovery path alone.

**Interview talking point:** "Recovery re-enters PROCESSING and schedules
exponential backoff, but the current implementation has a gap: it doesn't
actually call the provider. A complete implementation would call
`processor.process()` with the stable provider idempotency key and then
apply the result in TX2."

---

## 10. Exception Handling

**File:** `common/PaymentGatewayExceptionHandler.java`

```java
@RestControllerAdvice
public class PaymentGatewayExceptionHandler {
    
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EntityNotFoundException e) {
        // Returns 404 NOT_FOUND
    }
    
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyKeyConflictException e) {
        // Returns 409 IDEMPOTENCY_KEY_CONFLICT
    }
    
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalStateTransition(...) {
        // Returns 409 ILLEGAL_STATE_TRANSITION
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        // Returns 400 VALIDATION_FAILED with field errors
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAll(Exception e) {
        // Returns 500 INTERNAL_ERROR
    }
}
```

**Responsibility:** Centralize all exception-to-HTTP-response mapping. Never
expose stack traces to clients.

**Key behaviors:**
- `EntityNotFoundException` → 404 with payment ID
- `IdempotencyKeyConflictException` → 409 with conflict details
- Validation errors → 400 with field-level detail array
- All other exceptions → 500 (correlation ID only, no stack trace)

**Database exception handling (in controller, line 117-135):**
```java
catch (RuntimeException e) {
    String msg = e.getMessage();
    if (msg == null || (!msg.contains("23505") && !msg.contains("unique"))) {
        throw e;
    }
    log.info("Idempotency unique violation on key={}, re-reading existing row",
        maskKey(idempotencyKey));
    Optional<IdempotencyOutcome.ReplayOutcome> replay = idempotencyService.replay(
        body.merchantId(), key);
    if (replay.isPresent()) {
        Payment payment = chargeService.getPayment(
            UUID.fromString(replay.get().paymentId()));
        PaymentResponse cached = PaymentDtoMapper.toResponse(payment, base);
        return ResponseEntity.status(replay.get().responseStatus() == 201 ? 201 : 200)
            .body(cached);
    }
    throw new IdempotencyKeyConflictException(
        "Idempotency key already in use", idempotencyKey, null);
}
```

**Interview talking point:** "The 23505 catch in the controller is a defensive
measure — the `PostgresIdempotencyStore.reserve()` should handle this via
the unique constraint, but the controller also catches it directly if the
constraint fires before the store's `INSERT`."

---

## 11. Database Persistence

**Files:**
- `infrastructure/persistence/entity/PaymentEntity.java` — JPA entity
- `infrastructure/persistence/entity/IdempotencyEntity.java` — JPA entity
- `infrastructure/persistence/entity/OutboxEventEntity.java` — JPA entity
- `infrastructure/persistence/repository/PaymentRepository.java` — Spring Data JPA
- `infrastructure/persistence/repository/IdempotencyRepository.java` — Spring Data JPA
- `infrastructure/persistence/repository/OutboxEventRepository.java` — Spring Data JPA

### PaymentEntity Key Features

```java
@Entity
@Table(name = "payment")
public class PaymentEntity {
    @Id @Column(name = "payment_id", columnDefinition = "UUID")
    private UUID paymentId;
    
    @Column(name = "amount_minor", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;  // stored as minor units (e.g. 125000 for ₹1250.00)
    
    @Transient private String paymentToken;  // NEVER persisted (PCI-DSS)
    
    @Version @Column(name = "version")
    private Long version;  // optimistic locking
    
    @Column(name = "provider_reference", unique = true)
    private String providerReference;  // prevents duplicate provider refs
}
```

**Mapping methods:**
- `PaymentEntity.fromDomain(Payment)` (line 129) — domain → entity
- `PaymentEntity.updateFromDomain(Payment)` (line 155) — updates mutable fields
- `PaymentEntity.toDomain(String paymentToken)` (line 180) — entity → domain

### OutboxEntity Key Features

```java
@Entity
@Table(name = "outbox")
public class OutboxEventEntity {
    @Id @Column(name = "id", columnDefinition = "UUID")
    private UUID id;
    
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;  // consumer dedup key
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_payload", nullable = false, columnDefinition = "JSONB")
    private String eventPayload;  // serialized PaymentLifecycleEvent
    
    @Column(name = "event_key", nullable = false)
    private String eventKey;  // payment UUID string (Kafka partition key)
    
    @Column(name = "event_order", nullable = false, updatable = false)
    private Long eventOrder;  // from outbox_event_order_seq
}
```

**Database effect:** All writes happen inside `TransactionTemplate.execute()`
blocks — the payment row, outbox rows, and idempotency row are written
atomically. If the transaction rolls back, all rows are reverted.

**Interview talking point:** "The `event_id` is `UNIQUE` so the publisher
can't insert a duplicate. The `aggregate_id` foreign key to `payment` ensures
outbox rows can't outlive their payment. The `FOR UPDATE SKIP LOCKED` query
in `OutboxEventRepository.findDue` (native query) handles parallel publishing."

---

## 12. Event / Outbox Handling

**Files:**
- `domain/event/PaymentEventType.java` — 7 event types, all version 1 (line 3-36)
- `domain/event/PaymentLifecycleEvent.java` — 18-field record (line 8-29)
- `application/service/OutboxEventService.java` — append logic (line 20-107)
- `infrastructure/messaging/OutboxPublisher.java` — publish logic (line 24-164)
- `infrastructure/messaging/KafkaOutboxConfiguration.java` — Kafka config (line 23-93)
- `infrastructure/messaging/OutboxPublicationStatus.java` — enum (line 3-8)

### Event Types (PaymentEventType.java:3-10)

```java
PAYMENT_CREATED("PaymentCreated", 1),
PAYMENT_PROCESSING_STARTED("PaymentProcessingStarted", 1),
PAYMENT_SUCCEEDED("PaymentSucceeded", 1),
PAYMENT_FAILED("PaymentFailed", 1),
PAYMENT_UNKNOWN("PaymentUnknown", 1),
PAYMENT_RETRY_SCHEDULED("PaymentRetryScheduled", 1),
PAYMENT_RETRY_EXHAUSTED("PaymentRetryExhausted", 1);
```

### Append (OutboxEventService.java:37-93)

```java
@Transactional
public OutboxEventEntity append(final Payment payment,
                                final PaymentEventType eventType,
                                final PaymentStatus previousStatus,
                                final String reasonCode,
                                final UUID causationId,
                                final Integer retryAttempt,
                                final Instant nextAttemptAt) {
    Instant occurredAt = Instant.now();
    long eventOrder = repository.nextEventOrder();
    PaymentLifecycleEvent event = new PaymentLifecycleEvent(
        UUID.randomUUID(),           // eventId — UUID
        eventType.wireValue(),       // eventType
        eventType.version(),         // 1
        eventOrder,                  // monotonic per-payment order
        payment.getPaymentId().toUuid(),
        payment.getMerchantId(),
        payment.getBillRef(),        // erpReference
        payment.getAmount().toMinorUnits(),
        payment.getAmount().getCurrency().name(),
        payment.getStatus(),
        previousStatus,
        payment.getProviderReference(),
        payment.getFailureCode(),
        occurredAt,
        nextAttemptAt,
        retryAttempt,
        payment.getCorrelationId(),
        causationId,
        reasonCode
    );

    String payload = objectMapper.writeValueAsString(event);
    OutboxEventEntity entity = new OutboxEventEntity(
        UUID.randomUUID(), event.eventId(), PAYMENT_AGGREGATE_TYPE,
        event.paymentId(), event.eventType(), event.eventVersion(),
        eventOrder, payload, event.paymentId().toString(),
        occurredAt, occurredAt, occurredAt
    );
    OutboxEventEntity saved = repository.saveAndFlush(entity);
    metrics.recordCreated();
    return saved;
}
```

**Responsibility:** Create a versioned `PaymentLifecycleEvent`, serialize to JSON,
persist as an `OutboxEventEntity` with status=PENDING.

### Publish (OutboxPublisher.java:53-164)

```java
@Scheduled(fixedDelayString = "${app.outbox.polling-interval:1s}")
@Transactional
public void publishDueEvents() {
    if (!running || !properties.isEnabled()) return;

    Instant now = Instant.now();
    List<OutboxEventEntity> due = repository.findDue(
        now, PageRequest.of(0, Math.max(1, properties.getBatchSize())));
    metrics.recordPoll(due.size(), repository.countByStatus(PENDING));
    
    for (OutboxEventEntity event : due) {
        publishOne(event, now);
    }
}

private void publishOne(final OutboxEventEntity event, final Instant now) {
    String owner = UUID.randomUUID().toString();
    Instant lockedUntil = now.plus(properties.getLeaseDuration());
    
    // Claim with CAS
    if (repository.claim(event.getId(), owner, now, lockedUntil) != 1) {
        return;  // already claimed by another worker
    }

    try {
        // Validate deserialization (catches corrupted payloads)
        objectMapper.readValue(event.getEventPayload(), PaymentLifecycleEvent.class);
        
        // Send to Kafka and block for ack
        kafkaTemplate.send(
            properties.getKafka().getTopic(),
            event.getEventKey(),      // payment UUID string — partition key
            event.getEventPayload()   // JSON payload
        ).get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        
        // Mark published ONLY after ack
        repository.markPublished(event.getId(), owner, Instant.now());
        metrics.recordPublished();
        
    } catch (Exception e) {
        handleFailure(event, owner, now, e);
    }
}
```

**Responsibility:** Poll pending events, claim with lease, publish to Kafka,
mark published only after ack, handle failures with backoff + DLQ.

**Delivery guarantee:** At-least-once. If the ack arrives but the app crashes
before `markPublished`, the row stays PENDING and is re-published. Consumers
deduplicate by `event_id`.

**Kafka config (KafkaOutboxConfiguration.java:62-79):**
```java
ProducerFactory<String, String> paymentOutboxProducerFactory() {
    configs.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    configs.put(VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    configs.put(ACKS_CONFIG, "all");              // strongest durability
    configs.put(ENABLE_IDEMPOTENCE_CONFIG, true);  // Kafka-level idempotence
    configs.put(RETRIES_CONFIG, 3);
    configs.put(MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);  // for idempotence
}
```

**Interview talking point:** "The `claim()` with CAS pattern ensures
two workers never publish the same row. The `FOR UPDATE SKIP LOCKED`
query in `findDue` prevents workers from blocking on each other. The
`NOT EXISTS` subquery preserves per-payment ordering."

---

## Execution Path Summary

```
HTTP Request
    ↓
PaymentController.createPayment (line 76)
    ├── PaymentController.requestFingerprint (line 202) — SHA-256, token masked
    ├── IdempotencyService.reserve (line 100) → PostgresIdempotencyStore.reserve (line 44)
    │   ├── IdempotencyRepository.lockByKey (SELECT FOR UPDATE)
    │   └── IdempotencyRepository.insertReservation (INSERT or 23505)
    │
    ├── ChargeService.chargeWithOutcome (line 106)
    │   ├── Currency/Money validation
    │   ├── ChargeService.replay() fast-path check (line 131)
    │   │
    │   ├── TX1: transactionTemplate.execute (line 147)
    │   │   ├── Payment.create() → PaymentEntity.fromDomain() → saveAndFlush
    │   │   ├── OutboxEventService.append(PAYMENT_CREATED)
    │   │   └── IdempotencyService.reserve (inside TX1)
    │   │       └── May throw ReplayDuringReservationException (COMPILE ERROR)
    │   │
    │   ├── processor.process(token, amount, currency, corrId, providerKey)
    │   │   └── SimulatedPaymentProcessor → ProviderResult (token prefix)
    │   │
    │   └── TX2: transactionTemplate.execute (line 210)
    │       ├── PaymentRepository.findAndLockByPaymentId (SELECT FOR UPDATE)
    │       ├── Payment.markProcessing() (CREATED → PROCESSING)
    │       ├── OutboxEventService.append(PAYMENT_PROCESSING_STARTED)
    │       ├── Payment.applyProviderResult(result) (PROCESSING → terminal)
    │       ├── OutboxEventService.append(PAYMENT_*)
    │       ├── IdempotencyService.finalize()
    │       ├── PaymentEntity.updateFromDomain() + paymentRepository.flush()
    │       └── new ChargeResult(updated, false) (COMPILE ERROR — needs 3 args)
    │
    └── PaymentController returns ResponseEntity
        └── ResponseEntity.status(replayed ? OK : CREATED).body(response)

Background (needs @EnableScheduling):
    ├── OutboxPublisher.publishDueEvents (@Scheduled)
    │   └── OutboxEventRepository.findDue (FOR UPDATE SKIP LOCKED)
    │   └── KafkaTemplate.send → markPublished or handleFailure
    │
    └── RecoveryScheduler.runRecoverySweep (@Scheduled)
        └── PaymentRecoveryService.sweep()
            └── PaymentRecoveryService.recoverPayment()
                └── (GAP: does not call processor.process())
```

---

## Verification Notes

The following compile errors prevent the code from compiling (per
`docs/stage-5-status.md:26-31`):

1. **`ReplayDuringReservationException`** — referenced at `ChargeService.java:172,181` but not defined as a class.
2. **`ChargeResult` arity** — `new ChargeResult(updated, false)` at line 220 is a 2-arg call, but `ChargeResult` (line 5) is a 3-arg record `(Payment, boolean, int)`.
3. **`chargeWithOutcome` arg count** — called at `PaymentController.java:137` with 10 args (including `paymentId`), but the method signature (line 106) accepts 9 args (no `paymentId`). Note: `ChargeService.charge()` at line 233 calls `chargeWithOutcome` with a generated `UUID.randomUUID()` as the last arg — matching the 9-arg signature.
4. **`PaymentController` arg count** — calls `chargeWithOutcome` at line 137 with `idempotencyKey, paymentId` as the last two args (10 total), but the method accepts 9.

5. **`@EnableScheduling`** — Missing from `PaymentGatewaySettlementApplication.java`. `@Scheduled` methods on `OutboxPublisher` and `RecoveryScheduler` will not auto-fire.

6. **`attemptCount` not incremented in recovery** — `PaymentRecoveryService.recoverPayment()` computes `retryAttempt = payment.getAttemptCount() + 1` (line 165) but never calls `payment.setAttemptCount(retryAttempt)` or equivalent. The `Payment` class has no `setAttemptCount` method — `attemptCount` is only modified in `recordProviderAttempt()` (line 201), which is never called from recovery. This means retry exhaustion will never trigger via the recovery path.

7. **`REQUIRES_RECONCILIATION` not reachable** — `Payment.applyProviderResult()` maps `TECHNICAL_FAILURE → FAILED` (line 295), not `→ REQUIRES_RECONCILIATION`. No code path produces `REQUIRES_RECONCILIATION`.

8. **`ApplicationContextTest` broken** — Excludes `DataSourceAutoConfiguration` and `HibernateJpaAutoConfiguration`, but `PostgresIdempotencyStore` requires `IdempotencyRepository` (a JPA repository). This causes `NoSuchBeanDefinitionException`.

No application code was modified during the creation of this documentation.