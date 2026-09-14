package com.paymentgateway.settlement.domain.payment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payment aggregate root.
 *
 * <p><strong>Responsibility:</strong> Own the payment lifecycle from
 * creation through provider submission and terminal/final state.
 * Enforces the {@link PaymentStateEngine} state machine internally.</p>
 *
 * <p><strong>Not owned (ERP side):</strong> invoice balance, customer
 * data beyond the opaque {@code customerRef}, bill amount, invoice status.
 * The gateway only records that a payment was attempted against a given
 * bill reference.</p>
 *
 * <p><strong>Money semantics:</strong> The {@code amount} field is
 * the payment amount requested by the ERP. The gateway validates it is
 * positive and matches the currency. The gateway does NOT split bills —
 * if the ERP sends a partial amount, the gateway processes that amount
 * and the ERP tracks the remaining balance.</p>
 *
 * <p><strong>Optimistic locking:</strong> The {@code version} field
 * prevents lost updates when two concurrent threads (e.g. a provider
 * callback arriving twice) try to transition the same payment. The JPA
 * entity layer uses {@code @Version}; the domain enforces this via
 * the {@code version()} accessor.</p>
 *
 * <p><strong>Ledger integration:</strong> The {@code journalEntryId}
 * field is {@code null} until the ledger service posts double-entry
 * records. In Stage 3 (no ledger), it remains {@code null}. The
 * workflow is designed so that ledger posting happens in the same
 * database transaction as the terminal status transition.</p>
 */
public final class Payment {

    private final PaymentId paymentId;
    private final String merchantId;
    private final String customerRef;
    private final String billRef;
    private final Money amount;
    private final PaymentMethodType paymentMethod;
    private final String paymentToken;
    private PaymentStatus status;
    private String providerReference;
    private String failureCode;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
    private UUID correlationId;
    private long version;
    private UUID journalEntryId;   // null until ledger posted (Stage 4+)

    // --- Stage 4: idempotency, provider idempotency, retry metadata ---

    /** Stable key sent to the external provider for this attempt. */
    private String providerIdempotencyKey;

    /** Number of provider attempts made for this payment. */
    private int attemptCount;

    /** Timestamp of the most recent provider attempt. */
    private Instant lastAttemptAt;

    /** Timestamp when the next recovery attempt is permitted. */
    private Instant nextRetryAt;

    /** Reason recorded on the most recent failure/uncertainty. */
    private String lastFailureReason;

    // --- Construction ---

    private Payment(
            final PaymentId paymentId,
            final String merchantId,
            final String customerRef,
            final String billRef,
            final Money amount,
            final PaymentMethodType paymentMethod,
            final String paymentToken,
            final UUID correlationId) {

        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.merchantId = validateRef(merchantId, "merchantId");
        this.customerRef = validateRefNullable(customerRef, "customerRef");
        this.billRef = validateRef(billRef, "billRef");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod");
        this.paymentToken = paymentToken;
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");

        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.version = 0;
        this.journalEntryId = null;
        this.providerIdempotencyKey = null;
        this.attemptCount = 0;
        this.lastAttemptAt = null;
        this.nextRetryAt = null;
        this.lastFailureReason = null;
    }

    /**
     * Factory: create a new payment in {@link PaymentStatus#CREATED}.
     * Validates the monetary amount and references at construction time.
     */
    public static Payment create(
            final PaymentId paymentId,
            final String merchantId,
            final String customerRef,
            final String billRef,
            final Money amount,
            final PaymentMethodType paymentMethod,
            final String paymentToken,
            final UUID correlationId) {

        Objects.requireNonNull(paymentToken, "paymentToken");
        return new Payment(paymentId, merchantId, customerRef, billRef,
                amount, paymentMethod, paymentToken, correlationId);
    }

    // --- State transitions (delegated to PaymentStateEngine) ---

    /**
     * Transition from CREATED → PROCESSING.
     * Called when the payment is submitted to a provider.
     */
    public void markProcessing() {
        this.status = PaymentStateEngine.transition(
                this.status, PaymentStatus.PROCESSING,
                PaymentStateEngine.TransitionReason.SUBMITTED_TO_PROVIDER);
        touch();
    }

