# Payment State Machine

## Overview

The payment state machine is implemented in `PaymentStateEngine` (domain layer) and validated by `PaymentStatus` (enum). It enforces legal transitions between payment statuses, preventing invalid state changes.

The state machine logic lives in the domain layer (not persistence), making it fully unit-testable without Spring or a database.

## Stage 3 States

| Status | Terminal? | Meaning |
|--------|-----------|---------|
| `CREATED` | No | Payment record created, not yet submitted to provider. |
| `PROCESSING` | No | Submitted to provider, awaiting response. |
| `SUCCEEDED` | Yes | Payment completed successfully. |
| `FAILED` | Yes | Payment declined by provider. |
| `UNKNOWN` | No | Provider returned ambiguous result; outcome could be success or failure. Requires reconciliation. |
| `REQUIRES_RECONCILIATION` | No | Provider timeout with bank discrepancy. Requires reconciliation. |
| `VOIDED` | Yes | **Future state (Stage 4+).** Voided before capture. Not reachable in Stage 3. |
| `REFUNDED` | Yes | **Future state (Stage 4+).** Fully refunded. Not reachable in Stage 3. |

## Legal State Transitions (Stage 3)

```
CREATED ─────────────▶ PROCESSING
  │                     │    │    │
  │                     │    │    ├────────────────────▶ SUCCEEDED
  │                     │    │    └──────────────────────▶ FAILED
  │                     │    └──────────────────────────▶ UNKNOWN
  │                     └────────────────────────────────▶ REQUIRES_RECONCILIATION
  │
  └─ (cannot go back to CREATED after starting)

UNKNOWN ─────────────▶ SUCCEEDED  (resolved by reconciliation)
UNKNOWN ─────────────▶ FAILED      (resolved by reconciliation)

REQUIRES_RECONCILIATION ─────────▶ SUCCEEDED  (resolved by reconciliation)
REQUIRES_RECONCILIATION ─────────▶ FAILED      (resolved by reconciliation)

SUCCEEDED ───────────▶ REFUNDED    (Stage 4+ only)

FAILED, VOIDED, REFUNDED ─────────▶ (terminal — no outgoing transitions)
```

### Transition Table

| From | To | Reason | Allowed? |
|------|-----|--------|----------|
| CREATED | PROCESSING | SUBMITTED_TO_PROVIDER | ✅ |
| PROCESSING | SUCCEEDED | PROVIDER_SUCCESS | ✅ |
| PROCESSING | FAILED | PROVIDER_DECLINED | ✅ |
| PROCESSING | FAILED | PROVIDER_TECHNICAL_FAILURE | ✅ |
| PROCESSING | UNKNOWN | PROVIDER_UNKNOWN_OUTCOME | ✅ |
| PROCESSING | REQUIRES_RECONCILIATION | PROVIDER_TECHNICAL_FAILURE | ✅ |
| UNKNOWN | SUCCEEDED | RECONCILIATION_MATCHED | ✅ |
| UNKNOWN | FAILED | RECONCILIATION_FAILED | ✅ |
| REQUIRES_RECONCILIATION | SUCCEEDED | RECONCILIATION_MATCHED | ✅ |
| REQUIRES_RECONCILIATION | FAILED | RECONCILIATION_FAILED | ✅ |
| CREATED | SUCCEEDED | — | ❌ |
| CREATED | FAILED | — | ❌ |
| CREATED | UNKNOWN | — | ❌ |
| PROCESSING | CREATED | — | ❌ |
| PROCESSING | PROCESSING | — | ❌ |
| SUCCEEDED | * | — | ❌ |
| FAILED | * | — | ❌ |
| VOIDED | * | — | ❌ |
| REFUNDED | * | — | ❌ |
| * | CREATED | — | ❌ |

## Transition Reasons

