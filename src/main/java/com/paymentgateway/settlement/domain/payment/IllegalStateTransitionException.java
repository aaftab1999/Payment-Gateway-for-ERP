package com.paymentgateway.settlement.domain.payment;

import java.util.Objects;

/**
 * Thrown when a payment state transition violates the state machine.
 *
 * <p>Contains the {@link #currentStatus} and {@link #targetStatus} for
 * diagnostic purposes. This exception is caught by the service layer and
 * translated to an API error or logged internally.</p>
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final PaymentStatus currentStatus;
    private final PaymentStatus targetStatus;

    public IllegalStateTransitionException(
            final String message,
            final PaymentStatus currentStatus,
            final PaymentStatus targetStatus) {
        super(message);
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public PaymentStatus getCurrentStatus() {
        return currentStatus;
    }

    public PaymentStatus getTargetStatus() {
        return targetStatus;
    }

    @Override
    public String toString() {
        return "IllegalStateTransitionException{" +
                "currentStatus=" + currentStatus +
                ", targetStatus=" + targetStatus +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}
