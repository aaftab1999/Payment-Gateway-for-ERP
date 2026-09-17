package com.paymentgateway.settlement.domain.event;

import com.paymentgateway.settlement.domain.payment.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentLifecycleEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        long eventOrder,
        UUID paymentId,
        String merchantId,
        String erpReference,
        long amountMinor,
        String currency,
        PaymentStatus paymentStatus,
        PaymentStatus previousPaymentStatus,
        String providerReference,
        String failureCode,
        Instant occurredAt,
        Instant nextAttemptAt,
        Integer retryAttempt,
        UUID correlationId,
        UUID causationId,
        String reasonCode
) {
}
