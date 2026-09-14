# Payment Domain Model

## Overview

The payment domain is the core of the Payment Gateway & Settlement Core Engine. It models a payment from creation through provider submission and terminal state, using a state machine to enforce valid transitions.

All domain code lives in `src/main/java/com/paymentgateway/settlement/domain/payment/`.

## Aggregate Root: Payment

`Payment` is the aggregate root. It is immutable in identity (paymentId, merchantId, customerRef, billRef, amount, paymentMethod) but mutable in status and provider-facing fields.

### Fields

| Field | Type | Mutable | Description |
|-------|------|---------|-------------|
| `paymentId` | `PaymentId` | No | Internal gateway UUID. Not visible to the ERP in requests. |
| `merchantId` | `String` | No | Merchant identifier (opaque string from ERP). |
| `customerRef` | `String` | No | Customer reference (opaque string, nullable). |
| `billRef` | `String` | No | ERP bill reference used for lookups. |
| `amount` | `Money` | No | Payment amount as a `Money` value object. |
| `paymentMethod` | `PaymentMethodType` | No | UPI, CREDIT_CARD, DEBIT_CARD, NET_BANKING. |
| `paymentToken` | `String` | No | Tokenized payment credential. `@Transient` in JPA — never persisted. |
| `status` | `PaymentStatus` | Yes | Current state in the state machine. |
| `providerReference` | `String` | Yes | External provider's transaction reference. Nullable. |
| `failureCode` | `String` | Yes | Standardized failure code. Nullable. |
| `failureReason` | `String` | Yes | Human-readable failure reason. Nullable. |
| `createdAt` | `Instant` | No | Record creation time. |
| `updatedAt` | `Instant` | Yes | Last transition time. |
| `correlationId` | `UUID` | No | Traceability ID for the request. |
| `version` | `long` | Yes | Optimistic locking version. Maps to `@Version`. |
| `journalEntryId` | `UUID` | Yes | Ledger journal ID (null in Stage 3). |

### Factory Methods

- `Payment.create(...)` — Creates a new payment in `CREATED` state. Requires non-null `paymentToken`.
- `Payment.reconstitute(...)` — Reconstructs a `Payment` from persisted state. Bypasses transition validation (standard CQRS pattern). Can accept null `paymentToken` since it's not loaded from the database.

### State Transition Methods

- `markProcessing()` — `CREATED → PROCESSING`
- `applyProviderResult(ProviderResult)` — `PROCESSING → terminal/intermediate`
- `resolveUnknown(PaymentStatus, ...)` — `UNKNOWN → SUCCEEDED | FAILED`
- `setJournalEntryId(UUID)` — Sets the ledger journal entry ID (Stage 4+).

## Money and Currency

`Money` is an immutable value object wrapping `BigDecimal` with a `Currency` enum.

### Currency Enum

| Currency | Scale | Description |
|----------|-------|-------------|
| `INR` | 2 | Indian Rupee |
| `USD` | 2 | US Dollar |
| `EUR` | 2 | Euro |
| `GBP` | 2 | Pound Sterling |
| `JPY` | 0 | Japanese Yen |
| `AED` | 2 | UAE Dirham |
| `BHD` | 3 | Bahraini Dinar |
| `KWD` | 3 | Kuwaiti Dinar |
| `JOD` | 3 | Jordanian Dinar |
| `OMR` | 3 | Omani Rial |

`Currency.fromCode(String)` converts a 3-letter ISO-4217 code to the enum. Unknown codes throw `IllegalArgumentException("Unsupported currency: ...")`.

### Money

- Amounts are stored as `BigDecimal` — no `double`/`float` (binary floating-point cannot represent decimal fractions exactly).
- Amounts must be non-negative at construction.
- Scale must match the currency's scale — `Money.of("10.001", INR)` throws `IllegalArgumentException("Scale mismatch...")`.
- `toMinorUnits()` — converts to long integer minor units (e.g., 1250.00 INR → 125000 paise).
- `fromMinorUnits(long, Currency)` — reconstructs from minor units.

### Database Storage

Amounts are stored in the `payment` table as **minor units** (integer counts such as paise/cents). The column is named `amount_minor` and is typed `DECIMAL(18,2)`.

* Example: `1250.00` INR is stored as the integer `125000`.
* The `DECIMAL(18,2)` scale is **cosmetic only** — it does not represent the currency's decimal places. JPY (scale 0) and BHD/KWD/JOD/OMR (scale 3) are also stored as integer minor-unit counts. The application asserts the stored value is an exact integer on read; a fractional value (e.g. `125000.50`) fails loudly instead of silently truncating.

Conversion to/from minor units is handled by `Money.toMinorUnits()` and `Money.fromMinorUnits(long, Currency)`.

* Write path: `PaymentEntity.fromDomain` → `BigDecimal.valueOf(payment.getAmount().toMinorUnits())`.
* Read path: `PaymentEntity.toDomain` → validates the stored value is an exact integer, then `Money.fromMinorUnits(...)`.

## PaymentMethodType

| Method | Description |
|--------|-------------|
| `UPI` | Unified Payments Interface |
| `CREDIT_CARD` | Credit Card |
| `DEBIT_CARD` | Debit Card |
| `NET_BANKING` | Net Banking |

`PaymentMethodType.fromCode(String)` converts a string to the enum with case-insensitive matching.

## PaymentStatus

| Status | Terminal? | Awaiting Resolution? | Description |
|--------|-----------|---------------------|-------------|
| `CREATED` | No | No | Payment record created, not yet submitted to provider. |
| `PROCESSING` | No | No | Submitted to provider, awaiting response. |
| `SUCCEEDED` | Yes | No | Payment completed successfully. |
| `FAILED` | Yes | No | Payment declined by provider. |
| `UNKNOWN` | No | Yes | Provider returned ambiguous result; outcome could be success or failure. Requires reconciliation. |
| `REQUIRES_RECONCILIATION` | No | Yes | Provider timeout with bank discrepancy. Requires reconciliation. |
| `VOIDED` | Yes | No | Voided before capture (Stage 4+). |
| `REFUNDED` | Yes | No | Fully refunded (Stage 4+). |

## ProviderResult

Immutable record returned by the `PaymentProcessor` SPI. Has a `Type`, optional `providerReference`, optional `failureCode`, and optional `failureReason`.

| Type | Meaning |
|------|---------|
| `SUCCESS` | Payment succeeded. |
| `DECLINED` | Provider declined (e.g., insufficient funds). |
| `TECHNICAL_FAILURE` | Provider technical failure (no money moved). |
| `UNKNOWN` | Provider returned ambiguous result (may have debited). |

## PaymentId

Value object wrapping a `UUID`. Generated via `PaymentId.generate()` (random UUID).

## Constraints and Invariants

1. **Amount must be positive** — `Money.of` rejects non-positive amounts.
2. **Scale must match currency** — enforced at construction.
3. **paymentToken is never persisted** — stored as `@Transient` in the JPA entity and omitted from all API responses.
4. **providerReference is unique** — enforced by database unique constraint.
5. **Status transitions are state-machine-validated** — no direct `CREATED → SUCCEEDED` (must go through `PROCESSING` first).
6. **Optimistic locking** — `@Version` on the entity entity prevents lost updates.
7. **Non-negative amounts only** — refunds and reversals are separate payment records, not signed amounts.
