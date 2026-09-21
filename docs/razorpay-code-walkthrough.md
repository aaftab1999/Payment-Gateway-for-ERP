# Razorpay Code Walkthrough

> This document traces the exact execution path through the actual source code for the Razorpay integration. All class names, method names, and line numbers reference the real repository files in `src/main/java/com/paymentgateway/settlement/infrastructure/external/provider/razorpay/`.

---

## Table of Contents

1. [Normal Charge Flow with Razorpay (Order Created)](#1-normal-charge-flow-with-razorpay-order-created)
2. [Provider Result Applied (TX2)](#2-provider-result-applied-tx2)
3. [Webhook: payment.captured → SUCCEEDED](#3-webhook-paymentcaptured--succeeded)
4. [Webhook: payment.failed → FAILED](#4-webhook-paymentfailed--failed)
5. [Duplicate Webhook (Idempotent Resolution)](#5-duplicate-webhook-idempotent-resolution)
6. [Order Creation HTTP 400 (Declined)](#6-order-creation-http-400-declined)
7. [Order Creation HTTP 401 (Auth Error)](#7-order-creation-http-401-auth-error)
8. [Order Creation HTTP 500 (Server Error)](#8-order-creation-http-500-server-error)
9. [Recovery: Stuck UNKNOWN Payment](#9-recovery-stuck-unknown-payment)

---

## 1. Normal Charge Flow with Razorpay (Order Created)

**Trigger:** ERP calls `POST /api/v1/payments` with `paymentToken: "success:test"` (or any token) while `app.razorpay.enabled=true`.

```
PaymentController.createPayment
  └── ChargeService.chargeWithOutcome
        ├── TX1: create payment (CREATED) + reserve idempotency + outbox(PaymentCreated)
        ├── RazorpayPaymentProcessor.process()
        └── TX2: markProcessing → applyProviderResult → UNKNOWN + outbox
```

### Step 1: Controller Entry Point

**File:** `api/controller/PaymentController.java:67-104`

```java
@PostMapping
public ResponseEntity<PaymentResponse> createPayment(
        @Valid @RequestBody final CreatePaymentRequest body,
        ...
        @RequestHeader(value = "Idempotency-Key", required = true) final String idempotencyKey) {

    UUID corrId = (UUID) servletRequest.getAttribute("correlationId");
    UUID paymentId = UUID.randomUUID();
    ChargeResult result = chargeService.chargeWithOutcome(
            body.merchantId(), body.customerRef(), body.billRef(),
            body.amount(), body.currency(), body.paymentMethod(),
            body.paymentToken(), corrId, idempotencyKey, paymentId);

    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(response);
}
```

- `paymentId` is generated client-side (UUID) and passed to `chargeWithOutcome`
- `ChargeResult.replayed()` — `false` for a new payment → HTTP 201
- No idempotency fast-path in the controller (removed during Phase 0 fix); the pre-check is in `ChargeService.chargeWithOutcome`

**File:** `application/service/ChargeService.java:106-116`

```java
public ChargeResult chargeWithOutcome(
        final String merchantId, final String customerRef, final String billRef,
        final String amountStr, final String currencyCode,
        final String paymentMethodStr, final String paymentToken,
        final UUID correlationId, final String idempotencyKey,
        final UUID paymentId) {
```

### Step 2: Pre-TX1 Idempotency Check

**File:** `application/service/ChargeService.java:131-140`

```java
Optional<IdempotencyOutcome.ReplayOutcome> existing = idempotencyService.replay(
        merchantId, key);
if (existing.isPresent()) {
    UUID replayPaymentId = UUID.fromString(existing.get().paymentId());
    Payment replayedPayment = paymentRepository.findByPaymentId(replayPaymentId)
            .map(entity -> entity.toDomain(null))
            .orElseThrow(() -> new EntityNotFoundException(...));
    return new ChargeResult(replayedPayment, true, existing.get().responseStatus());
}
```

If the idempotency key was already finalized (terminal), this returns the cached result. For the first request, `existing` is empty — proceeds to TX1.

### Step 3: TX1 — Create Payment + Reserve Idempotency

**File:** `application/service/ChargeService.java:147-180`

```java
payment = transactionTemplate.execute(status -> {
    Payment created = Payment.create(
            typedPaymentId, merchantId, customerRef, billRef,
            amount, method, paymentToken, correlationId);
    created.ensureProviderIdempotencyKey();          // → "prov_" + paymentId

    PaymentEntity entity = PaymentEntity.fromDomain(created);
    paymentRepository.saveAndFlush(entity);           // INSERT (CREATED)
    OutboxEventEntity createdEvent = outboxEventService.append(
            created, PaymentEventType.PAYMENT_CREATED, null,
            "PAYMENT_CREATED", null, null, null);
    // ...outbox event log...

    IdempotencyOutcome outcome = idempotencyService.reserve(
            merchantId, key, fingerprint, paymentId);
    if (outcome instanceof IdempotencyOutcome.ReplayOutcome replay) {
        throw new ReplayDuringReservationException(replay);
    }
    if (outcome instanceof IdempotencyOutcome.ConflictOutcome conflict) {
        throw new IdempotencyKeyConflictException(...);
    }
    return created;
});
```

- `Payment.ensureProviderIdempotencyKey()` generates `prov_<paymentId>` (stored as `"prov_" + this.paymentId.toString()`)
- The transaction commits atomically: payment row + outbox row + idempotency reservation
- `ReplayDuringReservationException` is caught at line 181-188; the in-flight payment is discarded and the replayed result returned

### Step 4: Provider Call — RazorpayOrderRequest

**File:** `infrastructure/external/provider/razorpay/RazorpayPaymentProcessor.java:74-122`

```java
@Override
public ProviderResult process(
        final String paymentToken, final long amountMinor,
        final String currency, final UUID correlationId,
        final String providerIdempotencyKey) {

    log.info("Creating Razorpay order: amount={}, currency={}, correlationId={}, receipt={}",
            maskAmount(amountMinor), currency, correlationId, maskKey(providerIdempotencyKey));

    String receipt = toRazorpayReceipt(providerIdempotencyKey);  // strips "prov_" prefix

    RazorpayOrderRequest request = new RazorpayOrderRequest(
            amountMinor,
            currency,
            receipt,
            Map.of("correlationId", correlationId.toString()),
            1  // payment_capture = 1 (automatic capture)
    );

    RazorpayOrderResponse response = restClient.post()
            .uri("/orders")
            .body(request)
            .retrieve()
            .body(RazorpayOrderResponse.class);

    if (response == null) {
        return ProviderResult.unknown("EMPTY_RESPONSE", "Razorpay returned an empty response");
    }

    return new ProviderResult(
            ProviderResult.Type.UNKNOWN,
            response.id(),
            "ORDER_CREATED",
            "Razorpay order " + response.id() + " created; awaiting customer payment");
}
```

### Step 5: toRazorpayReceipt — Idempotency Key Transformation

**File:** `infrastructure/external/provider/razorpay/RazorpayPaymentProcessor.java:130-143`

```java
private static String toRazorpayReceipt(final String providerIdempotencyKey) {
    if (providerIdempotencyKey == null || providerIdempotencyKey.isBlank()) {
        return UUID.randomUUID().toString();
    }
    if (providerIdempotencyKey.startsWith(PROVIDER_KEY_PREFIX)) {  // "prov_"
        String uuid = providerIdempotencyKey.substring(PROVIDER_KEY_PREFIX.length());
        return uuid.length() <= RECEIPT_MAX_LEN  // 40
                ? uuid
                : uuid.substring(0, RECEIPT_MAX_LEN);
    }
    return providerIdempotencyKey.length() <= RECEIPT_MAX_LEN
            ? providerIdempotencyKey
            : providerIdempotencyKey.substring(0, RECEIPT_MAX_LEN);
}
```

Input: `prov_550e8400-e29b-41d4-a716-446655440000` (41 chars)
- Strips `prov_` → `550e8400-e29b-41d4-a716-446655440000` (36 chars)
- 36 ≤ 40 → returns UUID directly
- This UUID is sent as the `receipt` field to Razorpay, which uses it for server-side idempotency

### Step 6: TX2 — Apply Provider Result

**File:** `application/service/ChargeService.java:210-220`

```java
Payment updated = transactionTemplate.execute(status -> {
    Payment result = applyProviderResult(paymentId, providerCallResult, key);
    boolean isTerminal = result.getStatus().isTerminal();
    String responseBody = buildResponseJson(result, correlationId);
    idempotencyService.finalize(
            merchantId, key, fingerprint,
            paymentId, statusCodeFor(result.getStatus()), responseBody, isTerminal);
    return result;
});

return new ChargeResult(updated, false, statusCodeFor(updated.getStatus()));
```

`statusCodeFor(UNKNOWN)` returns `202` (per `ChargeService.java:513-519`), though the controller currently maps only to 201 for new payments (see [§3 of razorpay-integration.md](razorpay-integration.html)).

---

## 2. Provider Result Applied (TX2)

**File:** `application/service/ChargeService.java:249-338`

```java
protected Payment applyProviderResult(
        final UUID paymentId, final ProviderResult providerResult,
        final IdempotencyKey idempotencyKey) {

    for (int attempt = 1; attempt <= 3; attempt++) {
        try {
            PaymentEntity entity = paymentRepository.findAndLockByPaymentId(paymentId)
                    .orElseThrow(() -> new EntityNotFoundException("Payment not found"));
            Payment payment = entity.toDomain(null);

            if (payment.getStatus().isTerminal()) {
                return payment;  // idempotent no-op
            }

            PaymentStatus statusBeforeProcessing = payment.getStatus();  // CREATED
            payment.markProcessing();  // CREATED → PROCESSING

            UUID processingCausationId = outboxEventService
                    .findFirstEventId(paymentId, PaymentEventType.PAYMENT_CREATED)
                    .orElse(null);
            OutboxEventEntity processingEvent = outboxEventService.append(
                    payment, PaymentEventType.PAYMENT_PROCESSING_STARTED,
                    statusBeforeProcessing, "SUBMITTED_TO_PROVIDER",
                    processingCausationId, null, null);

            PaymentStatus statusBeforeResult = payment.getStatus();  // PROCESSING
            payment.applyProviderResult(providerResult);  // PROCESSING → UNKNOWN

            if (payment.getStatus() != statusBeforeResult) {
                PaymentEventType eventType = PaymentEventType.forStatus(payment.getStatus());
                OutboxEventEntity resultEvent = outboxEventService.append(
                        payment, eventType,  // PaymentUnknown
                        statusBeforeResult, "PROVIDER_UNKNOWN_OUTCOME",
                        processingEvent.getEventId(), null, null);
            }

            entity.updateFromDomain(payment);
            paymentRepository.flush();
            return payment;

        } catch (ObjectOptimisticLockingFailureException e) {
            // retry up to 3 times
        }
    }
}
```

For Razorpay, `providerResult.type() == Type.UNKNOWN`:
- `Payment.markProcessing()` transitions `CREATED → PROCESSING`
- `Payment.applyProviderResult(result)` transitions `PROCESSING → UNKNOWN`
- Two outbox events: `PaymentProcessingStarted` + `PaymentUnknown`
- `providerReference` is set to the Razorpay order ID (`response.id()`)

**File:** `domain/payment/Payment.java:149-176`

```java
public void applyProviderResult(final ProviderResult result) {
    if (this.status.isTerminal()) {
        if (this.providerReference == null && result.providerReference() != null) {
            this.providerReference = result.providerReference();
        }
        return;  // idempotent no-op for terminal payments
    }

    PaymentStatus target = mapResultToStatus(result.type());
    PaymentStateEngine.TransitionReason reason = mapResultToReason(result.type());

    this.status = PaymentStateEngine.transition(this.status, target, reason);

    if (result.providerReference() != null) {
        this.providerReference = result.providerReference();  // Razorpay order ID
    }
    touch();
}
```

`mapResultToStatus(Type.UNKNOWN)` returns `PaymentStatus.UNKNOWN` (line 297). `PaymentStateEngine.transition(PROCESSING, UNKNOWN, PROVIDER_UNKNOWN_OUTCOME)` is valid.

---

## 3. Webhook: payment.captured → SUCCEEDED

**Trigger:** Razorpay sends a webhook to `POST /webhooks/razorpay`.

### Step 1: Webhook Controller Entry

**File:** `api/controller/RazorpayWebhookController.java:58-84`

```java
@PostMapping("/razorpay")
public ResponseEntity<Void> handleWebhook(
        @RequestBody final String rawBody,
        @RequestHeader(value = "X-Razorpay-Signature", required = true)
        @NotBlank final String signature) {

    if (!RazorpaySignatureVerifier.verify(
            signature, rawBody.getBytes(StandardCharsets.UTF_8),
            properties.getWebhookSecret())) {
        log.warn("Invalid Razorpay webhook signature — rejecting request");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();  // 403
    }

    try {
        RazorpayWebhookEvent event = objectMapper.readValue(rawBody, RazorpayWebhookEvent.class);
        log.info("Received Razorpay webhook: event={}, orderId={}",
                event.event(),
                event.payload() != null && event.payload().payment() != null
                        ? event.payload().payment().orderId() : "unknown");

        handleEvent(event);
        return ResponseEntity.ok().build();  // 200
    } catch (Exception e) {
        log.error("Failed to process Razorpay webhook: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();  // 500
    }
}
```

The `@RequestBody String rawBody` ensures the raw bytes are used for signature verification — Jackson does not pre-parse and alter the body.

### Step 2: Signature Verification

**File:** `infrastructure/external/provider/razorpay/RazorpaySignatureVerifier.java:37-56`

```java
public static boolean verify(final String signature,
                             final byte[] rawBody,
                             final String webhookSecret) {
    if (signature == null || signature.isBlank()
            || rawBody == null || rawBody.length == 0
            || webhookSecret == null || webhookSecret.isBlank()) {
        return false;
    }
    try {
        Mac mac = Mac.getInstance(HMAC_SHA256);  // "HmacSHA256"
        mac.init(new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
        byte[] digest = mac.doFinal(rawBody);
        String expected = HexFormat.of().formatHex(digest);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));  // constant-time
    } catch (Exception e) {
        return false;
    }
}
```

- HMAC-SHA256 of the raw body, using `webhookSecret` as the key
- Hex-encoded using `java.util.HexFormat` (Java 17+)
- Constant-time comparison via `MessageDigest.isEqual`

### Step 3: Event Dispatch

**File:** `api/controller/RazorpayWebhookController.java:86-122`

```java
private void handleEvent(final RazorpayWebhookEvent event) {
    if (event.payload() == null || event.payload().payment() == null) {
        log.debug("Webhook has no payment payload — ignoring: event={}", event.event());
        return;
    }

    String orderId = event.payload().payment().orderId();
    if (orderId == null || orderId.isBlank()) {
        log.warn("Webhook payload has no order_id — cannot correlate: event={}", event.event());
        return;
    }

    ProviderResult result = switch (event.event()) {
        case "payment.captured", "order.paid" ->
                ProviderResult.success(orderId);
        case "payment.failed" ->
                ProviderResult.declined(
                        event.payload().payment().errorCode() != null
                                ? event.payload().payment().errorCode()
                                : "PAYMENT_FAILED",
                        event.payload().payment().errorDescription() != null
                                ? event.payload().payment().errorDescription()
                                : "Razorpay reported payment failed");
        case "payment.authorized" -> {
            log.info("Payment authorized (not yet captured) for order={} — no state change", orderId);
            yield null;
        }
        default -> {
            log.debug("Unhandled Razorpay event type: {} for order={}", event.event(), orderId);
            yield null;
        }
    };

    if (result != null) {
        chargeService.onProviderWebhook(orderId, result);
    }
}
```

For `payment.captured`:
- `orderId` = `"order_DaZlswtdcn9UNV"` (the Razorpay order ID)
- `result` = `ProviderResult.success("order_DaZlswtdcn9UNV")`

### Step 4: Webhook Resolution

**File:** `application/service/ChargeService.java:381-421`

```java
@Transactional
public void onProviderWebhook(final String providerReference, final ProviderResult result) {
    PaymentEntity entity = paymentRepository.findByProviderReference(providerReference)
            .orElseThrow(() -> new EntityNotFoundException(
                    "Payment not found for provider reference: " + providerReference));

    Payment payment = entity.toDomain(null);

    if (payment.getStatus().isTerminal()) {
        log.info("Webhook for payment {} already terminal ({}) — idempotent skip",
                payment.getPaymentId(), payment.getStatus());
        return;
    }

    PaymentStatus statusBefore = payment.getStatus();  // UNKNOWN
    payment.resolveReconciliation(
            mapResultToStatus(result.type()),           // ProviderResult.Type.SUCCESS → SUCCEEDED
            result.failureCode(),                       // null
            result.failureReason(),                     // null
            result.providerReference() != null
                    ? result.providerReference()        // "order_DaZlswtdcn9UNV"
                    : providerReference);               // (same value)

    PaymentEventType eventType = PaymentEventType.forStatus(payment.getStatus());  // PaymentSucceeded
    String reason = reasonFor(result.type());  // "PROVIDER_SUCCESS"
    outboxEventService.append(
            payment, eventType, statusBefore, reason,
            null, null, null);

    entity.updateFromDomain(payment);
    paymentRepository.flush();

    log.info("Webhook resolved payment {}: {} → {} (event={})",
            payment.getPaymentId(), statusBefore, payment.getStatus(), eventType.wireValue());
}
```

`Payment.resolveReconciliation()` (line 245-259):

```java
public void resolveReconciliation(final PaymentStatus resolved,
                                  final String failureCode,
                                  final String failureReason,
                                  final String providerReference) {
    this.status = PaymentStateEngine.transition(
            this.status, resolved,
            resolved == PaymentStatus.SUCCEEDED
                    ? PaymentStateEngine.TransitionReason.RECONCILIATION_MATCHED
                    : PaymentStateEngine.TransitionReason.RECONCILIATION_FAILED);

    if (providerReference != null) this.providerReference = providerReference;
    if (failureCode != null) this.failureCode = failureCode;
    if (failureReason != null) this.failureReason = failureReason;
    touch();
}
```

Transition: `UNKNOWN → SUCCEEDED` (via `RECONCILIATION_MATCHED`). This is valid per the state machine.

### Step 5: Outbox Event Published

The `PaymentSucceeded` outbox event is appended inside the same `@Transactional` method. The `OutboxPublisher` (scheduled, but `@EnableScheduling` is currently missing — see [Known Issues](stage-5-status.html)) will later publish it to Kafka's `payment.events` topic.

---

## 4. Webhook: payment.failed → FAILED

Same flow as §3, but:

- `event.event()` = `"payment.failed"`
- `result` = `ProviderResult.declined(errorCode, errorDescription)`
- `mapResultToStatus(Type.DECLINED)` → `PaymentStatus.FAILED`
- Transition: `UNKNOWN → FAILED` (via `RECONCILIATION_FAILED`)
- Outbox event: `PaymentFailed`
- `failureCode` and `failureReason` are set on the payment

**File:** `application/service/ChargeService.java:423-439`

```java
private static PaymentStatus mapResultToStatus(final ProviderResult.Type type) {
    return switch (type) {
        case SUCCESS -> PaymentStatus.SUCCEEDED;
        case DECLINED -> PaymentStatus.FAILED;
        case TECHNICAL_FAILURE -> PaymentStatus.FAILED;
        case UNKNOWN -> PaymentStatus.UNKNOWN;
    };
}
```

---

## 5. Duplicate Webhook (Idempotent Resolution)

**Scenario:** Razorpay sends a duplicate `payment.captured` webhook.

### Step 1: Signature Verification + Event Dispatch

Same as §3 Steps 1-3. The signature is re-verified (Razorpay may resend webhooks).

### Step 2: Idempotent Skip

**File:** `application/service/ChargeService.java:389-393`

```java
if (payment.getStatus().isTerminal()) {
    log.info("Webhook for payment {} already terminal ({}) — idempotent skip",
            payment.getPaymentId(), payment.getStatus());
    return;
}
```

Since the payment is already `SUCCEEDED` (terminal), the webhook handler returns immediately. No outbox event is appended. The HTTP 200 response is sent to Razorpay, acknowledging receipt.

**Why this is safe:**
- The `providerReference` (Razorpay order ID) is already set on the terminal payment
- `Payment.applyProviderResult()` also has an idempotent terminal check (line 150)
- The outbox event is NOT re-appended (the status didn't change)

---

## 6. Order Creation HTTP 400 (Declined)

**Scenario:** Razorpay rejects the order creation request (e.g. invalid amount, unsupported currency).

### Step 1: HTTP Error Thrown

**File:** `infrastructure/external/provider/razorpay/RazorpayPaymentProcessor.java:88-122`

```java
try {
    RazorpayOrderResponse response = restClient.post()
            .uri("/orders")
            .body(request)
            .retrieve()
            .body(RazorpayOrderResponse.class);  // throws RestClientResponseException on 4xx/5xx
    ...
} catch (RestClientResponseException e) {
    log.error("Razorpay API error for receipt={}: {} {}",
            maskKey(providerIdempotencyKey), e.getStatusCode(), e.getMessage());
    return mapHttpError(e, providerIdempotencyKey);
}
```

### Step 2: HTTP Status Mapping

**File:** `infrastructure/external/provider/razorpay/RazorpayPaymentProcessor.java:145-166`

```java
private static ProviderResult mapHttpError(final RestClientResponseException e,
                                           final String providerIdempotencyKey) {
    int code = e.getStatusCode().value();
    if (code == 400 || code == 409) {
        return ProviderResult.declined(
                "RAZORPAY_REJECTED",
                "Razorpay rejected order creation (HTTP " + code + ")");
    }
    if (code == 401 || code == 403) {
        return ProviderResult.technicalFailure(
                "AUTH_ERROR",
                "Razorpay authentication failed (HTTP " + code + ")");
    }
    if (code >= 500) {
        return ProviderResult.technicalFailure(
                "RAZORPAY_ERROR",
                "Razorpay server error (HTTP " + code + ")");
    }
    return ProviderResult.unknown("HTTP_" + code, "Unexpected HTTP response: " + code);
}
```

For HTTP 400:
- `ProviderResult.declined("RAZORPAY_REJECTED", ...)`
- `Type.DECLINED` → `PaymentStatus.FAILED` (no money moved — provider rejected the order)

### Step 3: TX2 Applies the Result

`ChargeService.applyProviderResult()` transitions `PROCESSING → FAILED`:
- `markProcessing()` first: `CREATED → PROCESSING`
- `applyProviderResult(result)`: `PROCESSING → FAILED` (via `PROVIDER_DECLINED`)
- Outbox events: `PaymentProcessingStarted` + `PaymentFailed`
- Idempotency finalized with `isTerminal=true`

---

## 7. Order Creation HTTP 401 (Auth Error)

Same flow as §6, but:
- `mapHttpError()`: HTTP 401 → `ProviderResult.technicalFailure("AUTH_ERROR", ...)`
- `Type.TECHNICAL_FAILURE` → `PaymentStatus.FAILED`
- This is a configuration issue — the `app.razorpay.key-id` or `key-secret` is incorrect
- The payment transitions to `FAILED` and is visible for investigation
- Recovery will retry with the same credentials — operator intervention needed to fix config

---

## 8. Order Creation HTTP 500 (Server Error)

Same flow as §6, but:
- `mapHttpError()`: HTTP 5xx → `ProviderResult.technicalFailure("RAZORPAY_ERROR", ...)`
- `Type.TECHNICAL_FAILURE` → `PaymentStatus.FAILED`
- Safety: `TECHNICAL_FAILURE` maps to `FAILED` because the order was not created — no money moved
- Recovery scheduler will retry with the same `receipt` (idempotency key)

---

## 9. Recovery: Stuck UNKNOWN Payment

**Scenario:** Order was created in Razorpay, but the gateway crashed before TX2 committed. The payment is in `CREATED` (not `UNKNOWN` — TX2 didn't apply the result yet).

### Step 1: Recovery Scheduler Finds the Payment

**File:** `application/service/PaymentRecoveryService.java:70-101` (simplified)

```java
@Transactional
public int sweep(final Instant now) {
    Instant cutoff = now.minusMillis(properties.getCreatedTimeoutMs());  // default 60s
    List<PaymentEntity> candidates = paymentRepository.findForRecovery(cutoff, now);
    ...
}
```

The payment is found because it's in `CREATED` status, created before the cutoff, and has no `next_retry_at`.

### Step 2: Recovery Re-Submits to Provider

**File:** `application/service/PaymentRecoveryService.java:124-215` (simplified)

```java
// 1. Lock the payment
PaymentEntity locked = paymentRepository.findAndLockByPaymentId(entity.getPaymentId());
Payment payment = locked.toDomain(null);

// 2. Ensure stable provider idempotency key
payment.ensureProviderIdempotencyKey();  // "prov_<paymentId>" — already set, no-op

// 3. Schedule backoff
long backoff = Math.min(
    properties.getBaseBackoffMs() * (1L << payment.getAttemptCount()),
    properties.getMaxBackoffMs());
Instant nextRetryAt = now.plusMillis(backoff);

// 4. Re-enter PROCESSING
payment.markRetrySubmitted();  // or markProcessing() for CREATED

// 5. Append outbox events
outboxEventService.append(payment, PaymentEventType.PAYMENT_RETRY_SCHEDULED, ...);
outboxEventService.append(payment, PaymentEventType.PAYMENT_PROCESSING_STARTED, ...);

// 6. Update
locked.updateFromDomain(payment);
paymentRepository.flush();
```

### Step 3: Provider Idempotency

On the next charge attempt (via `chargeWithOutcome` or a manual retry):

1. `Payment.ensureProviderIdempotencyKey()` returns the existing `prov_<paymentId>` (already set, no-op)
2. `RazorpayPaymentProcessor.process()` sends the same `receipt` (UUID from `prov_` key) to `POST /v1/orders`
3. Razorpay recognizes the duplicate `receipt` and returns the **existing order** — no duplicate charge
4. The gateway applies `UNKNOWN` (order exists, awaiting payment) — payment remains `UNKNOWN`

### Recovery Gap

As documented in `docs/interview-preparation/full-project-flow.md` Flow 12, the current `PaymentRecoveryService.recoverPayment()` does **not** call `processor.process()` — it only transitions to `PROCESSING` and schedules a retry. For the Razorpay integration, this means:
- Recovery marks the payment as retrying but doesn't actively re-check the Razorpay order status
- Webhook delivery remains the primary resolution mechanism
- A manual or future enhancement would call `processor.process()` and then `applyProviderResult()`

---

## Appendix: Key File Locations

| Component | File | Key Lines |
|---|---|---|
| REST client configuration | `RazorpayConfiguration.java` | 20-33 |
| Order request record | `RazorpayOrderRequest.java` | 16-23 |
| Order response record | `RazorpayOrderResponse.java` | 22-35 |
| Provider processor | `RazorpayPaymentProcessor.java` | 47-181 |
| Signature verifier | `RazorpaySignatureVerifier.java` | 22-57 |
| Webhook controller | `RazorpayWebhookController.java` | 42-123 |
| Webhook event records | `RazorpayWebhookEvent.java` | 17-40 |
| Config properties | `RazorpayProperties.java` | 13-71 |
| Webhook handler service | `ChargeService.java` | 381-421 |
| State transition logic | `Payment.java` | 149-176, 245-259 |
| State machine | `PaymentStateEngine.java` | 61-105 |
| ProviderResult type | `ProviderResult.java` | 18-62 |
| Idempotency key generation | `Payment.java` | 218-223 |
| Receipt truncation logic | `RazorpayPaymentProcessor.java` | 130-143 |
| HTTP error mapping | `RazorpayPaymentProcessor.java` | 145-166 |