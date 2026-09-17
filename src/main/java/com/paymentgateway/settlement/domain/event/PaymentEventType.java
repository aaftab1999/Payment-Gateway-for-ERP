package com.paymentgateway.settlement.domain.event;

public enum PaymentEventType {
    PAYMENT_CREATED("PaymentCreated", 1),
    PAYMENT_PROCESSING_STARTED("PaymentProcessingStarted", 1),
    PAYMENT_SUCCEEDED("PaymentSucceeded", 1),
    PAYMENT_FAILED("PaymentFailed", 1),
    PAYMENT_UNKNOWN("PaymentUnknown", 1),
    PAYMENT_RETRY_SCHEDULED("PaymentRetryScheduled", 1),
    PAYMENT_RETRY_EXHAUSTED("PaymentRetryExhausted", 1);

    private final String wireValue;
    private final int version;

    PaymentEventType(final String wireValue, final int version) {
        this.wireValue = wireValue;
        this.version = version;
    }

    public String wireValue() {
        return wireValue;
    }

    public int version() {
        return version;
    }

    public static PaymentEventType forStatus(final com.paymentgateway.settlement.domain.payment.PaymentStatus status) {
        return switch (status) {
            case SUCCEEDED -> PAYMENT_SUCCEEDED;
            case FAILED -> PAYMENT_FAILED;
            case UNKNOWN -> PAYMENT_UNKNOWN;
            default -> throw new IllegalArgumentException("No payment event for status: " + status);
        };
    }
}
