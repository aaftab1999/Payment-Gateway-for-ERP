package com.paymentgateway.settlement.infrastructure.persistence.repository;

import com.paymentgateway.settlement.infrastructure.persistence.entity.IdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link IdempotencyEntity}.
 *
 * <p><strong>Locking strategy:</strong> The store uses
 * {@code SELECT ... FOR UPDATE} (via {@link IdempotencyRepository#lockByKey})
 * during reservation so concurrent requests for the same key serialize at
 * the database level. The unique constraint on
 * {@code (merchant_id, idempotency_key)} is the ultimate arbiter: if two
 * transactions both attempt to insert the same key, one commits and the
 * other gets a unique-violation
 * {@code PSQLException} (SQL state 23505).</p>
 */
@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyEntity, UUID> {

    /**
     * Locks and loads the existing idempotency row for the given key.
     * Returns empty when no row exists yet.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM IdempotencyEntity e WHERE e.merchantId = :merchantId AND e.idempotencyKey = :key")
    Optional<IdempotencyEntity> lockByKey(@Param("merchantId") String merchantId,
                                          @Param("key") String key);

    Optional<IdempotencyEntity> findByMerchantIdAndIdempotencyKey(String merchantId, String key);

    /**
     * Attempts to insert the reservation row. The unique constraint on
     * (merchant_id, idempotency_key) enforces atomicity: if the row already
     * exists this returns 0 and the caller must resolve the conflict by
     * reading the existing row.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency
                (merchant_id, idempotency_key, request_hash, payment_id,
                 response_status, response_body, is_terminal, created_at, expires_at)
            VALUES
                (:merchantId, :idempotencyKey, :requestHash, :paymentId,
                 :responseStatus, CAST(:responseBody AS JSONB), :isTerminal, now(), :expiresAt)
            """, nativeQuery = true)
    int insertReservation(@Param("merchantId") String merchantId,
                          @Param("idempotencyKey") String idempotencyKey,
                          @Param("requestHash") String requestHash,
                          @Param("paymentId") UUID paymentId,
                          @Param("responseStatus") int responseStatus,
                          @Param("responseBody") String responseBody,
                          @Param("isTerminal") boolean isTerminal,
                          @Param("expiresAt") java.time.Instant expiresAt);
}