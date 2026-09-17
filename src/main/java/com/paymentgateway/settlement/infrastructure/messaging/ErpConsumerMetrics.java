package com.paymentgateway.settlement.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ErpConsumerMetrics {

    private final Counter duplicates;
    private final Counter processingFailures;
    private final Counter outcomes;

    public ErpConsumerMetrics(final MeterRegistry meterRegistry) {
        duplicates = Counter.builder("payment.erp.events.duplicates")
                .description("Duplicate payment events ignored by the ERP fixture")
                .register(meterRegistry);
        processingFailures = Counter.builder("payment.erp.processing.failures")
                .description("Payment events that failed ERP-side processing")
                .register(meterRegistry);
        outcomes = Counter.builder("payment.erp.invoice.outcomes")
                .description("ERP invoice outcome updates")
                .register(meterRegistry);
    }

    public void recordDuplicate() {
        duplicates.increment();
    }

    public void recordFailure() {
        processingFailures.increment();
    }

    public void recordOutcome() {
        outcomes.increment();
    }
}
