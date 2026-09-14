package com.paymentgateway.settlement.api.dto;

import com.paymentgateway.settlement.domain.payment.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Maps between domain {@link Payment} and API DTOs.
 *
 * <p>No payment token is ever included in the response — the domain's
 * {@code paymentToken} field is deliberately omitted from the mapping.</p>
 */
public final class PaymentDtoMapper {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private PaymentDtoMapper() {
    }

    public static PaymentResponse toResponse(final Payment payment,
                                             final String baseUrl) {
        return new PaymentResponse(
                payment.getPaymentId().toString(),
                payment.getMerchantId(),
                payment.getCustomerRef(),
                payment.getBillRef(),
                payment.getAmount().getAmount(),
                payment.getAmount().getCurrency().name(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getProviderReference(),
                payment.getFailureCode(),
                payment.getFailureReason(),
                payment.getCorrelationId() != null
                        ? payment.getCorrelationId().toString()
                        : null,
                ISO_FORMATTER.format(payment.getCreatedAt()),
                ISO_FORMATTER.format(payment.getUpdatedAt()),
                new PaymentResponse.Links(
                        baseUrl + "/payments/" + payment.getPaymentId().toString(),
                        baseUrl + "/payments/by-bill/" + payment.getBillRef()
                )
        );
    }
}
