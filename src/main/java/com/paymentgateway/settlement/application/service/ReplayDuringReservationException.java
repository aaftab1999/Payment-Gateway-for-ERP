package com.paymentgateway.settlement.application.service;

import com.paymentgateway.settlement.application.port.IdempotencyOutcome;

/**
 * Thrown when a replay is detected *during* the TX1 idempotency reservation
 * inside {@code chargeWithOutcome}. This covers the race window where a
 * concurrent identical request reserved the idempotency key first and
 * finalized the response before this request's reservation commit.
 *
 * <p>The payment row has already been persisted (in CREATED state) before the
 * reservation is attempted, so a replay here simply discards the in-flight
 * payment and returns the already-completed payment/response.</p>
 */
public class ReplayDuringReservationException extends RuntimeException {

    public final IdempotencyOutcome.ReplayOutcome outcome;

    public ReplayDuringReservationException(final IdempotencyOutcome.ReplayOutcome outcome) {
        super("Idempotency key replay detected during reservation: " + outcome.paymentId());
        this.outcome = outcome;
    }
}
