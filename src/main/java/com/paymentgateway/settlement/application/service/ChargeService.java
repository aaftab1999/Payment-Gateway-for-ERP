package com.paymentgateway.settlement.application.service;

import com.paymentgateway.settlement.application.port.IdempotencyOutcome;
import com.paymentgateway.settlement.application.port.IdempotencyService;
import com.paymentgateway.settlement.application.port.PaymentProcessor;
import com.paymentgateway.settlement.domain.event.PaymentEventType;
import com.paymentgateway.settlement.domain.idempotency.IdempotencyKey;
import com.paymentgateway.settlement.domain.idempotency.IdempotencyKeyConflictException;
import com.paymentgateway.settlement.domain.payment.Currency;
import com.paymentgateway.settlement.domain.payment.Money;
import com.paymentgateway.settlement.domain.payment.Payment;
import com.paymentgateway.settlement.domain.payment.PaymentId;
import com.paymentgateway.settlement.domain.payment.PaymentMethodType;
import com.paymentgateway.settlement.domain.payment.PaymentStatus;
import com.paymentgateway.settlement.domain.payment.ProviderResult;
import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository;
import com.paymentgateway.settlement.infrastructure.persistence.entity.OutboxEventEntity;
import com.paymentgateway.settlement.application.service.OutboxEventService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the payment-processing workflow.
 *
 * <p><strong>Transaction model (two-phase):</strong></p>
 * <pre>
 *   TX1 (REQUIRED): Reserve idempotency + create payment in CREATED state.
 *     1. IdempotencyService.reserve() — SELECT FOR UPDATE on idempotency row
 *     2. Persist PaymentEntity with status=CREATED
 *     3. Commit — payment is durable before provider call
 *
 *   Provider call (OUTSIDE transaction):
 *     4. Call PaymentProcessor.process(...)
 *     5. No DB locks held during this HTTP round-trip
 *
 *   TX2 (REQUIRED): Apply provider result.
 *     6. SELECT FOR UPDATE on payment row (prevents double-apply)
 *     7. payment.markProcessing() — CREATED → PROCESSING
 *     8. payment.applyProviderResult(result) — PROCESSING → terminal/intermediate
 *     9. IdempotencyService.finalize() — cache the response for replay
 *     10. Commit
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
 *   <li>The idempotency unique constraint serializes concurrent duplicate
 *       requests across multiple application instances.</li>
 * </ul></p>
 */
