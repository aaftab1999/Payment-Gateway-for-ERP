# Razorpay Orders API Integration

## 1. Overview

The gateway supports integration with the **Razorpay Orders API** as an alternative provider to the in-process `SimulatedPaymentProcessor`. The integration is activated through a feature flag (`app.razorpay.enabled`) and follows the same hexagonal architecture: the `PaymentProcessor` SPI is the port, and `RazorpayPaymentProcessor` is the adapter in `infrastructure.external.provider.razorpay`.

### Dual-Provider Design

| Flag | Active Processor | Bean Condition |
|---|---|---|
| `app.razorpay.enabled=false` (default) | `SimulatedPaymentProcessor` | `@ConditionalOnProperty(name="app.razorpay.enabled", havingValue="false", matchIfMissing=true)` |
| `app.razorpay.enabled=true` | `RazorpayPaymentProcessor` | `@ConditionalOnProperty(name="app.razorpay.enabled", havingValue="true")` |

Only one `PaymentProcessor` bean exists in the application context at any time, so `ChargeService` — which injects a single `PaymentProcessor` — requires no wiring changes when switching providers.

### Async-vs-Sync Architecture

The Razorpay integration is **asynchronous by design**, unlike the synchronous simulator:

- **Order creation** (`POST /v1/orders`) creates a Razorpay order in `"created"` status. The actual payment capture happens on the customer's frontend (Razorpay Checkout or Payment Button).
- The gateway returns `ProviderResult.Type.UNKNOWN` after order creation, placing the payment in a non-terminal awaiting-resolution state.
- **Webhook callbacks** (`payment.captured`, `payment.failed`, `order.paid`) resolve the payment to a terminal state via `ChargeService.onProviderWebhook()`.

This mirrors the timeout/unknown semantics of the simulated provider but replaces the polling/recovery path with real webhook callbacks from Razorpay.

---

## 2. Configuration

### Properties (`RazorpayProperties.java`)

All configuration is bound via `@ConfigurationProperties(prefix = "app.razorpay")`:

| Property | Default | Description |
|---|---|---|
| `app.razorpay.enabled` | `false` | Feature flag for the Razorpay provider |
| `app.razorpay.key-id` | `""` | Razorpay API key ID (starts with `rzp_`) |
| `app.razorpay.key-secret` | `""` | Razorpay API key secret |
| `app.razorpay.webhook-secret` | `""` | HMAC-SHA256 secret for webhook signature verification |
| `app.razorpay.base-url` | `https://api.razorpay.com/v1` | Razorpay Orders API base URL |

Properties are available in both `application.yml` (shared defaults) and `application-local.yml` (local development). Environment variables follow Spring Boot relaxed binding (e.g. `APP_RAZORPAY_KEY_ID`).

### REST Client Configuration (`RazorpayConfiguration.java`)

A `RestClient` bean is created with:
- **Base URL** from `app.razorpay.base-url`
- **Authentication** via HTTP Basic Auth (`keyId:keySecret` Base64-encoded)
- **Content-Type** header set to `application/json`

The bean is conditionally created only when `app.razorpay.enabled=true`.

---

## 3. Components

### 3.1 Request: `RazorpayOrderRequest.java`

A Java record serializing to the Razorpay `POST /v1/orders` request body:

| Field | JSON Property | Type | Description |
|---|---|---|---|
| `amount` | `amount` | `long` | Amount in **minor units** (paise for INR) |
| `currency` | `currency` | `String` | ISO 4217 code (e.g. `"INR"`) |
| `receipt` | `receipt` | `String` | ERP-side reference / idempotency key (≤ 40 chars) |
| `notes` | `notes` | `Map<String, String>` | Key-value metadata |
| `paymentCapture` | `payment_capture` | `int` | `1` = automatic capture, `0` = manual |

The `payment_capture` field is mapped via `@JsonProperty("payment_capture")` to the snake_case JSON convention used by Razorpay's API.

### 3.2 Response: `RazorpayOrderResponse.java`

A Java record deserializing the Razorpay `POST /v1/orders` response body:

