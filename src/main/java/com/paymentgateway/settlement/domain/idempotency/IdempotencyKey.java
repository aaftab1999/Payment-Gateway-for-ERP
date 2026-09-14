package com.paymentgateway.settlement.domain.idempotency;

import java.util.Objects;

/**
 * Client-supplied idempotency key for the payment creation API.
 *
 * <p><strong>Scope:</strong> The key is scoped by {@code merchantId}. Two
 * different merchants may reuse the same key value without conflict because
 * the authoritative {@code IDEMPOTENCY} table uses
 * {@code PRIMARY KEY (merchant_id, idempotency_key)}.</p>
 *
 * <p><strong>Immutability:</strong> This is a value object. Instances are
 * equal when their normalized string representation matches.</p>
 */
public final class IdempotencyKey {

    private static final int MAX_LENGTH = 255;

    private final String value;

    private IdempotencyKey(final String value) {
        this.value = value;
    }

    /**
     * Factory. Validates length and non-blankness.
     *
     * @param raw the raw key from the {@code Idempotency-Key} header
     * @return a validated {@link IdempotencyKey}
     * @throws IllegalArgumentException if the key is blank or too long
     */
    public static IdempotencyKey of(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        if (raw.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be ≤ " + MAX_LENGTH + " characters");
        }
        return new IdempotencyKey(raw);
    }

    /** Reconstitutes a key already known to be valid (used by the store layer). */
    public static IdempotencyKey reconstitute(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("idempotency_key must not be null");
        }
        return new IdempotencyKey(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyKey other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}