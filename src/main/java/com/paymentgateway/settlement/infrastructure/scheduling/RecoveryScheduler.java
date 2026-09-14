package com.paymentgateway.settlement.infrastructure.scheduling;

import com.paymentgateway.settlement.application.service.PaymentRecoveryService;
import com.paymentgateway.settlement.config.RecoveryProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Scheduled recovery job for payments stuck in non-terminal states.
 *
 * <p><strong>Why a scheduled job:</strong> Recovery must run after
 * application restart. A scheduled job with a fixed interval is the
 * simplest way to guarantee periodic sweeps without external
 * orchestration. The interval is configurable via
 * {@code app.recovery.sweep-interval-ms}.</p>
 *
 * <p><strong>Concurrency safety:</strong> The sweep runs in a single
 * thread. Each payment is locked via {@code SELECT FOR UPDATE} during
 * recovery, so two workers cannot process the same payment
 * simultaneously. The {@code fixedDelay} with {@code initialDelay}
 * ensures the previous sweep completes before the next starts.</p>
 *
 * <p><strong>Observability:</strong> Each sweep logs the number of
 * payments processed and the recovery metrics are exposed via
 * Micrometer (see {@code RecoveryMetrics}).</p>
 */
@Component
public class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final PaymentRecoveryService recoveryService;
    private final RecoveryProperties properties;

    public RecoveryScheduler(final PaymentRecoveryService recoveryService,
                             final RecoveryProperties properties) {
        this.recoveryService = recoveryService;
        this.properties = properties;
    }

    /**
     * Runs the recovery sweep on a fixed interval.
     *
     * <p>The initial delay gives the application time to start and the
     * database connection to stabilize. The fixed delay ensures the
     * previous sweep completes before the next starts.</p>
     */
    @Scheduled(fixedDelay = 60000L, initialDelay = 30000L)
    public void runRecoverySweep() {
        if (!properties.isEnabled()) {
            return;
        }

        Instant now = Instant.now();
        log.info("Starting recovery sweep: now={}", now);

        try {
            int processed = recoveryService.sweep(now);
            log.info("Recovery sweep complete: processed={}", processed);
        } catch (Exception e) {
            log.error("Recovery sweep failed: {}", e.getMessage(), e);
        }
    }
}
