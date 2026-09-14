package com.paymentgateway.settlement.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paymentgateway.settlement.domain.payment.PaymentMethodType;
import com.paymentgateway.settlement.domain.payment.PaymentStatus;

import java.math.BigDecimal;

/**
 * Response DTO for payment operations.
 *
 * <p><strong>Security:</strong> This DTO contains <em>no</em> payment
 * tokens, provider credentials, or sensitive data. The {@code paymentToken}
 * is never serialized in any response.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        String paymentId,
        String merchantId,
        String customerRef,
        String billRef,
        BigDecimal amount,
        String currency,
        PaymentMethodType paymentMethod,
        PaymentStatus status,
        String providerReference,
        String failureCode,
        String failureReason,
        String correlationId,
        String createdAt,
        String updatedAt,
        Links links
) {

    public record Links(
            String self,
            String billPayments
    ) {}
}
