# Provider Simulator

## Overview

The `SimulatedPaymentProcessor` is an in-process implementation of the `PaymentProcessor` SPI. It serves as a stand-in for real payment providers (UPI, card networks, net banking gateways) during development and testing.

**File:** `src/main/java/com/paymentgateway/settlement/infrastructure/external/provider/SimulatedPaymentProcessor.java`

## How It Works

The simulator determines the outcome based on the **prefix** of the `paymentToken` field (the part before the first colon). This makes outcomes deterministic and testable — no random behavior.

### Token Convention

| Token Prefix | ProviderResult | Provider Reference | Payment Status |
|---|---|---|---|
| `success:` | `ProviderResult.success(...)` | `provider_txn_{correlationId}` | SUCCEEDED |
| (any other valid token) | `ProviderResult.success(...)` | `provider_txn_{correlationId}` | SUCCEEDED |
| `decline:` | `ProviderResult.declined(...)` | null | FAILED |
| `timeout:` | `ProviderResult.unknown(...)` | null | UNKNOWN |
| `error500:` | `ProviderResult.technicalFailure(...)` | null | FAILED |
| `connfail:` | `ProviderResult.technicalFailure(...)` | null | FAILED |
| `unknown:` | `ProviderResult.unknown(...)` | null | UNKNOWN |

### Example Tokens

```
success:test
decline:cust_123
timeout:gateway_timeout
error500:internal_server
connfail:refused
unknown:ambiguous
test_token_no_prefix    (defaults to success)
```

## Unknown Outcome Behavior

When the token prefix is `timeout:` or `unknown:`, the simulator returns `ProviderResult.unknown(...)`. This results in:

- `ProviderResult.Type.UNKNOWN`
- Payment status transitions to `UNKNOWN`
- `failureCode` and `failureReason` are set in the payment record
- `providerReference` remains null (no transaction ID from the provider)

### Why This Matters

A timeout or unknown outcome means the provider may have debited the customer but not responded. The payment is kept in `UNKNOWN` state (non-terminal). The reconciliation service (Stage 4+) must poll the provider to determine the actual outcome:

- If the provider confirms the payment succeeded → `resolveUnknown(SUCCEEDED)`
- If the provider confirms the payment failed → `resolveUnknown(FAILED)`

This prevents double-charging: the gateway does not retry the charge automatically because it cannot distinguish between a provider timeout (payment may have succeeded) and a provider failure (payment definitely failed).

## Security

The `paymentToken` is **masked in logs** — only the scenario prefix is shown, never the full token:

```
DEBUG Processing simulated payment: token=success:XXXXX, amountMinor=125000, currency=INR, correlationId=...
```

The `maskToken()` method extracts only the prefix (before the first colon) and replaces the rest with `*****`.

## SimulatedPaymentProcessorFactory

The project includes a `SimulatedPaymentProcessorFactory` that allows selecting a processor by `PaymentMethodType`. Currently, the factory returns the same `SimulatedPaymentProcessor` for all payment methods. In a real system, this would route to different provider-specific adapters (e.g., a UPI provider adapter, a card network adapter).

## ProviderResult Mapping

| Simulator Scenario | ProviderResult.Type | ProviderResult Methods Used |
|---|---|---|
| success / default | `SUCCESS` | `ProviderResult.success(providerReference)` |
| decline | `DECLINED` | `ProviderResult.declined(failureCode, failureReason)` |
| timeout | `UNKNOWN` | `ProviderResult.unknown(failureCode, failureReason)` |
| error500 / connfail | `TECHNICAL_FAILURE` | `ProviderResult.technicalFailure(failureCode, failureReason)` |
| unknown | `UNKNOWN` | `ProviderResult.unknown(failureCode, failureReason)` |

## Limitations

1. **No real provider connectivity** — the simulator is in-process and does not make HTTP calls.
2. **No real token validation** — any token starting with `success:` produces a success, regardless of format.
3. **No latency simulation by method** — all payment methods have identical behavior.
4. **No actual money movement** — this is a development/test tool only.
5. **No network failure simulation** — `connfail:` produces a `TECHNICAL_FAILURE` result, but does not simulate actual connection-level failures (timeouts, DNS failures, etc.).
6. **Deterministic only** — outcomes are fully determined by the token prefix. There is no random or probabilistic behavior.

## Adding New Scenarios

To add a new simulation scenario:

1. Add a `case` to the `switch` statement in `SimulatedPaymentProcessor.process()`.
2. Return the appropriate `ProviderResult` type.
3. Document the new prefix in the Javadoc table.
4. Add a test case in `SimulatedPaymentProcessorTest`.

## Switching to Razorpay Provider

The `SimulatedPaymentProcessor` is active by default (`app.razorpay.enabled=false`). To switch to the Razorpay Orders API integration, set `app.razorpay.enabled=true` and provide the required credentials in `application.yml`:

```yaml
app:
  razorpay:
    enabled: true
    key-id: "rzp_test_..."
    key-secret: "..."
    webhook-secret: "..."
    base-url: "https://api.razorpay.com/v1"
```

When enabled:
- `SimulatedPaymentProcessor`'s `@ConditionalOnProperty(matchIfMissing=true)` excludes it
- `RazorpayPaymentProcessor`'s `@ConditionalOnProperty(havingValue="true")` activates it
- `RazorpayConfiguration` creates the `RestClient` bean for API calls
- `RazorpayWebhookController` exposes `POST /webhooks/razorpay` for callbacks
- `ChargeService` injects the active `PaymentProcessor` (no code changes needed)

**Key behavioral difference:** The simulated provider returns a final result synchronously (e.g. `success:` → `ProviderResult.success(...)`). The Razorpay provider creates an order and returns `ProviderResult.Type.UNKNOWN` — the payment resolves later via webhook callbacks. See `docs/razorpay-integration.md` for full details.
