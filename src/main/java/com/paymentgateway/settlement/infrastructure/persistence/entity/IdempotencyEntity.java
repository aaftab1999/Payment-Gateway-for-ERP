package com.paymentgateway.settlement.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the authoritative idempotency store.
 *
 * <p><strong>Uniqueness:</strong> The primary key is
 * {@code (merchant_id, idempotency_key)}. Two concurrent inserts for the
 * same pair serialize on this constraint at the PostgreSQL level — no
 * distributed lock required. The store layer catches the resulting
 * {@code PSQLException} (unique_violation, SQL state 23505) and translates
 * it into a replay or conflict.</p>
 *
 * <p><strong>Response cache:</strong> {@code response_body} stores the JSON
 * payload of the original response so a replay can reconstruct the result
 * byte-for-byte without re-contacting the provider.</p>
 */
@Entity
@Table(name = "idempotency")
public class IdempotencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "idempotency_id", columnDefinition = "UUID")
    private UUID idempotencyId;

    @Column(name = "merchant_id", nullable = false, length = 255)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "payment_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentId;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "JSONB")
    private String responseBody;

    @Column(name = "is_terminal", nullable = false)
    private boolean terminal;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant expiresAt;

    protected IdempotencyEntity() {
        // JPA
    }

    public IdempotencyEntity(final String merchantId, final String idempotencyKey,
                             final String requestHash, final UUID paymentId,
                             final int responseStatus, final String responseBody,
                             final boolean terminal, final Instant expiresAt) {
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.paymentId = paymentId;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.terminal = terminal;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public UUID getIdempotencyId() { return idempotencyId; }
    public void setIdempotencyId(UUID idempotencyId) { this.idempotencyId = idempotencyId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }
    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int responseStatus) { this.responseStatus = responseStatus; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    /**
     * Updates this row in place with the finalized response payload.
     */
    public void finalize(final String requestHash, final UUID paymentId,
                         final int responseStatus, final String responseBody,
                         final boolean terminal, final Instant expiresAt) {
        this.requestHash = requestHash;
        this.paymentId = paymentId;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.terminal = terminal;
        this.expiresAt = expiresAt;
    }
}