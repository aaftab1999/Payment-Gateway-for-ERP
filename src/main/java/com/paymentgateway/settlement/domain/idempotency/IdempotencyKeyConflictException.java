package com.paymentgateway.settlement.domain.idempotency;

import com.paymentgateway.settlement.domain.payment.Payment;

/**
 * Domain exception: an idempotency key was reused with a different request
 * payload while the original payment is still in-flight (non-terminal).
 *
 * <p><strong>Why this is a 409 CONFLICT, not a 400:</strong>
 * The caller did nothing wrong in isolation — they submitted a request that
 * is internally consistent. The conflict is with a *prior concurrent*
 * request. Returning 409 lets the caller distinguish "fix your payload"
 * from "retry later / use the original key".</p>
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    private final String idempotencyKey;
    private final String existingPaymentId;

    public IdempotencyKeyConflictException(
            final String message,
            final String idempotencyKey,
            final String existingPaymentId) {
        super(message);
        this.idempotencyKey = idempotencyKey;
        this.existingPaymentId = existingPaymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getExistingPaymentId() {
        return existingPaymentId;
    }
}