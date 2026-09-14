package com.paymentgateway.settlement.application.port;

import com.paymentgateway.settlement.domain.idempotency.IdempotencyKey;

import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency service port.
 *
 * <p><strong>Responsibility:</strong> Owns the idempotency contract for
 * payment creation. It guarantees that a given (merchantId, idempotencyKey)
 * pair maps to at most one payment, even under concurrent requests across
 * multiple application instances.</p>
 *
 * <p><strong>Authoritative store:</strong> PostgreSQL. The store enforces
 * uniqueness at the database level via a unique constraint on
 * {@code (merchant_id, idempotency_key)}. In-memory caches (e.g. Redis)
 * are an optional fast-path and must never be the source of truth.</p>
 */
public interface IdempotencyService {

    /**
     * Attempts to reserve the idempotency key for a new payment.
     *
     * <p><strong>Outcome A — first request:</strong> The key is not present.
     * The store persists the provisional reservation and returns
     * {@link IdempotencyOutcome.Proceed} so the caller can create the
     * payment and later finalize the idempotency record.</p>
     *
     * <p><strong>Outcome B — same key, same request:</strong> A record
     * already exists. The store returns
     * {@link IdempotencyOutcome.ReplayOutcome} with the cached response so
     * the caller can return the original result without re-contacting the
     * provider.</p>
     *
     * <p><strong>Outcome C — same key, different request:</strong>
     * The store returns {@link IdempotencyOutcome.ConflictOutcome} so the
     * caller can reject with a 409 CONFLICT.</p>
     *
     * @param merchantId         tenant scope
     * @param key                client-supplied idempotency key
     * @param requestFingerprint SHA-256 of the canonical request payload
     * @param paymentId          UUID the caller intends to assign to the payment
     * @return the outcome describing what the caller should do next
     */
    IdempotencyOutcome reserve(String merchantId, IdempotencyKey key,
                               String requestFingerprint, UUID paymentId);

    /**
     * Finalizes the idempotency record after the payment has been processed.
     *
     * <p>Must be called inside the same transaction that persists the payment
     * so the idempotency record and the payment are atomic.</p>
     *
     * @param merchantId        tenant scope
     * @param key               client-supplied idempotency key
     * @param requestFingerprint SHA-256 of the canonical request payload
     * @param paymentId         the persisted payment UUID
     * @param responseStatus    HTTP status to cache for replay
     * @param responsePayload   JSON payload to cache for replay
     * @param isTerminal        true when the payment reached a terminal state
     */
    void finalize(String merchantId, IdempotencyKey key, String requestFingerprint,
                  UUID paymentId, int responseStatus, String responsePayload, boolean isTerminal);

    /**
     * Replays a previously stored response for a repeated request.
     *
     * @param merchantId tenant scope
     * @param key        client-supplied idempotency key
     * @return the stored replay, or empty if no record exists
     */
    Optional<IdempotencyOutcome.ReplayOutcome> replay(String merchantId, IdempotencyKey key);
}
