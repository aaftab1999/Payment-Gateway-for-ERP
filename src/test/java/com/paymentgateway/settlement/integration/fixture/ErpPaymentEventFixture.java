package com.paymentgateway.settlement.integration.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.domain.event.PaymentLifecycleEvent;
import com.paymentgateway.settlement.domain.payment.PaymentStatus;
import com.paymentgateway.settlement.infrastructure.messaging.ErpConsumerMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ErpPaymentEventFixture {

    private static final Logger log = LoggerFactory.getLogger(ErpPaymentEventFixture.class);

    private final ObjectMapper objectMapper;
    private final ErpConsumerMetrics metrics;
    private final Map<java.util.UUID, Boolean> processedEventIds = new ConcurrentHashMap<>();
    private final Map<String, ErpInvoiceState> invoiceStates = new ConcurrentHashMap<>();
    private final AtomicInteger businessEffects = new AtomicInteger();

    public ErpPaymentEventFixture(final ObjectMapper objectMapper,
                                  final ErpConsumerMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public boolean process(final String eventJson) {
        try {
            PaymentLifecycleEvent event = objectMapper.readValue(
                    eventJson, PaymentLifecycleEvent.class);
            ErpInvoiceState incoming = toInvoiceState(event);
            if (incoming == null) {
                return true;
            }
            String erpReference = requireReference(event);
            if (processedEventIds.putIfAbsent(event.eventId(), Boolean.TRUE) != null) {
                metrics.recordDuplicate();
                log.info("ERP fixture ignored duplicate event: eventId={}, paymentId={}",
                        event.eventId(), event.paymentId());
                return false;
            }

            java.util.concurrent.atomic.AtomicBoolean applied = new java.util.concurrent.atomic.AtomicBoolean(false);
            invoiceStates.compute(erpReference, (reference, current) -> {
                if (current == null || rank(incoming) > rank(current)) {
                    applied.set(true);
                    return incoming;
                }
                log.info("ERP fixture ignored out-of-order event: eventId={}, paymentId={}, current={}, incoming={}",
                        event.eventId(), event.paymentId(), current, incoming);
                return current;
            });
            if (applied.get()) {
                businessEffects.incrementAndGet();
                metrics.recordOutcome();
            }
            return true;
        } catch (Exception e) {
            metrics.recordFailure();
            throw new IllegalArgumentException("ERP fixture could not process payment event", e);
        }
    }

    public ErpInvoiceState invoiceState(final String erpReference) {
        return invoiceStates.get(erpReference);
    }

    public int businessEffectCount() {
        return businessEffects.get();
    }

    public int processedEventCount() {
        return processedEventIds.size();
    }

    private ErpInvoiceState toInvoiceState(final PaymentLifecycleEvent event) {
        if ("PaymentRetryExhausted".equals(event.eventType())) {
            return ErpInvoiceState.PAYMENT_FAILED;
        }
        return switch (event.paymentStatus()) {
            case SUCCEEDED -> ErpInvoiceState.PAID;
            case FAILED -> ErpInvoiceState.PAYMENT_FAILED;
            case UNKNOWN -> ErpInvoiceState.REVIEW_REQUIRED;
            case REFUNDED -> ErpInvoiceState.REFUNDED;
            case VOIDED -> ErpInvoiceState.VOIDED;
            case CREATED, PROCESSING, REQUIRES_RECONCILIATION -> null;
        };
    }

    private static int rank(final ErpInvoiceState state) {
        return switch (state) {
            case PAID -> 4;
            case REFUNDED -> 3;
            case VOIDED -> 2;
            case PAYMENT_FAILED -> 1;
            case REVIEW_REQUIRED -> 0;
        };
    }

    private static String requireReference(final PaymentLifecycleEvent event) {
        if (event.erpReference() == null || event.erpReference().isBlank()) {
            throw new IllegalArgumentException("ERP reference is required for an invoice outcome");
        }
        return event.erpReference();
    }

    public enum ErpInvoiceState {
        PAID,
        PAYMENT_FAILED,
        REVIEW_REQUIRED,
        REFUNDED,
        VOIDED
    }
}
