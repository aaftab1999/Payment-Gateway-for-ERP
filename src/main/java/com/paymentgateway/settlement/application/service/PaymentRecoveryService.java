package com.paymentgateway.settlement.application.service;

import com.paymentgateway.settlement.config.RecoveryProperties;
import com.paymentgateway.settlement.domain.payment.Payment;
import com.paymentgateway.settlement.domain.payment.PaymentStatus;
import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;

/**
 * Recovery service for payments stuck in non-terminal states.
 *
 * <p><strong>Responsibility:</strong> Bounded, safe recovery of payments
 * that are stuck in CREATED, PROCESSING, UNKNOWN, or REQUIRES_RECONCILIATION
 * after a provider timeout, network failure, or application crash.</p>
 *
 * <p><strong>Safety guarantees:</strong>
 * <ul>
 *   <li>Provider idempotency key is reused on retry — the provider never
 *       charges the same logical attempt twice.</li>
 *   <li>Retry budget is enforced: after {@code maxRetries} attempts the
 *       payment is left in a clearly-documented recoverable state.</li>
 *   <li>State transitions are atomic via {@code SELECT FOR UPDATE}.</li>
 *   <li>Backoff between retries prevents hammering the provider.</li>
 *   <li>Recovery can run after application restart because all state is
 *       persisted in the database.</li>
 * </ul></p>
 *
 * <p><strong>What recovery does NOT do:</strong> It does NOT implement
 * settlement, reconciliation files, refunds, or chargebacks. Those belong
 * to later stages.</p>
 */
@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    private final PaymentRepository paymentRepository;
    private final RecoveryProperties properties;

    public PaymentRecoveryService(final PaymentRepository paymentRepository,
                                  final RecoveryProperties properties) {
        this.paymentRepository = paymentRepository;
        this.properties = properties;
    }

    /**
     * Runs a single recovery sweep.
     *
     * <p>Called by the scheduled job. Each invocation processes up to
     * {@code maxPerSweep} payments eligible for recovery.</p>
     *
     * @param now current timestamp
     * @return the number of payments processed
     */
    @Transactional
    public int sweep(final Instant now) {
        // The cutoff is the earliest timestamp a payment must have been
        // created to be considered for recovery. We use a single cutoff
        // based on the oldest timeout (CREATED) to avoid re-processing
        // fresh payments.
        Instant cutoff = now.minusMillis(properties.getCreatedTimeoutMs());

        List<PaymentEntity> candidates = paymentRepository.findForRecovery(cutoff, now);
        if (candidates.isEmpty()) {
            return 0;
        }

        int processed = 0;
        int limit = Math.min(candidates.size(), properties.getMaxPerSweep());
        for (int i = 0; i < limit; i++) {
            PaymentEntity entity = candidates.get(i);
            try {
                recoverPayment(entity, now);
                processed++;
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic lock conflict during recovery for payment {}",
                        entity.getPaymentId());
            } catch (Exception e) {
                log.error("Recovery failed for payment {}: {}",
                        entity.getPaymentId(), e.getMessage(), e);
            }
        }

        log.info("Recovery sweep complete: processed={}/{}", processed, limit);
        return processed;
    }

    /**
     * Attempts to recover a single payment.
     *
     * <p><strong>Strategy by status:</strong>
     * <ul>
     *   <li>CREATED — re-submit to provider (the original call never
     *       completed). Uses the stable provider idempotency key.</li>
     *   <li>PROCESSING — the provider call is in-flight or timed out.
     *       Re-submit with the same provider idempotency key.</li>
     *   <li>UNKNOWN — the provider returned an ambiguous result.
     *       Re-submit with the same key; if the provider confirms the
     *       original outcome, the payment transitions to terminal.</li>
     *   <li>REQUIRES_RECONCILIATION — same as UNKNOWN but with a different
     *       reason code.</li>
     * </ul></p>
     *
     * <p><strong>Retry budget:</strong> If the attempt count reaches
     * {@code maxRetries}, the payment is left in its current non-terminal
     * state with the exhaustion reason recorded. It remains visible for
     * manual investigation or a later reconciliation pass.</p>
     */
    @Transactional
    protected void recoverPayment(final PaymentEntity entity, final Instant now) {
        // Re-acquire the lock to prevent concurrent recovery.
        PaymentEntity locked = paymentRepository.findAndLockByPaymentId(entity.getPaymentId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Payment not found: " + entity.getPaymentId()));

        Payment payment = locked.toDomain(null);

        // Check if the retry budget is exhausted.
        if (payment.getAttemptCount() >= properties.getMaxRetries()) {
            log.warn("Payment {} has exhausted retry budget (attemptCount={}), leaving in {}",
                    payment.getPaymentId(), payment.getAttemptCount(), payment.getStatus());
            payment.markRetryExhausted("Retry budget exhausted after " +
                    properties.getMaxRetries() + " attempts");
            locked.updateFromDomain(payment);
            return;
        }

        // Check if the next retry is scheduled in the future.
        if (payment.getNextRetryAt() != null && payment.getNextRetryAt().isAfter(now)) {
            return; // Not yet due.
        }

        // Ensure the provider idempotency key is stable.
        payment.ensureProviderIdempotencyKey();

        // Re-enter PROCESSING for the retry.
        if (payment.getStatus() == PaymentStatus.UNKNOWN ||
                payment.getStatus() == PaymentStatus.REQUIRES_RECONCILIATION) {
            payment.markRetrySubmitted();
        } else if (payment.getStatus() == PaymentStatus.CREATED ||
                payment.getStatus() == PaymentStatus.PROCESSING) {
            // Already in PROCESSING or CREATED — just record the attempt.
            payment.markProcessing();
        }

        // Schedule the next retry with exponential backoff.
        long backoff = Math.min(
                properties.getBaseBackoffMs() * (1L << payment.getAttemptCount()),
                properties.getMaxBackoffMs());
        payment.scheduleNextRetry(now.plusMillis(backoff));

        locked.updateFromDomain(payment);
        log.info("Scheduled recovery for payment {}: status={}, attemptCount={}, nextRetryAt={}",
                payment.getPaymentId(), payment.getStatus(),
                payment.getAttemptCount(), payment.getNextRetryAt());
    }

    /**
     * Counts payments eligible for recovery (for metrics).
     */
    public long countForRecovery(final Instant now) {
        Instant cutoff = now.minusMillis(properties.getCreatedTimeoutMs());
        return paymentRepository.countForRecovery(cutoff, now);
    }

    public long countAwaitingReconciliation() {
        return paymentRepository.countAwaitingReconciliation();
    }

    public long countAwaitingProcessing() {
        return paymentRepository.countAwaitingProcessing();
    }
}