| Field | JSON Property | Type | Description |
|---|---|---|---|
| `id` | `id` | `String` | Razorpay order ID (e.g. `"order_DaZlswtdcn9UNV"`) |
| `entity` | `entity` | `String` | Always `"order"` |
| `amount` | `amount` | `long` | Amount in minor units |
| `amountPaid` | `amount_paid` | `long` | Amount captured so far (0 if not captured) |
| `amountDue` | `amount_due` | `long` | Remaining amount to capture |
| `currency` | `currency` | `String` | ISO 4217 currency code |
| `receipt` | `receipt` | `String` | The receipt value sent in the request |
| `status` | `status` | `String` | `"created"` \| `"attempted"` \| `"paid"` |
| `attempts` | `attempts` | `int` | Number of payment attempts |
| `notes` | `notes` | `Map<String, String>` | Metadata echoed from request |
| `createdAt` | `created_at` | `long` | Unix timestamp of order creation |

### 3.3 Processor: `RazorpayPaymentProcessor.java`

Implements `PaymentProcessor.process()` with the following flow:

1. **Compute receipt**: Convert `providerIdempotencyKey` to a Razorpay-compliant receipt (≤ 40 chars) by stripping the `prov_` prefix.
2. **Build request**: Create a `RazorpayOrderRequest` with `payment_capture=1` (automatic capture).
3. **Send HTTP POST**: To `{baseUrl}/orders` with Basic auth.
4. **Handle response**: Map the response to a `ProviderResult`.

#### ProviderResult Mapping

| Scenario | ProviderResult.Type | failureCode | providerReference |
|---|---|---|---|
| Order created successfully | `UNKNOWN` | `"ORDER_CREATED"` | Razorpay order ID |
| Empty response body | `UNKNOWN` | `"EMPTY_RESPONSE"` | `null` |
| HTTP 400 Bad Request | `DECLINED` | `"RAZORPAY_REJECTED"` | `null` |
| HTTP 409 Conflict | `DECLINED` | `"RAZORPAY_REJECTED"` | `null` |
| HTTP 401 Unauthorized | `TECHNICAL_FAILURE` | `"AUTH_ERROR"` | `null` |
| HTTP 403 Forbidden | `TECHNICAL_FAILURE` | `"AUTH_ERROR"` | `null` |
| HTTP 5xx Server Error | `TECHNICAL_FAILURE` | `"RAZORPAY_ERROR"` | `null` |
| Other HTTP errors | `UNKNOWN` | `"HTTP_{code}"` | `null` |
| Connection/timeout exception | `TECHNICAL_FAILURE` | `"PROVIDER_EXCEPTION"` | `null` |

**Why UNKNOWN on success:** The order is created, but the customer has not yet paid. The payment is in a non-terminal state awaiting webhook resolution. Returning `UNKNOWN` ensures the payment is not marked terminal prematurely and the recovery/reconciliation path remains available.

### 3.4 Webhook Controller: `RazorpayWebhookController.java`

Exposed at `POST /webhooks/razorpay` (only when `app.razorpay.enabled=true`).

**Webhook verification flow:**

1. Receive raw request body as `@RequestBody String` (prevents Jackson pre-parsing that could alter the body).
2. Extract `X-Razorpay-Signature` header.
3. Verify signature using `RazorpaySignatureVerifier.verify()`.
4. If invalid → HTTP 403 Forbidden.
5. If valid → parse JSON body into `RazorpayWebhookEvent` and dispatch.

**Event → ProviderResult mapping:**

| Razorpay Event | ProviderResult | Payment Transition |
|---|---|---|
| `payment.captured` | `ProviderResult.success(orderId)` | UNKNOWN → SUCCEEDED |
| `order.paid` | `ProviderResult.success(orderId)` | UNKNOWN → SUCCEEDED |
| `payment.failed` | `ProviderResult.declined(errorCode, errorDescription)` | UNKNOWN → FAILED |
| `payment.authorized` | *(no-op / null)* | No state change |
| *(unknown event)* | *(no-op / null)* | Logged and ignored |