    /**
     * Transition from PROCESSING → terminal/intermediate state based
     * on the provider result.
     *
     * <p><strong>Idempotent:</strong> If the payment has already reached a
     * terminal state and the same provider result is applied again (e.g.
     * duplicate callback or retry), the call is a no-op. This prevents
     * duplicate provider results from corrupting state or re-posting the
     * ledger. The provider reference and failure fields are only updated
     * when the caller supplies a non-null value.</p>
     */
    public void applyProviderResult(final ProviderResult result) {
        if (this.status.isTerminal()) {
            // Duplicate provider result for an already-terminal payment.
            // Update provider reference only if it was not previously set
            // (some providers supply a ref only on the first response).
            if (this.providerReference == null && result.providerReference() != null) {
                this.providerReference = result.providerReference();
            }
            return;
        }

        PaymentStatus target = mapResultToStatus(result.type());
        PaymentStateEngine.TransitionReason reason = mapResultToReason(result.type());

        this.status = PaymentStateEngine.transition(
                this.status, target, reason);

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

    /**
     * Records a provider attempt and its outcome.
     *
     * <p><strong>Provider idempotency:</strong> The {@code providerIdempotencyKey}
     * must remain stable across retries of the same attempt. This method
     * sets it only when it has not already been set, so a retry never
     * generates a new provider key and the provider never charges the same
     * logical attempt twice.</p>
     *
     * @param providerIdempotencyKey stable key for this attempt (must not be blank)
     * @param result                 the provider result for this attempt
     * @param failureReason          reason to record when the attempt was uncertain/failed
     */
    public void recordProviderAttempt(
            final String providerIdempotencyKey,
            final ProviderResult result,
            final String failureReason) {
        if (providerIdempotencyKey == null || providerIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("providerIdempotencyKey must not be blank");
        }
        if (this.providerIdempotencyKey == null) {
            this.providerIdempotencyKey = providerIdempotencyKey;
        }
        this.attemptCount = this.attemptCount + 1;
        this.lastAttemptAt = Instant.now();
        if (failureReason != null && !failureReason.isBlank()) {
            this.lastFailureReason = failureReason;
        }
        touch();
    }

    /**
     * Ensures a stable provider idempotency key exists for this payment.
     *
     * <p>The key is derived deterministically from the payment ID so it
     * survives application restarts and is stable across retries of the
     * same attempt. The first call sets it; subsequent calls are no-ops.</p>
     *
     * @return the provider idempotency key (never null for a persisted payment)
     */
    public String ensureProviderIdempotencyKey() {
        if (this.providerIdempotencyKey == null || this.providerIdempotencyKey.isBlank()) {
            this.providerIdempotencyKey = "prov_" + this.paymentId.toString();
        }
        return this.providerIdempotencyKey;
    }

    /**
     * Schedules the next permitted retry.
     *
     * @param nextRetryAt when the recovery worker may attempt again
     */
    public void scheduleNextRetry(final Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
        touch();
    }

/**
     * Resolve an UNKNOWN or REQUIRES_RECONCILIATION payment to a confirmed
     * state. Called by the reconciliation/polling job (Stage 4).
     *
     * <p>Safe for both non-terminal awaiting-resolution states because the
     * only legal targets are {@link PaymentStatus#SUCCEEDED} or
     * {@link PaymentStatus#FAILED}. No money moves; the ledger is not
     * re-posted. The provider reference, failure code and failure reason
     * are updated only when the caller supplies a non-null value.</p>
     */
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

    /**
     * Re-enters PROCESSING for a bounded retry of an uncertain payment.
     *
     * <p>Only legal from {@link PaymentStatus#UNKNOWN} or
     * {@link PaymentStatus#REQUIRES_RECONCILIATION}. The caller MUST ensure
     * the retry budget has not been exhausted before invoking this method;
     * the state machine only validates the transition shape.</p>
     */
    public void markRetrySubmitted() {
        this.status = PaymentStateEngine.transition(
                this.status, PaymentStatus.PROCESSING,
                PaymentStateEngine.TransitionReason.RETRY_SUBMITTED);
        touch();
    }

    /**
     * Records that the retry budget for this payment has been exhausted.
     *
     * <p>The payment is left in its current non-terminal state
     * ({@link PaymentStatus#UNKNOWN} or
     * {@link PaymentStatus#REQUIRES_RECONCILIATION}) so it remains visible
     * for manual investigation or a later reconciliation pass. This method
     * does NOT change status — it only records the exhaustion reason.</p>
     */
    public void markRetryExhausted(final String reason) {
        if (reason != null && !reason.isBlank()) {
            this.lastFailureReason = reason;
        }
        touch();
    }

    private PaymentStatus mapResultToStatus(final ProviderResult.Type type) {
        return switch (type) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case DECLINED -> PaymentStatus.FAILED;
            case TECHNICAL_FAILURE -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.UNKNOWN;
        };
    }

