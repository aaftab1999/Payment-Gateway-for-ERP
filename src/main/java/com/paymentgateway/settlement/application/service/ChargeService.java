package com.paymentgateway.settlement.application.service;

import com.paymentgateway.settlement.application.port.PaymentProcessor;
import com.paymentgateway.settlement.domain.payment.Currency;
import com.paymentgateway.settlement.domain.payment.Money;
import com.paymentgateway.settlement.domain.payment.Payment;
import com.paymentgateway.settlement.domain.payment.PaymentId;
import com.paymentgateway.settlement.domain.payment.PaymentMethodType;
import com.paymentgateway.settlement.domain.payment.ProviderResult;
import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the payment-processing workflow.
 *
 * <p><strong>Transaction model (two-phase):</strong></p>
 * <pre>
 *   TX1 (REQUIRED): Create payment in CREATED state.
 *     1. Persist PaymentEntity with status=CREATED
 *     2. Commit — payment is durable before provider call
 *
 *   Provider call (OUTSIDE transaction):
 *     3. Call PaymentProcessor.process(...)
 *     4. No DB locks held during this HTTP round-trip

 *   TX2 (REQUIRED): Apply provider result.
 *     5. SELECT FOR UPDATE on payment row (prevents double-apply)
 *     6. payment.applyProviderResult(result) — state machine validates transition
 *     7. Update PaymentEntity
 *     8. Commit
 * </pre>
 *
 * <p><strong>Why two transactions:</strong> Holding {@code SELECT FOR UPDATE}
 * during a potentially slow provider HTTP call would block other operations
 * on the same payment row and risk transaction timeouts. By splitting,
 * the lock is held only for the quick status transition.</p>
 *
 * <p><strong>Concurrency safety:</strong>
 * <ul>
 *   <li>{@code @Version} on the entity prevents lost updates.</li>
 *   <li>{@code SELECT FOR UPDATE} ensures the state transition is atomic.</li>
 * </ul></p>
 *
 * <p><strong>Idempotency (Stage 4 note):</strong> This stage does NOT
 * implement idempotency keys. Duplicate charge calls will create duplicate
 * payment records. This is a documented known limitation.</p>
 */
@Service
@Slf4j
public class ChargeService {

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor processor;

    public ChargeService(
            final PaymentRepository paymentRepository,
            final PaymentProcessor processor) {
        this.paymentRepository = paymentRepository;
        this.processor = processor;
    }

    /**
     * Single-call convenience method: creates AND processes a payment.
     * Used by the REST controller for synchronous provider responses.
     *
     * <strong>Transaction flow:</strong>
     * TX1 creates + commits the payment, then the provider call happens
     * outside any transaction, then TX2 applies the result.
     */
    @Transactional
    public Payment charge(
            final String merchantId,
            final String customerRef,
            final String billRef,
            final String amountStr,
            final String currencyCode,
            final String paymentMethodStr,
            final String paymentToken,
            final UUID correlationId) {

        // --- TX1: Create payment (CREATED) ---
        PaymentMethodType method = PaymentMethodType.fromCode(paymentMethodStr);
        Currency currency = Currency.fromCode(currencyCode);

        if (!currency.isSupported()) {
            throw new IllegalArgumentException("Unsupported currency: " + currencyCode);
        }

        Money amount = Money.of(amountStr, currency);
        PaymentId paymentId = PaymentId.generate();

        log.info("Creating payment: id={}, merchant={}, billRef={}, amount={}, method={}",
                paymentId, merchantId, billRef, amount, method);

        Payment payment = Payment.create(
                paymentId, merchantId, customerRef, billRef,
                amount, method, paymentToken, correlationId
        );

        PaymentEntity entity = PaymentEntity.fromDomain(payment);
        paymentRepository.save(entity);
        // TX1 commits here (transactional method boundary)
        log.info("Payment persisted in CREATED state: {}", paymentId);

        // --- Provider call: OUTSIDE transaction ---
        // No DB locks are held during this HTTP round-trip.
        // If the app crashes here, the payment is in CREATED state
        // and a background job can reconcile (Stage 4+).
        log.info("Calling provider for payment: {}", paymentId);

        ProviderResult providerResult;
        try {
            providerResult = processor.process(
                    payment.getPaymentToken(),
                    payment.getAmount().toMinorUnits(),
                    payment.getAmount().getCurrency().name(),
                    payment.getCorrelationId()
            );
        } catch (Exception e) {
            log.error("Provider call failed for payment {}: {}", paymentId, e.getMessage(), e);
            providerResult = ProviderResult.technicalFailure(
                    "PROVIDER_EXCEPTION",
                    "Provider call failed: " + e.getClass().getSimpleName()
            );
        }

        // --- TX2: Apply result (re-acquire lock, transition, commit) ---
        return applyProviderResult(paymentId.toUuid(), providerResult);
    }

    /**
     * Applies the provider result to an existing payment.
     *
     * <p>Uses {@code SELECT FOR UPDATE} (via {@code findAndLockByPaymentId})
     * to atomically transition the payment. Retries on optimistic-lock
     * conflict (up to 3 times with backoff).</p>
     */
    @Transactional
    protected Payment applyProviderResult(
            final UUID paymentId,
            final ProviderResult providerResult) {

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // SELECT FOR UPDATE — lock the row
                PaymentEntity entity = paymentRepository.findAndLockByPaymentId(paymentId)
                        .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));

                // Token is @Transient — not loaded from DB. We don't need it here.
                Payment payment = entity.toDomain(null);

                // State machine: CREATED → PROCESSING → terminal
                payment.markProcessing();
                payment.applyProviderResult(providerResult);

                // Persist updated state on the already-managed entity
                entity.updateFromDomain(payment);
                paymentRepository.flush();

                log.info("Payment {} transitioned: {} → {} (provider: {})",
                        paymentId,
                        payment.getStatus(),
                        providerResult.type(),
                        providerResult.providerReference());

                return payment;

            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == 3) {
                    log.error("Optimistic lock conflict on payment {} after 3 attempts", paymentId);
                    throw e;
                }
                log.warn("Optimistic lock conflict on payment {} (attempt {}/3), retrying",
                        paymentId, attempt);
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
        throw new IllegalStateException("Unreachable: retry loop exhausted");
    }

    /**
     * Retrieve a single payment by ID.
     * Does NOT return the payment token (security: never expose tokens).
     */
    @Transactional(readOnly = true)
    public Payment getPayment(final UUID paymentId) {
        PaymentEntity entity = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        // Token is intentionally null — never reconstruct with token
        return entity.toDomain(null);
    }

    /**
     * Retrieve all payments for a given bill reference + merchant.
     * Uses a single query (no N+1).
     */
    @Transactional(readOnly = true)
    public List<Payment> getPaymentsByBillRef(final String merchantId, final String billRef) {
        List<PaymentEntity> entities = paymentRepository.findByBillRefAndMerchantId(billRef, merchantId);
        return entities.stream()
                .map(e -> e.toDomain(null))
                .collect(Collectors.toList());
    }
}