| Reason | When Used |
|--------|-----------|
| `SUBMITTED_TO_PROVIDER` | CREATED → PROCESSING |
| `PROVIDER_SUCCESS` | PROCESSING → SUCCEEDED |
| `PROVIDER_DECLINED` | PROCESSING → FAILED |
| `PROVIDER_TECHNICAL_FAILURE` | PROCESSING → FAILED |
| `PROVIDER_UNKNOWN_OUTCOME` | PROCESSING → UNKNOWN |
| `RECONCILIATION_MATCHED` | UNKNOWN → SUCCEEDED, REQUIRES_RECONCILIATION → SUCCEEDED |
| `RECONCILIATION_FAILED` | UNKNOWN → FAILED, REQUIRES_RECONCILIATION → FAILED |

Note: `PROVIDER_TIMEOUT` is defined in `TransitionReason` but is currently unused (timeout maps to `UNKNOWN` with `PROVIDER_UNKNOWN_OUTCOME` reason). It will be used when timeout is differentiated from unknown outcomes in a future stage.

## How Transitions Are Enforced

### Payment.applyProviderResult(ProviderResult)

Called after the provider returns a result. Maps `ProviderResult.Type` to `PaymentStatus`:

| ProviderResult.Type | Target Status | Transition |
|---|---|---|
| `SUCCESS` | `SUCCEEDED` | PROCESSING → SUCCEEDED |
| `DECLINED` | `FAILED` | PROCESSING → FAILED |
| `TECHNICAL_FAILURE` | `FAILED` | PROCESSING → FAILED |
| `UNKNOWN` | `UNKNOWN` | PROCESSING → UNKNOWN |

### Payment.markProcessing()

Called before applying the provider result. Transitions `CREATED → PROCESSING`. This is invoked automatically by `ChargeService.applyProviderResult()` as part of the two-phase commit flow.

### Payment.resolveReconciliation(PaymentStatus, ...)

Called by the reconciliation/polling job (Stage 4). Transitions `UNKNOWN → SUCCEEDED | FAILED` and `REQUIRES_RECONCILIATION → Succeeded | FAILED` based on external confirmation from the provider.

## Transaction Boundaries

The state machine is enforced within the `ChargeService` two-phase transaction model:

### TX1 (Create)
- Transaction scope: `@Transactional` (REQUIRED)
- Action: Creates payment in `CREATED` state, persists to database, commits.
- The payment must be durable before the provider call — if the app crashes, the payment is in `CREATED` state for reconciliation.

### Provider Call (Outside Transaction)
- No database transaction, no locks held.
- The provider is called synchronously. If it throws an exception, the result is mapped to `ProviderResult.technicalFailure()`.

### TX2 (Apply Result)
- Transaction scope: `@Transactional` (REQUIRED)
- Action:
  1. `SELECT FOR UPDATE` (via `findAndLockByPaymentId`) — locks the row
  2. Reconstitutes `Payment` from entity (bypassing validation)
  3. `markProcessing()` — CREATED → PROCESSING
  4. `applyProviderResult()` — PROCESSING → terminal state
  5. Updates entity in place (`updateFromDomain`)
  6. Flushes and commits
- Retry loop: up to 3 attempts on `ObjectOptimisticLockingFailureException` with 50ms × attempt backoff.

## Why UNKNOWN and REQUIRES_RECONCILIATION Are Not Terminal

A provider timeout does not mean the payment failed — the provider may have debited the customer but timed out before responding. Marking `UNKNOWN` (and `REQUIRES_RECONCILIATION`) keeps the payment in a non-terminal state pending reconciliation, preventing double-charging. Both states have well-defined exit transitions to terminal states, so no payment can be permanently stuck.

## Testing

The state machine is unit-tested in `PaymentStateEngineTest` (20 tests, 0 failures) covering:
- All valid transitions
- All invalid transitions (assert throwing `IllegalStateTransitionException`)
- Terminal state enforcement
- Self-transition rejection
- Reconciliation exit transitions for both `UNKNOWN` and `REQUIRES_RECONCILIATION`
- `VOIDED` and `REFUNDED` are terminal and unreachable in Stage 3

Domain tests for `Payment` in `PaymentTest` cover:
- State transition behavior
- Provider result mapping
- `paymentToken` null handling during reconstitution
- Reconciliation resolution from both `UNKNOWN` and `REQUIRES_RECONCILIATION`
