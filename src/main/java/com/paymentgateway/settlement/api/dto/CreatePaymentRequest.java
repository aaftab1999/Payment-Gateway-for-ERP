package com.paymentgateway.settlement.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for {@code POST /api/v1/payments}.
 *
 * <p><strong>Security:</strong> {@code paymentToken} is accepted in
 * the request but <strong>never echoed back</strong> in any response.</p>
 *
 * @param merchantId     required, ≤ 255 chars
 * @param customerRef    required, alphanumeric/hyphen, ≤ 255 chars
 * @param billRef        required, ERP bill identifier, ≤ 255 chars
 * @param amount         required, positive decimal string, ≤ 2 decimal places for INR
 * @param currency       required, ISO-4217 code (INR, USD, EUR, GBP, JPY, etc.)
 * @param paymentMethod  required, one of: UPI, CREDIT_CARD, DEBIT_CARD, NET_BANKING
 * @param paymentToken   required, tokenized payment reference (never returned)
 */
public record CreatePaymentRequest(
        @NotBlank(message = "merchantId must not be blank")
        @Size(max = 255, message = "merchantId must be ≤ 255 characters")
        String merchantId,

        @NotBlank(message = "customerRef must not be blank")
        @Size(max = 255)
        String customerRef,

        @NotBlank(message = "billRef must not be blank")
        @Size(max = 255)
        String billRef,

        @NotBlank(message = "amount must not be blank")
        @DecimalMin(value = "0.01", message = "amount must be ≥ 0.01")
        String amount,

        @NotBlank(message = "currency must not be blank")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO-4217 code")
        String currency,

        @NotBlank(message = "paymentMethod must not be blank")
        String paymentMethod,

        @NotBlank(message = "paymentToken must not be blank")
        @Size(max = 500)
        String paymentToken
) {
}
