package com.paymentgateway.settlement.infrastructure.external.provider.razorpay;

/**
 * Webhook payload delivered by Razorpay to the {@code X-Razorpay-Signature}
 * endpoint.
 *
 * <p>Razorpay sends webhooks as JSON objects with the event name and a nested
 * payload containing the relevant entity (payment or order). The gateway
 * correlates the webhook to a payment via the {@code order_id} field inside the
 * payload, which is the providerReference stored on the payment record.</p>
 *
 * @param event      the event type (e.g. "payment.captured", "payment.failed", "order.paid")
 * @param accountId  Razorpay account ID that triggered the event
 * @param payload    nested payload containing the entity data
 * @param createdAt  Unix timestamp of the event
 */
public record RazorpayWebhookEvent(
        String event,
        String accountId,
        RazorpayWebhookPayload payload,
        long createdAt
) {
    public record RazorpayWebhookPayload(
            RazorpayPaymentEntity payment
    ) {
    }

    public record RazorpayPaymentEntity(
            String id,
            String orderId,
            String status,
            long amount,
            String currency,
            String method,
            String resultCode,
            String errorCode,
            String errorDescription
    ) {
    }
}
