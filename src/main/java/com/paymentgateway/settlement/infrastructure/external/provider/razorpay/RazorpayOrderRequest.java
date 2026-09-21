package com.paymentgateway.settlement.infrastructure.external.provider.razorpay;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Request body for {@code POST /v1/orders} (Razorpay Orders API).
 *
 * @param amount       amount in sub-units (e.g. paise for INR)
 * @param currency     ISO 4217 currency code (e.g. "INR")
 * @param receipt      ERP-side reference / idempotency key (≤ 40 chars)
 * @param notes        key-value metadata (e.g. correlationId)
 * @param paymentCapture 1 = automatic capture, 0 = manual capture
 */
public record RazorpayOrderRequest(
        long amount,
        String currency,
        String receipt,
        Map<String, String> notes,
        @JsonProperty("payment_capture") int paymentCapture
) {
}
