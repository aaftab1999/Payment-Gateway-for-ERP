package com.paymentgateway.settlement.domain.payment;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing the internal payment identifier.
 *
 * <p>This is the gateway's opaque internal ID. The ERP does not see this
 * in the request — it appears only in responses and events. The ERP
 * uses the {@code billRef} to correlate.</p>
 */
public record PaymentId(UUID id) {

    public static PaymentId generate() {
        return new PaymentId(UUID.randomUUID());
    }

    public static PaymentId of(final UUID id) {
        return new PaymentId(Objects.requireNonNull(id, "id must not be null"));
    }

    public static PaymentId of(final String id) {
        return new PaymentId(UUID.fromString(Objects.requireNonNull(id, "id must not be null")));
    }

    public UUID toUuid() {
        return id;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