    private PaymentStateEngine.TransitionReason mapResultToReason(final ProviderResult.Type type) {
        return switch (type) {
            case SUCCESS -> PaymentStateEngine.TransitionReason.PROVIDER_SUCCESS;
            case DECLINED -> PaymentStateEngine.TransitionReason.PROVIDER_DECLINED;
            case TECHNICAL_FAILURE -> PaymentStateEngine.TransitionReason.PROVIDER_TECHNICAL_FAILURE;
            case UNKNOWN -> PaymentStateEngine.TransitionReason.PROVIDER_UNKNOWN_OUTCOME;
        };
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // --- Validation helpers ---

    private static String validateRef(final String ref, final String fieldName) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        if (ref.length() > 255) {
            throw new IllegalArgumentException(fieldName + " must be ≤ 255 characters");
        }
        return ref;
    }

    private static String validateRefNullable(final String ref, final String fieldName) {
        if (ref == null || ref.isBlank()) return null;
        if (ref.length() > 255) {
            throw new IllegalArgumentException(fieldName + " must be ≤ 255 characters");
        }
        return ref;
    }

    // --- Accessors ---

    public PaymentId getPaymentId() { return paymentId; }
    public String getMerchantId() { return merchantId; }
    public String getCustomerRef() { return customerRef; }
    public String getBillRef() { return billRef; }
    public Money getAmount() { return amount; }
    public PaymentMethodType getPaymentMethod() { return paymentMethod; }
    public String getPaymentToken() { return paymentToken; }
    public PaymentStatus getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getCorrelationId() { return correlationId; }
    public long getVersion() { return version; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public String getProviderIdempotencyKey() { return providerIdempotencyKey; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getLastFailureReason() { return lastFailureReason; }

    public void setJournalEntryId(final UUID journalEntryId) {
        this.journalEntryId = journalEntryId;
    }

    // --- Reconstitution from persistence ---

    /**
     * Reconstructs a Payment from persisted state.
     *
     * <p>Unlike {@link #create(...)}, this bypasses all transition validation
     * because the payment has already transitioned through the state machine
     * (under database locks) before being persisted. This is the standard
     * CQRS/hexagonal pattern for reconstituting aggregates from storage.</p>
     *
     * <p>Reconstitution from persistence — only the infrastructure mapper may call this.</p>
     */
    public static Payment reconstitute(
            final PaymentId paymentId,
            final String merchantId,
            final String customerRef,
            final String billRef,
            final Money amount,
            final PaymentMethodType paymentMethod,
            final String paymentToken,
            final PaymentStatus status,
            final String providerReference,
            final String failureCode,
            final String failureReason,
            final Instant createdAt,
            final Instant updatedAt,
            final UUID correlationId,
            final long version,
            final UUID journalEntryId,
            final String providerIdempotencyKey,
            final int attemptCount,
            final Instant lastAttemptAt,
            final Instant nextRetryAt,
            final String lastFailureReason) {

        Payment payment = new Payment(paymentId, merchantId, customerRef, billRef,
                amount, paymentMethod, paymentToken, correlationId);

        payment.status = status;
        payment.providerReference = providerReference;
        payment.failureCode = failureCode;
        payment.failureReason = failureReason;
        payment.createdAt = createdAt;
        payment.updatedAt = updatedAt;
        payment.version = version;
        payment.journalEntryId = journalEntryId;
        payment.providerIdempotencyKey = providerIdempotencyKey;
        payment.attemptCount = attemptCount;
        payment.lastAttemptAt = lastAttemptAt;
        payment.nextRetryAt = nextRetryAt;
        payment.lastFailureReason = lastFailureReason;

        return payment;
    }
}