@Service
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor processor;
    private final IdempotencyService idempotencyService;
    private final OutboxEventService outboxEventService;
    private final TransactionTemplate transactionTemplate;

    public ChargeService(
            final PaymentRepository paymentRepository,
            final PaymentProcessor processor,
            final IdempotencyService idempotencyService,
            final OutboxEventService outboxEventService,
            final PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.processor = processor;
        this.idempotencyService = idempotencyService;
        this.outboxEventService = outboxEventService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Single-call convenience method: creates AND processes a payment.
     * Used by the REST controller for synchronous provider responses.
     *
     * <strong>Transaction flow:</strong>
     * TX1 creates the payment + reserves idempotency atomically. If the
     * idempotency key already exists, the transaction rolls back and the
     * caller handles the replay/conflict. The provider call happens outside
     * any transaction. TX2 applies the result and finalizes the idempotency
     * record.
     */
    public ChargeResult chargeWithOutcome(
            final String merchantId,
            final String customerRef,
            final String billRef,
            final String amountStr,
            final String currencyCode,
            final String paymentMethodStr,
            final String paymentToken,
            final UUID correlationId,
            final String idempotencyKey,
            final UUID paymentId) {

        PaymentMethodType method = PaymentMethodType.fromCode(paymentMethodStr);
        Currency currency = Currency.fromCode(currencyCode);

        if (!currency.isSupported()) {
            throw new IllegalArgumentException("Unsupported currency: " + currencyCode);
        }

        Money amount = Money.of(amountStr, currency);
        IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
        String fingerprint = requestFingerprint(merchantId, customerRef, billRef,
                amountStr, currencyCode, paymentMethodStr, paymentToken);
        PaymentId typedPaymentId = PaymentId.of(paymentId);

        Optional<IdempotencyOutcome.ReplayOutcome> existing = idempotencyService.replay(
                merchantId, key);
        if (existing.isPresent()) {
            UUID replayPaymentId = UUID.fromString(existing.get().paymentId());
            Payment replayedPayment = paymentRepository.findByPaymentId(replayPaymentId)
                    .map(entity -> entity.toDomain(null))
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Payment not found: " + replayPaymentId));
            return new ChargeResult(replayedPayment, true, existing.get().responseStatus());
        }

        log.info("Creating payment: merchant={}, billRef={}, amount={}, method={}",
                merchantId, billRef, amount, method);

        Payment payment;
        try {
            payment = transactionTemplate.execute(status -> {
                Payment created = Payment.create(
                        typedPaymentId, merchantId, customerRef, billRef,
                        amount, method, paymentToken, correlationId
                );
                created.ensureProviderIdempotencyKey();

                PaymentEntity entity = PaymentEntity.fromDomain(created);
                paymentRepository.saveAndFlush(entity);
                OutboxEventEntity createdEvent = outboxEventService.append(
                        created,
                        PaymentEventType.PAYMENT_CREATED,
                        null,
                        "PAYMENT_CREATED",
                        null,
                        null,
                        null
                );
                log.info("Payment outbox event created: paymentId={}, eventId={}, eventType={}",
                        paymentId, createdEvent.getEventId(), PaymentEventType.PAYMENT_CREATED.wireValue());
                log.info("Payment persisted in CREATED state: {}", paymentId);

                IdempotencyOutcome outcome = idempotencyService.reserve(
                        merchantId, key, fingerprint, paymentId);
                if (outcome instanceof IdempotencyOutcome.ReplayOutcome replay) {
                    throw new ReplayDuringReservationException(replay);
                }
                if (outcome instanceof IdempotencyOutcome.ConflictOutcome conflict) {
                    throw new IdempotencyKeyConflictException(
                            "Idempotency key reused with different request payload",
                            key.value(), conflict.existingPaymentId());
                }
                return created;
            });
        } catch (ReplayDuringReservationException replay) {
            UUID replayPaymentId = UUID.fromString(replay.outcome.paymentId());
            Payment replayedPayment = paymentRepository.findByPaymentId(replayPaymentId)
                    .map(entity -> entity.toDomain(null))
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Payment not found: " + replayPaymentId));
            return new ChargeResult(replayedPayment, true, replay.outcome.responseStatus());
        }

        log.info("Calling provider for payment: {}", paymentId);
        ProviderResult providerResult;
        try {
            providerResult = processor.process(
                    payment.getPaymentToken(),
                    payment.getAmount().toMinorUnits(),
                    payment.getAmount().getCurrency().name(),
                    payment.getCorrelationId(),
                    payment.getProviderIdempotencyKey()
            );
        } catch (Exception e) {
            log.error("Provider call failed for payment {}: {}", paymentId, e.getMessage(), e);
            providerResult = ProviderResult.technicalFailure(
                    "PROVIDER_EXCEPTION",
                    "Provider call failed: " + e.getClass().getSimpleName()
            );
        }

        final ProviderResult providerCallResult = providerResult;

        Payment updated = transactionTemplate.execute(status -> {
            Payment result = applyProviderResult(paymentId, providerCallResult, key);
            boolean isTerminal = result.getStatus().isTerminal();
            String responseBody = buildResponseJson(result, correlationId);
            idempotencyService.finalize(
                    merchantId, key, fingerprint,
                    paymentId, statusCodeFor(result.getStatus()), responseBody, isTerminal);
            return result;
        });

        return new ChargeResult(updated, false, statusCodeFor(updated.getStatus()));
    }

    public Payment charge(
            final String merchantId,
            final String customerRef,
            final String billRef,
            final String amountStr,
            final String currencyCode,
            final String paymentMethodStr,
            final String paymentToken,
            final UUID correlationId,
            final String idempotencyKey) {
        return chargeWithOutcome(merchantId, customerRef, billRef, amountStr, currencyCode,
                paymentMethodStr, paymentToken, correlationId, idempotencyKey,
                UUID.randomUUID()).payment();
    }

    /**
     * Applies the provider result to an existing payment.
     *
     * <p>Uses {@code SELECT FOR UPDATE} (via {@code findAndLockByPaymentId})
     * to atomically transition the payment. Retries on optimistic-lock
     * conflict (up to 3 times with backoff).</p>
     *
     * <p><strong>Idempotent apply:</strong> If the payment has already reached
     * a terminal state, the call is a no-op. This prevents duplicate
     * provider results from corrupting state or re-posting the ledger.</p>
     */
    protected Payment applyProviderResult(
            final UUID paymentId,
            final ProviderResult providerResult,
            final IdempotencyKey idempotencyKey) {

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // SELECT FOR UPDATE — lock the row
                PaymentEntity entity = paymentRepository.findAndLockByPaymentId(paymentId)
                        .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));

                // Token is @Transient — not loaded from DB. We don't need it here.
                Payment payment = entity.toDomain(null);

                // Idempotent apply: if already terminal, no-op.
                if (payment.getStatus().isTerminal()) {
                    log.info("Payment {} already terminal ({}) — idempotent apply",
                            paymentId, payment.getStatus());
                    return payment;
                }

                PaymentStatus statusBeforeProcessing = payment.getStatus();
                payment.markProcessing();
                UUID processingCausationId = outboxEventService
                        .findFirstEventId(paymentId, PaymentEventType.PAYMENT_CREATED)
                        .orElse(null);
                OutboxEventEntity processingEvent = outboxEventService.append(
                        payment,
                        PaymentEventType.PAYMENT_PROCESSING_STARTED,
                        statusBeforeProcessing,
                        "SUBMITTED_TO_PROVIDER",
                        processingCausationId,
                        null,
                        null
                );
                log.info("Payment outbox event created: paymentId={}, eventId={}, eventType={}",
                        paymentId, processingEvent.getEventId(),
                        PaymentEventType.PAYMENT_PROCESSING_STARTED.wireValue());

                PaymentStatus statusBeforeResult = payment.getStatus();
                payment.applyProviderResult(providerResult);
                if (payment.getStatus() != statusBeforeResult) {
                    PaymentEventType eventType = PaymentEventType.forStatus(payment.getStatus());
                    String reason = switch (providerResult.type()) {
                        case SUCCESS -> "PROVIDER_SUCCESS";
                        case DECLINED -> "PROVIDER_DECLINED";
                        case TECHNICAL_FAILURE -> "PROVIDER_TECHNICAL_FAILURE";
                        case UNKNOWN -> "PROVIDER_UNKNOWN_OUTCOME";
                    };
                    OutboxEventEntity resultEvent = outboxEventService.append(
                            payment,
                            eventType,
                            statusBeforeResult,
                            reason,
                            processingEvent.getEventId(),
                            null,
                            null
                    );
                    log.info("Payment outbox event created: paymentId={}, eventId={}, eventType={}",
                            paymentId, resultEvent.getEventId(), eventType.wireValue());
                }

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

    /**
     * Handles asynchronous provider webhook callbacks (e.g. Razorpay order.paid
     * or payment.captured events).
     *
     * <p><strong>Idempotency:</strong> If the payment has already reached a
     * terminal state, the call is a no-op. This prevents duplicate webhooks
     * from corrupting state.</p>
     *
     * <p>The payment is resolved from its non-terminal awaiting-resolution
     * state ({@code UNKNOWN} or {@code REQUIRES_RECONCILIATION}) to the
     * target terminal state via {@link Payment#resolveReconciliation}.</p>
     *
     * @param providerReference the provider's order/payment ID (e.g. Razorpay order ID)
     * @param result            the resolved outcome from the webhook
     * @throws EntityNotFoundException if no payment matches the reference
     */
    @Transactional
    public void onProviderWebhook(final String providerReference, final ProviderResult result) {
        PaymentEntity entity = paymentRepository.findByProviderReference(providerReference)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Payment not found for provider reference: " + providerReference));

        Payment payment = entity.toDomain(null);

        if (payment.getStatus().isTerminal()) {
            log.info("Webhook for payment {} already terminal ({}) — idempotent skip",
                    payment.getPaymentId(), payment.getStatus());
            return;
        }

        PaymentStatus statusBefore = payment.getStatus();
        payment.resolveReconciliation(
                mapResultToStatus(result.type()),
                result.failureCode(),
                result.failureReason(),
                result.providerReference() != null
                        ? result.providerReference()
                        : providerReference);

        PaymentEventType eventType = PaymentEventType.forStatus(payment.getStatus());
        String reason = reasonFor(result.type());
        outboxEventService.append(
                payment,
                eventType,
                statusBefore,
                reason,
                null,
                null,
                null
        );

        entity.updateFromDomain(payment);
        paymentRepository.flush();

        log.info("Webhook resolved payment {}: {} → {} (event={})",
                payment.getPaymentId(), statusBefore, payment.getStatus(), eventType.wireValue());
    }

    private static PaymentStatus mapResultToStatus(final ProviderResult.Type type) {
        return switch (type) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case DECLINED -> PaymentStatus.FAILED;
            case TECHNICAL_FAILURE -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.UNKNOWN;
        };
    }

    private static String reasonFor(final ProviderResult.Type type) {
        return switch (type) {
            case SUCCESS -> "PROVIDER_SUCCESS";
            case DECLINED -> "PROVIDER_DECLINED";
            case TECHNICAL_FAILURE -> "PROVIDER_TECHNICAL_FAILURE";
            case UNKNOWN -> "PROVIDER_UNKNOWN_OUTCOME";
        };
    }

    // --- Helpers ---

    /**
     * Computes a deterministic fingerprint of the payment-creation request.
     *
     * <p>The fingerprint is used by the idempotency store to distinguish
     * "same request, retry" from "same key, new request". It is a SHA-256
     * digest of the canonical request fields, sorted by key for stability.</p>
     */
    private static String requestFingerprint(final String merchantId,
                                             final String customerRef,
                                             final String billRef,
                                             final String amountStr,
                                             final String currencyCode,
                                             final String paymentMethodStr,
                                             final String paymentToken) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            // Canonical, sorted fields
            String canonical = "merchantId=" + merchantId +
                    "|customerRef=" + nullToEmpty(customerRef) +
                    "|billRef=" + billRef +
                    "|amount=" + amountStr +
                    "|currency=" + currencyCode +
                    "|method=" + paymentMethodStr +
                    "|token=" + maskToken(paymentToken);
            byte[] hash = digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }

    private static String maskToken(final String token) {
        if (token == null || token.isBlank()) return "***";
        int idx = token.indexOf(':');
        return idx > 0 ? token.substring(0, idx) + ":*****" : "***";
    }

    /**
     * Builds the JSON response payload cached for idempotent replay.
     *
     * <p>Never includes the payment token.</p>
     */
    private static String buildResponseJson(final Payment payment, final UUID correlationId) {
        return String.format(
                "{\"paymentId\":\"%s\",\"merchantId\":\"%s\",\"billRef\":\"%s\"," +
                        "\"amount\":%d,\"currency\":\"%s\",\"paymentMethod\":\"%s\"," +
                        "\"status\":\"%s\",\"providerReference\":%s,\"correlationId\":\"%s\"}",
                payment.getPaymentId().toString(),
                escapeJson(payment.getMerchantId()),
                escapeJson(payment.getBillRef()),
                payment.getAmount().toMinorUnits(),
                payment.getAmount().getCurrency().name(),
                payment.getPaymentMethod().name(),
                payment.getStatus().name(),
                payment.getProviderReference() == null ? "null" :
                        "\"" + escapeJson(payment.getProviderReference()) + "\"",
                correlationId != null ? correlationId.toString() : "null");
    }

    private static String escapeJson(final String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int statusCodeFor(final PaymentStatus status) {
        return switch (status) {
            case SUCCEEDED, FAILED -> 200;
            case UNKNOWN, REQUIRES_RECONCILIATION -> 202;
            default -> 201;
        };
    }
}
