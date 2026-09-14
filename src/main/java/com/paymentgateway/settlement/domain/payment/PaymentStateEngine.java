package com.paymentgateway.settlement.domain.payment;

/**
 * Payment state machine — validates transitions between {@link PaymentStatus} values.
 *
 * <p>All transition logic lives here in the domain layer. The {@link Payment}
 * aggregate delegates to this engine, making the state machine fully unit-testable
 * without Spring or persistence.</p>
 *
 * <p><strong>Valid transitions (Stage 4):</strong></p>
 * <pre>
 *   CREATED        → PROCESSING
 *   PROCESSING     → SUCCEEDED | FAILED | UNKNOWN | REQUIRES_RECONCILIATION
 *   UNKNOWN        → SUCCEEDED | FAILED | PROCESSING  (retry)
 *   REQUIRES_RECONCILIATION → SUCCEEDED | FAILED | PROCESSING  (retry)
 * </pre>
 *
 * <p><strong>Why UNKNOWN/REQUIRES_RECONCILIATION can return to PROCESSING:</strong>
 * A bounded retry of an uncertain payment is safe <em>only</em> when the
 * provider idempotency key is reused. The recovery worker re-enters
 * PROCESSING, re-submits with the same provider key, and either lands in a
 * terminal state or exhausts its retry budget and leaves the payment in a
 * clearly-documented recoverable state. This is NOT an uncontrolled loop —
 * the retry budget is enforced by the application, not the state machine.</p>
 *
 * <p><strong>Why {@code REQUIRES_RECONCILIATION} has exit transitions:</strong>
 * A payment that reached this state was submitted to the provider but the
 * gateway never received a definitive response (timeout with a bank
 * discrepancy). The payment is not terminal — it must be resolved by a
 * reconciliation/polling job that confirms the actual outcome with the
 * provider. The exit transitions are the same safe administrative actions
 * used to resolve {@code UNKNOWN}: confirm success or confirm failure.
 * No new money moves; the ledger is not re-posted.</p>
 *
 * <p><strong>Invalid transitions (rejected):</strong></p>
 * <ul>
 *   <li>Any → CREATED (can't go back)</li>
 *   <li>SUCCEEDED → * (terminal — no outgoing)</li>
 *   <li>FAILED → * (terminal — no outgoing)</li>
 *   <li>VOIDED → * (terminal — no outgoing, Stage 4+)</li>
 *   <li>REFUNDED → * (terminal — no outgoing, Stage 4+)</li>
 *   <li>PROCESSING → CREATED</li>
 *   <li>PROCESSING → PROCESSING (no self-loop)</li>
 * </ul>
 */
public final class PaymentStateEngine {

    private PaymentStateEngine() {
        // Utility class — no instances
    }

    /**
     * Validates and returns the next status.
     *
     * @param current   the current status (must not be null)
     * @param target    the requested next status (must not be null)
     * @param reason    reason for transition (e.g. "PROVIDER_SUCCESS", "TIMEOUT")
     * @return the validated next status
     * @throws IllegalStateTransitionException if the transition is not allowed
     */
    public static PaymentStatus transition(
            final PaymentStatus current,
            final PaymentStatus target,
            final TransitionReason reason) {

        if (current == null) {
            throw new IllegalStateTransitionException("Current status cannot be null", null, target);
        }
        if (target == null) {
            throw new IllegalStateTransitionException("Target status cannot be null", current, null);
        }

        if (current == target) {
            throw new IllegalStateTransitionException(
                    "Payment is already in status " + current, current, target);
        }

        if (current.isTerminal()) {
            throw new IllegalStateTransitionException(
                    "Cannot transition from terminal status " + current, current, target);
        }

        boolean isValid = switch (current) {
            case CREATED -> target == PaymentStatus.PROCESSING;
            case PROCESSING -> target == PaymentStatus.SUCCEEDED
                    || target == PaymentStatus.FAILED
                    || target == PaymentStatus.UNKNOWN
                    || target == PaymentStatus.REQUIRES_RECONCILIATION;
            case UNKNOWN -> target == PaymentStatus.SUCCEEDED
                    || target == PaymentStatus.FAILED
                    || target == PaymentStatus.PROCESSING;   // retry: UNKNOWN → PROCESSING
            case REQUIRES_RECONCILIATION -> target == PaymentStatus.SUCCEEDED
                    || target == PaymentStatus.FAILED
                    || target == PaymentStatus.PROCESSING;   // retry: REQUIRES_RECONCILIATION → PROCESSING
            case SUCCEEDED, FAILED, VOIDED, REFUNDED -> false;
        };

        if (!isValid) {
            throw new IllegalStateTransitionException(
                    "Invalid transition: " + current + " → " + target,
                    current, target);
        }

        return target;
    }

    /** Reason for a state transition — aids debugging and audit. */
    public enum TransitionReason {
        SUBMITTED_TO_PROVIDER,
        PROVIDER_SUCCESS,
        PROVIDER_DECLINED,
        PROVIDER_TECHNICAL_FAILURE,
        PROVIDER_TIMEOUT,
        PROVIDER_UNKNOWN_OUTCOME,
        RECONCILIATION_MATCHED,
        RECONCILIATION_FAILED,
        /** Bounded retry: an uncertain payment was re-submitted to the provider. */
        RETRY_SUBMITTED,
        /** Bounded retry exhausted: the payment remains in a recoverable state. */
        RETRY_EXHAUSTED
    }
}
