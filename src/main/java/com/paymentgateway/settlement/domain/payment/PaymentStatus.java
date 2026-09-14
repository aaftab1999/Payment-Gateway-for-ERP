package com.paymentgateway.settlement.domain.payment;

/**
 * Payment status state machine.
 *
 * <p><strong>Lifecycle (Stage 3 subset):</strong></p>
 * <pre>
 *   CREATED ─▶ PROCESSING ─▶ SUCCEEDED
 *                       ├────▶ FAILED
 *                       ├────▶ UNKNOWN
 *                       └────▶ REQUIRES_RECONCILIATION
 *
 *   UNKNOWN ─▶ SUCCEEDED   (resolved by external polling/reconciliation)
 *   UNKNOWN ─▶ FAILED
 *
 *   SUCCEEDED ─▶ REFUNDED   (Stage 4+)
 *   FAILED ─▶ (terminal, no outgoing)
 *   REQUIRES_RECONCILIATION ─▶ (resolved by Stage 5 reconciliation)
 * </pre>
 *
 * <p><strong>Why is {@code UNKNOWN} not terminal?</strong>
 * A provider timeout does not mean the payment failed — the provider may
 * have debited the customer but timed out before responding. Marking
 * {@code UNKNOWN} keeps the payment in a non-terminal state pending
 * reconciliation, preventing double-charging.</p>
 *
 * <p><strong>Design decision:</strong> transitions are validated in
 * {@link PaymentStateEngine}. The state machine lives in the domain
 * layer (not persistence) so it is fully unit-testable without Spring.</p>
 */
public enum PaymentStatus {

    /** Initial state — payment record created, not yet submitted to provider. */
    CREATED,

    /** Payment is being processed (submitted to provider, awaiting response). */
    PROCESSING,

    /** Payment completed synchronously and successfully. Terminal. */
    SUCCEEDED,

    /** Payment was declined by the provider (e.g. insufficient funds). Terminal. */
    FAILED,

    /** Provider returned an ambiguous result; outcome could be success or failure. Non-terminal. */
    UNKNOWN,

    /** Payment outcome requires reconciliation (timeout + bank discrepancy). Non-terminal. */
    REQUIRES_RECONCILIATION,

    /** Payment has been voided before capture. Terminal (Stage 4+). */
    VOIDED,

    /** Payment has been fully refunded. Terminal (Stage 4+). */
    REFUNDED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, VOIDED, REFUNDED -> true;
            default -> false;
        };
    }

    public boolean isAwaitingResolution() {
        return this == UNKNOWN || this == REQUIRES_RECONCILIATION;
    }
}
