package com.paymentgateway.settlement.infrastructure.external.provider.razorpay;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Response body from {@code POST /v1/orders} (Razorpay Orders API).
 *
 * @param id          Razorpay order ID (e.g. "order_DaZlswtdcn9UNV")
 * @param entity      always "order"
 * @param amount      amount in sub-units
 * @param amountPaid  amount captured so far (0 if not yet captured)
 * @param amountDue   remaining amount to capture
 * @param currency    ISO 4217 currency code
 * @param receipt     the receipt value sent in the request
 * @param status      order lifecycle: "created" | "attempted" | "paid"
 * @param attempts    number of payment attempts made
 * @param notes       key-value metadata echoed from the request
 * @param createdAt   Unix timestamp of order creation
 */
public record RazorpayOrderResponse(
        String id,
        String entity,
        long amount,
        @JsonProperty("amount_paid") long amountPaid,
        @JsonProperty("amount_due") long amountDue,
        String currency,
        String receipt,
        String status,
        int attempts,
        Map<String, String> notes,
        @JsonProperty("created_at") long createdAt
) {
}
