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
     */
    public void applyProviderResult(final ProviderResult result) {
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
     * Resolve an UNKNOWN payment to a confirmed state.
     * Called by the reconciliation/polling job (Stage 4).
     */
    public void resolveUnknown(final PaymentStatus resolved,
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
            final UUID journalEntryId) {

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

        return payment;
    }
}
