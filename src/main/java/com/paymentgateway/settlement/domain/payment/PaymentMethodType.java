package com.paymentgateway.settlement.domain.payment;

/**
 * Payment method type.
 *
 * <p>This enum is the integration point between the payment domain and the
 * provider adapter layer. New methods can be added here without changing
 * the core payment workflow — the {@code PaymentProcessor} SPI selects
 * the adapter based on this type.</p>
 *
 * <p>Design: the enum lives in the domain (not the infra layer) because
 * it is a business concept — the ERP selects a payment method and the
 * gateway records which method was used. The provider adapter mapping
 * happens in {@code application.port.PaymentProcessor}.</p>
 */
public enum PaymentMethodType {
    UPI("Unified Payments Interface"),
    CREDIT_CARD("Credit Card"),
    DEBIT_CARD("Debit Card"),
    NET_BANKING("Net Banking");

    private final String displayName;

    PaymentMethodType(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PaymentMethodType fromCode(final String code) {
        try {
            return PaymentMethodType.valueOf(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported payment method: " + code);
        }
    }
}
