package com.paymentgateway.settlement.domain.payment;

import java.time.Instant;

/**
 * Immutable record of a single provider attempt for a payment.
 *
 * <p><strong>Purpose:</strong> Binds the provider idempotency key to a
 * specific attempt so that retries of the same attempt reuse the same key.
 * The key is derived deterministically from the payment attempt and must
 * remain stable across application restarts — it is persisted on the
 * payment aggregate.</p>
 *
 * <p><strong>Not stored separately:</strong> This is a value object carried
 * on the {@link Payment} aggregate. The authoritative copy lives in the
 * {@code payment} row ({@code provider_idempotency_key}).</p>
 */
public record ProviderAttempt(
        String providerIdempotencyKey,
        int attemptNumber,
        Instant attemptedAt,
        String resultType,
        String failureReason) {

    public ProviderAttempt {
        if (providerIdempotencyKey == null || providerIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("providerIdempotencyKey must not be blank");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be ≥ 1");
        }
        if (attemptedAt == null) {
            throw new IllegalArgumentException("attemptedAt must not be null");
        }
    }

    public static ProviderAttempt of(
            final String providerIdempotencyKey,
            final int attemptNumber,
            final Instant attemptedAt,
            final String resultType,
            final String failureReason) {
        return new ProviderAttempt(providerIdempotencyKey, attemptNumber, attemptedAt,
                resultType, failureReason);
    }
}