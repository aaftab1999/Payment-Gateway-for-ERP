package com.paymentgateway.settlement.domain.payment;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable result returned by a {@link com.paymentgateway.settlement.application.port.PaymentProcessor}.
 *
 * <p>The result type explicitly distinguishes between confirmed outcomes
 * and ambiguous ones. The payment state machine treats these differently:</p>
 * <ul>
 *   <li>{@link Type#SUCCESS} → {@link PaymentStatus#SUCCEEDED}</li>
 *   <li>{@link Type#DECLINED} → {@link PaymentStatus#FAILED}</li>
 *   <li>{@link Type#TECHNICAL_FAILURE} → {@link PaymentStatus#FAILED} (no money moved)</li>
 *   <li>{@link Type#UNKNOWN} → {@link PaymentStatus#UNKNOWN} (provider may have debited)</li>
 * </ul>
 */
public record ProviderResult(
        Type type,
        String providerReference,
        String failureCode,
        String failureReason) {

    public enum Type {
        SUCCESS,
        DECLINED,
        TECHNICAL_FAILURE,
        UNKNOWN
    }

    public static ProviderResult success(final String providerReference) {
        return new ProviderResult(Type.SUCCESS, providerReference, null, null);
    }

    public static ProviderResult declined(final String failureCode, final String failureReason) {
        return new ProviderResult(Type.DECLINED, null, failureCode, failureReason);
    }

    public static ProviderResult technicalFailure(final String failureCode, final String failureReason) {
        return new ProviderResult(Type.TECHNICAL_FAILURE, null, failureCode, failureReason);
    }

    public static ProviderResult unknown(final String failureCode, final String failureReason) {
        return new ProviderResult(Type.UNKNOWN, null, failureCode, failureReason);
    }

    public boolean isConfirmedSuccess() {
        return type == Type.SUCCESS;
    }

    public boolean isConfirmedFailure() {
        return type == Type.DECLINED || type == Type.TECHNICAL_FAILURE;
    }

    public boolean isAmbiguous() {
        return type == Type.UNKNOWN;
    }

    public UUID correlationId() {
        return UUID.randomUUID();
    }
}
