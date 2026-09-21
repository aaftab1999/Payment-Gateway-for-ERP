package com.paymentgateway.settlement.infrastructure.persistence.repository;

import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link PaymentEntity}.
 *
 * <p><strong>Indexes used (defined in Flyway V2/V3 migrations):</strong></p>
 * <ul>
 *   <li>{@code idx_payment_bill_ref}: fast lookup by ERP bill reference (used by {@code GET /by-bill/{ref}})</li>
 *   <li>{@code idx_payment_merchant}: tenant-scoped queries</li>
 *   <li>{@code idx_payment_status}: batch processing by status (e.g. polling UNKNOWN payments)</li>
 *   <li>{@code idx_payment_recovery_created}: recovery queries by status + created_at</li>
 *   <li>{@code idx_payment_recovery_next_retry}: recovery queries by next_retry_at</li>
 * </ul>
 *
 * <p><strong>Concurrency:</strong> {@code @Lock(OPTIMISTIC_FORCE_INCREMENT)}
 * is applied on the status-update method to ensure atomic transitions.
 * Pessimistic locking is used in the ChargeService for idempotency
 * (Stage 4).</p>
 */
@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByPaymentId(UUID paymentId);

    Optional<PaymentEntity> findByProviderReference(String providerReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentEntity> findAndLockByPaymentId(UUID paymentId);

    List<PaymentEntity> findByBillRefAndMerchantId(String billRef, String merchantId);

    List<PaymentEntity> findByMerchantId(String merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PaymentEntity> findAndLockByBillRefAndMerchantId(String billRef, String merchantId);

    // --- Recovery queries (Stage 4) ---

    /**
     * Finds payments stuck in non-terminal states for recovery.
     *
     * <p>Filters by:
     * <ul>
     *   <li>status in (CREATED, PROCESSING, UNKNOWN, REQUIRES_RECONCILIATION)</li>
     *   <li>created_at older than the cutoff (so we don't re-process fresh payments)</li>
     *   <li>next_retry_at is null or in the past</li>
     * </ul></p>
     *
     * @param cutoff payments created before this timestamp are eligible
     * @param now    current time (for next_retry_at comparison)
     * @return up to {@code limit} payments eligible for recovery
     */
    @Query(value = """
            SELECT p FROM PaymentEntity p
            WHERE p.status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'REQUIRES_RECONCILIATION')
              AND p.createdAt < :cutoff
              AND (p.nextRetryAt IS NULL OR p.nextRetryAt <= :now)
            ORDER BY p.createdAt ASC
            """)
    List<PaymentEntity> findForRecovery(@Param("cutoff") Instant cutoff,
                                        @Param("now") Instant now);

    /**
     * Counts payments eligible for recovery (for metrics/observability).
     */
    @Query(value = """
            SELECT COUNT(p) FROM PaymentEntity p
            WHERE p.status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'REQUIRES_RECONCILIATION')
              AND p.createdAt < :cutoff
              AND (p.nextRetryAt IS NULL OR p.nextRetryAt <= :now)
            """)
    long countForRecovery(@Param("cutoff") Instant cutoff,
                          @Param("now") Instant now);

    /**
     * Counts payments stuck in UNKNOWN or REQUIRES_RECONCILIATION (awaiting
     * reconciliation) — a key operational metric.
     */
    @Query(value = """
            SELECT COUNT(p) FROM PaymentEntity p
            WHERE p.status IN ('UNKNOWN', 'REQUIRES_RECONCILIATION')
            """)
    long countAwaitingReconciliation();

    /**
     * Counts payments stuck in CREATED or PROCESSING (awaiting provider
     * submission or result) — a key operational metric.
     */
    @Query(value = """
            SELECT COUNT(p) FROM PaymentEntity p
            WHERE p.status IN ('CREATED', 'PROCESSING')
            """)
    long countAwaitingProcessing();
}
