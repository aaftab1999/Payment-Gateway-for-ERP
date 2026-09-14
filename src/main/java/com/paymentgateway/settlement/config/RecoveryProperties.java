package com.paymentgateway.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the recovery service.
 *
 * <p><strong>Why these exist:</strong> Retry limits and timeout thresholds
 * must be configurable per environment (dev/test/prod). Hard-coded values
 * would make it impossible to tune behaviour without a code change and
 * redeploy.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.recovery")
public class RecoveryProperties {

    /** Maximum number of provider retry attempts per payment. */
    private int maxRetries = 3;

    /** Base backoff in milliseconds between retries (exponential). */
    private long baseBackoffMs = 1000L;

    /** Maximum backoff in milliseconds. */
    private long maxBackoffMs = 30000L;

    /** How long a payment can sit in CREATED before recovery considers it. */
    private long createdTimeoutMs = 60000L;

    /** How long a payment can sit in PROCESSING before recovery considers it. */
    private long processingTimeoutMs = 300000L;

    /** How long a payment can sit in UNKNOWN before recovery considers it. */
    private long unknownTimeoutMs = 900000L;

    /** How long a payment can sit in REQUIRES_RECONCILIATION before recovery considers it. */
    private long requiresReconciliationTimeoutMs = 1800000L;

    /** Whether the recovery job is enabled. */
    private boolean enabled = true;

    /** Interval between recovery sweeps (ms). */
    private long sweepIntervalMs = 60000L;

    /** Maximum number of payments to process per sweep. */
    private int maxPerSweep = 100;

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getBaseBackoffMs() { return baseBackoffMs; }
    public void setBaseBackoffMs(long baseBackoffMs) { this.baseBackoffMs = baseBackoffMs; }

    public long getMaxBackoffMs() { return maxBackoffMs; }
    public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }

    public long getCreatedTimeoutMs() { return createdTimeoutMs; }
    public void setCreatedTimeoutMs(long createdTimeoutMs) { this.createdTimeoutMs = createdTimeoutMs; }

    public long getProcessingTimeoutMs() { return processingTimeoutMs; }
    public void setProcessingTimeoutMs(long processingTimeoutMs) { this.processingTimeoutMs = processingTimeoutMs; }

    public long getUnknownTimeoutMs() { return unknownTimeoutMs; }
    public void setUnknownTimeoutMs(long unknownTimeoutMs) { this.unknownTimeoutMs = unknownTimeoutMs; }

    public long getRequiresReconciliationTimeoutMs() { return requiresReconciliationTimeoutMs; }
    public void setRequiresReconciliationTimeoutMs(long requiresReconciliationTimeoutMs) {
        this.requiresReconciliationTimeoutMs = requiresReconciliationTimeoutMs;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getSweepIntervalMs() { return sweepIntervalMs; }
    public void setSweepIntervalMs(long sweepIntervalMs) { this.sweepIntervalMs = sweepIntervalMs; }

    public int getMaxPerSweep() { return maxPerSweep; }
    public void setMaxPerSweep(int maxPerSweep) { this.maxPerSweep = maxPerSweep; }

    /**
     * Returns the timeout threshold for the given status.
     */
    public long timeoutFor(com.paymentgateway.settlement.domain.payment.PaymentStatus status) {
        return switch (status) {
            case CREATED -> getCreatedTimeoutMs();
            case PROCESSING -> getProcessingTimeoutMs();
            case UNKNOWN -> getUnknownTimeoutMs();
            case REQUIRES_RECONCILIATION -> getRequiresReconciliationTimeoutMs();
            default -> 0L;
        };
    }
}