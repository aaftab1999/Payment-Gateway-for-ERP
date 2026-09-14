package com.paymentgateway.settlement.application.port;

/**
 * Outcome of an idempotency reservation attempt.
 *
 * <p><strong>Design note:</strong> This is a plain class hierarchy (not a
 * sealed interface) to avoid Java 21 sealed-class compilation issues
 * across package boundaries. The three concrete subclasses
 * ({@link Proceed}, {@link ReplayOutcome}, {@link ConflictOutcome}) are
 * the only valid implementations.</p>
 */
public class IdempotencyOutcome {

    private IdempotencyOutcome() {
        // utility
    }

    /** The key was not present — caller should create the payment. */
    public static final class Proceed extends IdempotencyOutcome {
    }

    /** A stored response exists — caller should return it. */
    public static final class ReplayOutcome extends IdempotencyOutcome {
        private final String paymentId;
        private final int responseStatus;
        private final String responseBody;
        private final boolean terminal;

        public ReplayOutcome(final String paymentId, final int responseStatus,
                             final String responseBody, final boolean terminal) {
            this.paymentId = paymentId;
            this.responseStatus = responseStatus;
            this.responseBody = responseBody;
            this.terminal = terminal;
        }

        public String paymentId() { return paymentId; }
        public int responseStatus() { return responseStatus; }
        public String responseBody() { return responseBody; }
        public boolean terminal() { return terminal; }
    }

    /** The key exists with a different request — caller should reject. */
    public static final class ConflictOutcome extends IdempotencyOutcome {
        private final String idempotencyKey;
        private final String existingPaymentId;
        private final String existingRequestFingerprint;

        public ConflictOutcome(final String idempotencyKey, final String existingPaymentId,
                               final String existingRequestFingerprint) {
            this.idempotencyKey = idempotencyKey;
            this.existingPaymentId = existingPaymentId;
            this.existingRequestFingerprint = existingRequestFingerprint;
        }

        public String idempotencyKey() { return idempotencyKey; }
        public String existingPaymentId() { return existingPaymentId; }
        public String existingRequestFingerprint() { return existingRequestFingerprint; }
    }
}