The webhook handler delegates to `ChargeService.onProviderWebhook(providerReference, result)`, which:
- Looks up the payment by `providerReference` (the Razorpay order ID).
- Skips if the payment is already terminal (idempotent).
- Calls `Payment.resolveReconciliation()` to transition to the target status.
- Appends the appropriate outbox event.

### 3.5 Signature Verifier: `RazorpaySignatureVerifier.java`

Verifies HMAC-SHA256 webhook signatures:

```
signature = HMAC-SHA256(webhookSecret, rawRequestBody)  →  lowercase hex string
```

Properties:
- Uses `javax.crypto.Mac` with `HmacSHA256`.
- Hex-encodes the digest using `java.util.HexFormat` (Java 17+).
- Compares using `MessageDigest.isEqual` (constant-time, prevents timing attacks).
- Returns `false` for any null/blank inputs.

### 3.6 Webhook Event: `RazorpayWebhookEvent.java`

A nested record structure deserializing Razorpay's webhook JSON:

```java
record RazorpayWebhookEvent(
    String event,        // e.g. "payment.captured"
    String accountId,    // Razorpay account ID
    RazorpayWebhookPayload payload,
    long createdAt       // Unix timestamp
)

record RazorpayWebhookPayload(RazorpayPaymentEntity payment)

record RazorpayPaymentEntity(
    String id,           // Payment ID (e.g. "pay_xxx")
    String orderId,      // Order ID (our providerReference)
    String status,       // "created" | "authorized" | "captured" | "failed"
    long amount,         // Amount in minor units
    String currency,
    String method,       // Payment method
    String resultCode,
    String errorCode,
    String errorDescription
)
```

---

## 4. Idempotency

### 4.1 Idempotency Key Flow

```
Payment.ensureProviderIdempotencyKey()
    → "prov_" + paymentId (UUID)
```

The gateway's `providerIdempotencyKey` (format `prov_<UUID>`, 41 characters) is derived deterministically from the payment ID. This key is:
- Set once on the payment aggregate (in `Payment.create()`)
- Stable across retries and application restarts
- Passed to the provider as the `receipt` field

### 4.2 Receipt Truncation

Razorpay requires the `receipt` field to be **≤ 40 characters**. The gateway's `providerIdempotencyKey` is 41 characters (`prov_` prefix + 36-char UUID). The `toRazorpayReceipt()` method in `RazorpayPaymentProcessor` strips the `prov_` prefix, yielding a 36-character UUID — within the limit.

If the key is shorter than the prefix or doesn't contain it, the method falls back to using the key directly (truncated to 40 chars if needed).

### 4.3 Provider-Side Idempotency

Razorpay's `receipt` field acts as a server-side idempotency key. A duplicate `POST /orders` request with the same `receipt` returns the existing order instead of creating a new one. This mirrors the contract of the `SimulatedPaymentProcessor` (which caches results by `providerIdempotencyKey`).

---

## 5. Payment Flow

### 5.1 Charge Flow (Synchronous API Request)

```
ERP → PaymentController.createPayment
    → ChargeService.chargeWithOutcome
        ├─ TX1: Create payment (CREATED) + reserve idempotency + outbox(PaymentCreated)
        ├─ provider.process()  ← RazorpayPaymentProcessor
        │   ├─ POST /v1/orders with receipt=providerIdempotencyKey
        │   ├─ Provider returns order_id
        │   └─ Returns ProviderResult.UNKNOWN(providerReference=order_id)
        └─ TX2: markProcessing → applyProviderResult → UNKNOWN
            + outbox(PaymentProcessingStarted, PaymentUnknown)
            + idempotency.finalize(responseStatus=202)
```

