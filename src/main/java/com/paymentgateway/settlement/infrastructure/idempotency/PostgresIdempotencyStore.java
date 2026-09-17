package com.paymentgateway.settlement.infrastructure.idempotency;

import com.paymentgateway.settlement.application.port.IdempotencyOutcome;
import com.paymentgateway.settlement.application.port.IdempotencyService;
import com.paymentgateway.settlement.domain.idempotency.IdempotencyKey;
import com.paymentgateway.settlement.infrastructure.persistence.entity.IdempotencyEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.IdempotencyRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed implementation of {@link IdempotencyService}.
 *
 * <p><strong>Locking strategy:</strong> This store uses
 * {@code SELECT ... FOR UPDATE} (via {@link IdempotencyRepository#lockByKey})
 * during reservation so concurrent requests for the same key serialize at
 * the database level. The unique constraint on
 * {@code (merchant_id, idempotency_key)} is the ultimate arbiter: if two
 * transactions both attempt to insert the same key, one commits and the
 * other observes a unique-violation {@code PSQLException} (SQL state 23505),
 * which this store translates into a replay or conflict.</p>
 */
@Service
public class PostgresIdempotencyStore implements IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(PostgresIdempotencyStore.class);
    private static final Instant NO_EXPIRY = Instant.parse("9999-12-31T23:59:59Z");

    private final IdempotencyRepository idempotencyRepository;

    public PostgresIdempotencyStore(final IdempotencyRepository idempotencyRepository) {
        this.idempotencyRepository = idempotencyRepository;
    }

    @Override
    @Transactional
    public IdempotencyOutcome reserve(final String merchantId,
                                      final IdempotencyKey key,
                                      final String requestFingerprint,
                                      final UUID paymentId) {
        // 1. Lock the existing row (if any) to serialize concurrent requests.
        Optional<IdempotencyEntity> existing =
                idempotencyRepository.lockByKey(merchantId, key.value());

        if (existing.isPresent()) {
            IdempotencyEntity row = existing.get();
            boolean sameRequest = row.getRequestHash().equals(requestFingerprint);

            if (sameRequest) {
                log.info("Idempotency hit: merchant={}, key={}, paymentId={}",
                        merchantId, maskKey(key.value()), row.getPaymentId());
                return new IdempotencyOutcome.ReplayOutcome(
                        row.getPaymentId().toString(),
                        row.getResponseStatus(),
                        row.getResponseBody(),
                        row.isTerminal());
            }

            if (row.isTerminal()) {
                log.info("Idempotency terminal replay despite payload mismatch: merchant={}, key={}",
                        merchantId, maskKey(key.value()));
                return new IdempotencyOutcome.ReplayOutcome(
                        row.getPaymentId().toString(),
                        row.getResponseStatus(),
                        row.getResponseBody(),
                        true);
            }

            log.warn("Idempotency conflict: merchant={}, key={}, existingPaymentId={}",
                    merchantId, maskKey(key.value()), row.getPaymentId());
            return new IdempotencyOutcome.ConflictOutcome(
                    key.value(), row.getPaymentId().toString(), row.getRequestHash());
        }

        // 2. No existing row — insert a provisional reservation.
        idempotencyRepository.insertReservation(
                merchantId, key.value(), requestFingerprint, paymentId,
                202, "{}", false, NO_EXPIRY);

        log.info("Idempotency reserved: merchant={}, key={}, paymentId={}",
                merchantId, maskKey(key.value()), paymentId);
        return new IdempotencyOutcome.Proceed();
    }

    @Override
    @Transactional
    public void finalize(final String merchantId,
                         final IdempotencyKey key,
                         final String requestFingerprint,
                         final UUID paymentId,
                         final int responseStatus,
                         final String responsePayload,
                         final boolean isTerminal) {
        IdempotencyEntity row = idempotencyRepository
                .findByMerchantIdAndIdempotencyKey(merchantId, key.value())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Idempotency reservation not found for key=" + key.value()));

        row.finalize(requestFingerprint, paymentId, responseStatus,
                responsePayload, isTerminal, NO_EXPIRY);
        idempotencyRepository.saveAndFlush(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyOutcome.ReplayOutcome> replay(final String merchantId,
                                                             final IdempotencyKey key) {
        return idempotencyRepository.findByMerchantIdAndIdempotencyKey(merchantId, key.value())
                .map(r -> new IdempotencyOutcome.ReplayOutcome(
                        r.getPaymentId().toString(),
                        r.getResponseStatus(),
                        r.getResponseBody(),
                        r.isTerminal()));
    }

    private static String maskKey(final String key) {
        if (key == null || key.isBlank()) return "***";
        return key.length() <= 8 ? "***" : key.substring(0, 6) + "*****";
    }
}
