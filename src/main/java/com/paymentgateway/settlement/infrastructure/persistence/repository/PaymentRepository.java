package com.paymentgateway.settlement.infrastructure.persistence.repository;

import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link PaymentEntity}.
 *
 * <p><strong>Indexes used (defined in Flyway V2 migration):</strong></p>
 * <ul>
 *   <li>{@code idx_payment_bill_ref}: fast lookup by ERP bill reference (used by {@code GET /by-bill/{ref}})</li>
 *   <li>{@code idx_payment_merchant}: tenant-scoped queries</li>
 *   <li>{@code idx_payment_status}: batch processing by status (e.g. polling UNKNOWN payments)</li>
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

    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    Optional<PaymentEntity> findAndLockByPaymentId(UUID paymentId);

    List<PaymentEntity> findByBillRefAndMerchantId(String billRef, String merchantId);

    List<PaymentEntity> findByMerchantId(String merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PaymentEntity> findAndLockByBillRefAndMerchantId(String billRef, String merchantId);
}