**Key difference from the simulator:** The provider call returns `UNKNOWN` immediately (the customer still needs to complete the payment on Razorpay's frontend). The payment remains in `UNKNOWN` state until a webhook resolves it.

### 5.2 Webhook Resolution Flow (Asynchronous)

```
Razorpay → POST /webhooks/razorpay
    → RazorpayWebhookController
        ├─ Verify X-Razorpay-Signature (HMAC-SHA256)
        ├─ Parse RazorpayWebhookEvent
        ├─ Map event → ProviderResult:
        │   payment.captured / order.paid → ProviderResult.success(orderId)
        │   payment.failed → ProviderResult.declined(errorCode, errorDescription)
        │   payment.authorized → no-op
        └─ ChargeService.onProviderWebhook(orderId, result)
            ├─ Lookup payment by providerReference (Razorpay order ID)
            ├─ Skip if already terminal (idempotent)
            ├─ Payment.resolveReconciliation(targetStatus, ...)
            └─ Append outbox(PaymentSucceeded/Failed) + flush
```

### 5.3 ProviderResult → PaymentStatus Mapping

| ProviderResult.Type | mapResultToStatus (Payment.java) | mapResultToStatus (ChargeService.java) | State Transition |
|---|---|---|---|
| `SUCCESS` | `SUCCEEDED` | `SUCCEEDED` | UNKNOWN → SUCCEEDED |
| `DECLINED` | `FAILED` | `FAILED` | UNKNOWN → FAILED |
| `TECHNICAL_FAILURE` | `FAILED` | `FAILED` | UNKNOWN → FAILED |
| `UNKNOWN` | `UNKNOWN` | `UNKNOWN` | No change (stays awaiting) |

Both `Payment.applyProviderResult()` and `ChargeService.mapResultToStatus()` use the same mapping, ensuring consistency between the synchronous charge flow and the async webhook resolution flow.

---

## 6. Recovery

### 6.1 Recovery Scheduler

The existing `RecoveryScheduler` and `PaymentRecoveryService` handle payments stuck in non-terminal states. When `app.razorpay.enabled=true`:

1. Payments in `UNKNOWN` after order creation are found by `PaymentRepository.findForRecovery()`.
2. Recovery re-enters `PROCESSING` and schedules a retry.
3. The `providerIdempotencyKey` is stable (`prov_<paymentId>`), so a re-submission to Razorpay's Orders API with the same `receipt` returns the existing order.

### 6.2 Recovery Gap

The same gap documented in `docs/interview-preparation/full-project-flow.md` Flow 12 applies: `PaymentRecoveryService.recoverPayment()` transitions the payment to `PROCESSING` but does **not** call `processor.process()` to re-submit to the provider. For the Razorpay integration, this means recovery schedules a retry but doesn't actively poll the provider. Webhooks remain the primary resolution mechanism.

### 6.3 No-REQUIRES_RECONCILIATION

The `REQUIRES_RECONCILIATION` state is not reachable from `applyProviderResult()` (which maps `TECHNICAL_FAILURE → FAILED`, not `→ REQUIRES_RECONCILIATION`). For the Razorpay integration, `TECHNICAL_FAILURE` (HTTP 5xx, auth errors) maps directly to `FAILED` — the order was not successfully created and a retry is appropriate.

---

## 7. Failure Scenarios and Handling

### 7.1 Order Creation Failure

| Error | ProviderResult | Payment Status | Recovery |
|---|---|---|---|
| HTTP 400 (invalid request) | `DECLINED` | `FAILED` | No retry (client error) |
| HTTP 401/403 (auth) | `TECHNICAL_FAILURE` | `FAILED` | Fix credentials, retry |
| HTTP 5xx | `TECHNICAL_FAILURE` | `FAILED` | Recovery retries |
| Connection timeout | `TECHNICAL_FAILURE` | `FAILED` | Recovery retries |
| Empty response | `UNKNOWN` | `UNKNOWN` | Recovery + webhook |

### 7.2 Webhook Failure

| Failure | Handling |
|---|---|
| Invalid signature | HTTP 403 — request rejected, no state change |
| Unknown event type | Logged at DEBUG, acknowledged (HTTP 200) |
| Missing order_id | Logged at WARN, acknowledged (HTTP 200), no state change |
| Payment not found | `EntityNotFoundException` → HTTP 500 (should be treated as non-retryable by Razorpay) |
| Duplicate webhook | Idempotent — `onProviderWebhook` checks `isTerminal()` and skips |

### 7.3 Idempotency During Recovery

If order creation succeeds but the gateway crashes before TX2 commits, the payment remains in `CREATED`. Recovery finds it, re-enters `PROCESSING`, and re-submits to Razorpay with the same `receipt` (idempotency key). Razorpay returns the existing order, and the gateway applies the `UNKNOWN` result consistently.

---

## 8. Security

### 8.1 Webhook Signature Verification

- **HMAC-SHA256** using the configured `webhook-secret`
- **Constant-time comparison** via `MessageDigest.isEqual` (prevents timing attacks)
- **Raw body verification** — the controller receives `@RequestBody String rawBody` and converts to bytes with `StandardCharsets.UTF_8` before hashing. This prevents Jackson pre-parsing from altering the body.
- **Null/blank safety** — returns `false` for any incomplete inputs

### 8.2 Credential Handling

- API key ID and secret are Base64-encoded for HTTP Basic Auth (Razorpay's preferred authentication method)
- The webhook secret is stored separately from the API credentials (different security contexts)
- All sensitive values are masked in logs via `maskKey()` and `maskId()` helper methods

### 8.3 PCI Scope

The gateway does not handle raw card data. Payment tokens are passed through to the provider, and `paymentToken` is `@Transient` on the JPA entity (never persisted). For the Razorpay integration, the customer completes payment on Razorpay's hosted Checkout page — card data never touches the gateway.

---

## 9. Database

### 9.1 Provider Reference Storage

The Razorpay order ID is stored in `Payment.providerReference`, which has a unique constraint (`uq_payment_provider_ref` — `UNIQUE (provider_ref) WHERE provider_ref IS NOT NULL`). The `PaymentRepository.findByProviderReference(String)` method was added to support webhook lookup.

### 9.2 Flyway Migrations

No Razorpay-specific migrations are required. The integration reuses the existing `payment` table schema. The `provider_idempotency_key` and `provider_reference` columns (added in V3) are sufficient to support the Razorpay flow.

### 9.3 Outbox Events

Webhook resolution triggers outbox events via `ChargeService.onProviderWebhook()`:

| Webhook Event | Outbox Event |
|---|---|
| `payment.captured` / `order.paid` | `PaymentSucceeded` |
| `payment.failed` | `PaymentFailed` |

These events are published to Kafka via the existing `OutboxPublisher`, reaching the ERP consumer just like synchronous charge results.

---

## 10. Testing

### 10.1 `RazorpaySignatureVerifierTest` (7 tests)

| Test | Scenario |
|---|---|
| `validSignatureReturnsTrue` | Correct HMAC signature verifies successfully |
| `tamperedBodyReturnsFalse` | Modified body fails verification |
| `wrongSecretReturnsFalse` | Different webhook secret fails |
| `nullSignatureReturnsFalse` | Null signature header rejected |
| `nullBodyReturnsFalse` | Null request body rejected |
| `nullSecretReturnsFalse` | Null webhook secret rejected |
| `blankInputsReturnFalse` | Blank/empty inputs rejected |

Uses real `HmacSHA256` MAC computation to generate valid signatures for testing.

### 10.2 `RazorpayPaymentProcessorTest` (6 tests)

Uses `MockRestServiceServer` (Spring's `MockRestServiceServer.bindTo(RestClient.Builder)`) to mock HTTP responses:

| Test | Scenario | Expected Result |
|---|---|---|
| `createsOrderAndReturnsUnknownWithOrderId` | HTTP 200 with order JSON | `UNKNOWN`, providerReference = order ID, failureCode = `"ORDER_CREATED"` |
| `providerIdempotencyKeyIsMappedToReceipt` | Same as above | Verifies `receipt` field contains UUID (stripped of `prov_` prefix) |
| `emptyResponseReturnsUnknown` | HTTP 200 with empty body | `UNKNOWN`, failureCode = `"EMPTY_RESPONSE"` |
| `http500ReturnsTechnicalFailure` | HTTP 500 | `TECHNICAL_FAILURE`, failureCode = `"RAZORPAY_ERROR"`, reason contains "500" |
| `http401ReturnsTechnicalFailureAuthError` | HTTP 401 | `TECHNICAL_FAILURE`, failureCode = `"AUTH_ERROR"` |
| `http400ReturnsDeclined` | HTTP 400 | `DECLINED`, failureCode = `"RAZORPAY_REJECTED"`, `isConfirmedFailure() = true` |

### 10.3 Test Coverage Gaps

No webhook controller integration test exists. A future test should:
1. Start a test server that accepts `POST /orders` and sends webhook callbacks
2. Verify the full flow: order creation → webhook → resolution → terminal state

---

## 11. Razorpay API Reference

### 11.1 Order Creation: `POST /v1/orders`

**Request:**
```json
{
  "amount": 125000,
  "currency": "INR",
  "receipt": "550e8400-e29b-41d4-a716-446655440000",
  "notes": {
    "correlationId": "abc123"
  },
  "payment_capture": 1
}
```

**Response (HTTP 200):**
```json
{
  "id": "order_DaZlswtdcn9UNV",
  "entity": "order",
  "amount": 125000,
  "amount_paid": 0,
  "amount_due": 125000,
  "currency": "INR",
  "receipt": "550e8400-e29b-41d4-a716-446655440000",
  "status": "created",
  "attempts": 0,
  "notes": {"correlationId": "abc123"},
  "created_at": 1726886400
}
```

### 11.2 Webhook Endpoint

Razorpay sends webhooks to the configured URL as JSON POST requests with the `X-Razorpay-Signature` header:

```json
{
  "event": "payment.captured",
  "account_id": "acc_xyz",
  "payload": {
    "payment": {
      "id": "pay_abc123",
      "order_id": "order_DaZlswtdcn9UNV",
      "status": "captured",
      "amount": 125000,
      "currency": "INR",
      "method": "upi",
      "error_code": null,
      "error_description": null
    }
  },
  "created_at": 1726886400
}
```

### 11.3 Supported Event Types

| Event | Meaning | Gateway Action |
|---|---|---|
| `payment.captured` | Payment successfully captured | Resolve to SUCCEEDED |
| `order.paid` | Order fully paid | Resolve to SUCCEEDED |
| `payment.failed` | Payment failed | Resolve to FAILED |
| `payment.authorized` | Payment authorized (not yet captured) | No state change (awaiting capture) |
| `payment.queued` | Payment queued for processing | No state change (awaiting capture) |
| `payment.dropped` | Payment dropped (customer abandoned) | Resolve to FAILED |
| `payment.cancelled` | Payment cancelled | Resolve to FAILED |
| `payment.error` | Payment encountered an error | Resolve to FAILED |

---

## 12. Comparison: Simulated vs Razorpay Provider

| Aspect | SimulatedPaymentProcessor | RazorpayPaymentProcessor |
|---|---|---|
| Mode of operation | Synchronous — returns final result immediately | Asynchronous — order created, capture happens later via webhook |
| Outcome determination | Token prefix (`success:`, `decline:`, etc.) | Customer completes payment on Razorpay Checkout |
| Idempotency | In-memory `ConcurrentHashMap` cache by provider key | Razorpay `receipt` field (server-side idempotency) |
| Result type after order creation | `SUCCESS`/`DECLINED`/`UNKNOWN`/`TECHNICAL_FAILURE` | Always `UNKNOWN` (awaiting webhook) |
| Network calls | None (in-process) | HTTP POST to `api.razorpay.com` |
| Authentication | None | HTTP Basic Auth (`keyId:keySecret`) |
| Webhook verification | N/A | HMAC-SHA256 with `webhook-secret` |
| Recovery re-submission | Replays cached in-memory result | Re-creates order with same `receipt` (Razorpay returns existing) |
| Configuration | None (always active) | `app.razorpay.*` properties |
| Conditional | `@ConditionalOnProperty matchIfMissing=true` | `@ConditionalOnProperty havingValue="true"` |
