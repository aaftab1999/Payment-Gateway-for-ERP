package com.paymentgateway.settlement.infrastructure.external.provider;

import com.paymentgateway.settlement.application.port.PaymentProcessor;
import com.paymentgateway.settlement.domain.payment.PaymentMethodType;
import com.paymentgateway.settlement.domain.payment.ProviderResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated payment processor.
 *
 * <p><strong>Design:</strong> This is a single, in-process simulator
 * that handles all payment methods (UPI, CARD, NET_BANKING) with
 * deterministic outcomes based on the token prefix.</p>
 *
 * <p><strong>Provider idempotency:</strong> When a non-blank
 * {@code providerIdempotencyKey} is supplied, the processor records the
 * first result for that key and replays it for any subsequent call with
 * the same key. This mirrors the contract of real providers (e.g. Stripe's
 * {@code idempotency-key} header) and is what makes retries safe: a
 * timeout followed by a retry returns the same logical result rather than
 * charging twice.</p>
 *
 * <p><strong>Test token convention:</strong> The {@code paymentToken}
 * field encodes the desired simulation scenario:</p>
 * <table>
 *   <tr><th>Token prefix</th><th>Result</th><th>Provider ref generated</th></tr>
 *   <tr><td>{@code success:</td><td>ProviderResult.success()</td><td>provider_txn_{correlationId}</td></tr>
 *   <tr><td>{@code decline:</td><td>ProviderResult.declined()}</td><td>null}</td></tr>
 *   <tr><td>{@code timeout:</td><td>ProviderResult.unknown()}</td><td>null}</td></tr>
 *   <tr><td>{@code error500:</td><td>ProviderResult.technicalFailure()}</td><td>null}</td></tr>
 *   <tr><td>{@code connfail:</td><td>ProviderResult.technicalFailure()}</td><td>null}</td></tr>
 *   <tr><td>{@code unknown:</td><td>ProviderResult.unknown()}</td><td>null}</td></tr>
 *   <tr><td><em>anything else</em></td><td>ProviderResult.success()}</td><td>provider_txn_{correlationId}</td></tr>
 * </table>
 *
 * <p><strong>Why deterministic, not random:</strong> Payment infrastructure
 * must be testable. Random outcomes make it impossible to write deterministic
 * integration tests or to reproduce issues in production. The simulation
 * token gives the caller (or the test suite) explicit control over the
 * outcome.</p>
 */
@Component
public class SimulatedPaymentProcessor implements PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProcessor.class);

    /** Maps providerIdempotencyKey -> first result for that attempt. */
    private final Map<String, ProviderResult> idempotencyCache = new ConcurrentHashMap<>();

    @Override
    public ProviderResult process(
            final String paymentToken,
            final long amountMinor,
            final String currency,
            final UUID correlationId,
            final String providerIdempotencyKey) {

        log.debug("Processing simulated payment: token={}, amountMinor={}, currency={}, " +
                        "correlationId={}, providerIdempotencyKey={}",
                maskToken(paymentToken), amountMinor, currency, correlationId,
                maskKey(providerIdempotencyKey));

        // Provider idempotency: replay the first result for a repeated key.
        if (providerIdempotencyKey != null && !providerIdempotencyKey.isBlank()) {
            ProviderResult cached = idempotencyCache.get(providerIdempotencyKey);
            if (cached != null) {
                log.debug("Simulated provider: idempotent replay for key={}", maskKey(providerIdempotencyKey));
                return cached;
            }
        }

        String scenario = extractScenario(paymentToken);
        ProviderResult result = switch (scenario) {
            case "decline" -> {
                log.debug("Simulated provider: DECLINED");
                yield ProviderResult.declined("DECLINED", "Simulator: declined by provider");
            }
            case "timeout" -> {
                log.debug("Simulated provider: UNKNOWN (timeout)");
                yield ProviderResult.unknown("TIMEOUT", "Simulator: provider timeout");
            }
            case "error500" -> {
                log.debug("Simulated provider: TECHNICAL_FAILURE (HTTP 500)");
                yield ProviderResult.technicalFailure("PROVIDER_ERROR", "Simulator: HTTP 500 Internal Server Error");
            }
            case "connfail" -> {
                log.debug("Simulated provider: TECHNICAL_FAILURE (connection)");
                yield ProviderResult.technicalFailure("CONNECTION_ERROR", "Simulator: connection refused");
            }
            case "unknown" -> {
                log.debug("Simulated provider: UNKNOWN outcome");
                yield ProviderResult.unknown("UNKNOWN_OUTCOME", "Simulator: ambiguous provider response");
            }
            default -> {
                log.debug("Simulated provider: SUCCESS");
                yield ProviderResult.success("provider_txn_" + correlationId);
            }
        };

        if (providerIdempotencyKey != null && !providerIdempotencyKey.isBlank()) {
            // Only cache the first result. If a retry later observes a different
            // cached result it is a bug in the caller (the key must be stable).
            idempotencyCache.putIfAbsent(providerIdempotencyKey, result);
        }

        return result;
    }

    /** Exposes the cached result for a provider idempotency key (test hook). */
    public ProviderResult getCached(final String providerIdempotencyKey) {
        if (providerIdempotencyKey == null) return null;
        return idempotencyCache.get(providerIdempotencyKey);
    }

    /** Clears the cache (test hook). */
    public void clearCache() {
        idempotencyCache.clear();
    }

    /** Extracts the scenario prefix from the token (before the first colon). */
    private static String extractScenario(final String token) {
        if (token == null || token.isBlank()) return "";
        int idx = token.indexOf(':');
        return idx > 0 ? token.substring(0, idx) : "";
    }

    /** Masks a token for logging — only shows the scenario prefix. */
    private static String maskToken(final String token) {
        if (token == null || token.isBlank()) return "***";
        int idx = token.indexOf(':');
        return idx > 0 ? token.substring(0, idx) + ":*****" : "***";
    }

    /** Masks a provider idempotency key for logging — shows first 6 chars only. */
    private static String maskKey(final String key) {
        if (key == null || key.isBlank()) return "***";
        return key.length() <= 8 ? "***" : key.substring(0, 6) + "*****";
    }
}