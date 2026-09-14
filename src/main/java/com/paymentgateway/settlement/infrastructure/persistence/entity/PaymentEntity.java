package com.paymentgateway.settlement.infrastructure.persistence.entity;

import com.paymentgateway.settlement.domain.payment.Currency;
import com.paymentgateway.settlement.domain.payment.Money;
import com.paymentgateway.settlement.domain.payment.Payment;
import com.paymentgateway.settlement.domain.payment.PaymentId;
import com.paymentgateway.settlement.domain.payment.PaymentMethodType;
import com.paymentgateway.settlement.domain.payment.PaymentStatus;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the Payment aggregate.
 *
 * <p><strong>Design decisions:</strong></p>
 * <ul>
 *   <li>{@code @Version} for optimistic locking — prevents lost updates
 *       when two concurrent threads try to transition the same payment.</li>
 *   <li>{@code BigDecimal} stored as DECIMAL(18,2) in PostgreSQL —
 *       sufficient for payment amounts up to 999,999,999,999,999.99.</li>
 *   <li>{@code billRef} has a composite index with {@code merchantId} —
 *       for fast lookup by ERP bill reference.</li>
 *   <li>{@code providerReference} is nullable and unique — prevents
 *       duplicate provider responses from creating duplicate payments.</li>
 *   <li>{@code paymentToken} is {@code @Transient} — never persisted
 *       (PCI-DSS compliance).</li>
 * </ul>
 */
@Entity
@Table(name = "payment")
public class PaymentEntity {

    @Id
    @Column(name = "payment_id", columnDefinition = "UUID")
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false, length = 255)
    private String merchantId;

    @Column(name = "customer_ref", length = 255)
    private String customerRef;

    @Column(name = "bill_ref", nullable = false, length = 255)
    private String billRef;

    @Column(name = "amount_minor", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, columnDefinition = "VARCHAR(3)")
    private String currency;

    @Column(name = "payment_method", nullable = false, columnDefinition = "VARCHAR(32)")
    @Enumerated(EnumType.STRING)
    private PaymentMethodType paymentMethod;

    // NEVER persisted — PCI-DSS rule: tokens may be cached in-memory only
    @Transient
    private String paymentToken;

    /**
     * Getter for paymentToken used by the domain mapper.
     * The value exists only in-memory (never persisted).
     */
    public String getPaymentToken() {
        return paymentToken;
    }

    public void setPaymentToken(String paymentToken) {
        this.paymentToken = paymentToken;
    }

    @Column(name = "status", nullable = false, columnDefinition = "VARCHAR(32)")
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "provider_reference", columnDefinition = "VARCHAR(255)", unique = true)
    private String providerReference;

    @Column(name = "failure_code", columnDefinition = "VARCHAR(64)")
    private String failureCode;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "correlation_id", columnDefinition = "UUID")
    private UUID correlationId;

    @Column(name = "journal_entry_id", columnDefinition = "UUID")
    private UUID journalEntryId;

    // --- Stage 4: idempotency, provider idempotency, retry metadata ---

    @Column(name = "provider_idempotency_key", columnDefinition = "VARCHAR(255)")
    private String providerIdempotencyKey;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempt_at", columnDefinition = "TIMESTAMPTZ")
    private Instant lastAttemptAt;

    @Column(name = "next_retry_at", columnDefinition = "TIMESTAMPTZ")
    private Instant nextRetryAt;

    @Column(name = "last_failure_reason", columnDefinition = "VARCHAR(1024)")
    private String lastFailureReason;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // --- Constructors ---

    protected PaymentEntity() {
        // JPA
    }

    // --- Mapping: domain → entity ---

    public static PaymentEntity fromDomain(final Payment payment) {
        PaymentEntity entity = new PaymentEntity();
        entity.paymentId = payment.getPaymentId().toUuid();
        entity.merchantId = payment.getMerchantId();
        entity.customerRef = payment.getCustomerRef();
        entity.billRef = payment.getBillRef();
        entity.amount = BigDecimal.valueOf(payment.getAmount().toMinorUnits());
        entity.currency = payment.getAmount().getCurrency().name();
        entity.paymentMethod = payment.getPaymentMethod();
        entity.paymentToken = payment.getPaymentToken();
        entity.status = payment.getStatus();
        entity.providerReference = payment.getProviderReference();
        entity.failureCode = payment.getFailureCode();
        entity.failureReason = payment.getFailureReason();
        entity.correlationId = payment.getCorrelationId();
        entity.journalEntryId = payment.getJournalEntryId();
        entity.providerIdempotencyKey = payment.getProviderIdempotencyKey();
        entity.attemptCount = payment.getAttemptCount();
        entity.lastAttemptAt = payment.getLastAttemptAt();
        entity.nextRetryAt = payment.getNextRetryAt();
        entity.lastFailureReason = payment.getLastFailureReason();
        entity.createdAt = payment.getCreatedAt();
        entity.updatedAt = payment.getUpdatedAt();
        return entity;
    }

    public void updateFromDomain(final Payment payment) {
        this.merchantId = payment.getMerchantId();
        this.customerRef = payment.getCustomerRef();
        this.billRef = payment.getBillRef();
        this.amount = BigDecimal.valueOf(payment.getAmount().toMinorUnits());
        this.currency = payment.getAmount().getCurrency().name();
        this.paymentMethod = payment.getPaymentMethod();
        this.paymentToken = payment.getPaymentToken();
        this.status = payment.getStatus();
        this.providerReference = payment.getProviderReference();
        this.failureCode = payment.getFailureCode();
        this.failureReason = payment.getFailureReason();
        this.correlationId = payment.getCorrelationId();
        this.journalEntryId = payment.getJournalEntryId();
        this.providerIdempotencyKey = payment.getProviderIdempotencyKey();
        this.attemptCount = payment.getAttemptCount();
        this.lastAttemptAt = payment.getLastAttemptAt();
        this.nextRetryAt = payment.getNextRetryAt();
        this.lastFailureReason = payment.getLastFailureReason();
        this.createdAt = payment.getCreatedAt();
        this.updatedAt = payment.getUpdatedAt();
    }

    // --- Mapping: entity → domain ---

    public Payment toDomain(final String paymentToken) {
        // amount_minor holds the integer minor-unit count (e.g. 125000 for
        // 1250.00 INR). The column is DECIMAL(18,2) so PostgreSQL may store a
        // trailing scale of 2, but the value must be an exact integer. Use
        // longValueExact() so a fractional minor-unit value (e.g. 125000.50)
        // fails loudly instead of silently truncating to 125000.
        BigDecimal amountMinor = this.amount;
        if (amountMinor == null) {
            amountMinor = BigDecimal.ZERO;
        }
        if (amountMinor.scale() != 0) {
            // Strip trailing zeros only when the value is an exact integer.
            // Any non-zero fraction is a data-corruption signal and must throw.
            BigDecimal stripped = amountMinor.stripTrailingZeros();
            if (stripped.scale() > 0) {
                throw new IllegalStateException(
                        "amount_minor must be an integer minor-unit count, but was: " + amountMinor);
            }
            amountMinor = stripped;
        }

        var money = Money.fromMinorUnits(
                amountMinor.longValueExact(),
                Currency.fromCode(this.currency)
        );

        return Payment.reconstitute(
                PaymentId.of(this.paymentId),
                this.merchantId,
                this.customerRef,
                this.billRef,
                money,
                this.paymentMethod,
                paymentToken,
                this.status,
                this.providerReference,
                this.failureCode,
                this.failureReason,
                this.createdAt,
                this.updatedAt,
                this.correlationId,
                this.version != null ? this.version : 0L,
                this.journalEntryId,
                this.providerIdempotencyKey,
                this.attemptCount,
                this.lastAttemptAt,
                this.nextRetryAt,
                this.lastFailureReason
        );
    }

    // --- Getters / Setters for JPA ---

    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getCustomerRef() { return customerRef; }
    public void setCustomerRef(String customerRef) { this.customerRef = customerRef; }

    public String getBillRef() { return billRef; }
    public void setBillRef(String billRef) { this.billRef = billRef; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public PaymentMethodType getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethodType paymentMethod) { this.paymentMethod = paymentMethod; }

    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }

    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public UUID getCorrelationId() { return correlationId; }
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }

    public UUID getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }

    public String getProviderIdempotencyKey() { return providerIdempotencyKey; }
    public void setProviderIdempotencyKey(String providerIdempotencyKey) { this.providerIdempotencyKey = providerIdempotencyKey; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public String getLastFailureReason() { return lastFailureReason; }
    public void setLastFailureReason(String lastFailureReason) { this.lastFailureReason = lastFailureReason; }
}
