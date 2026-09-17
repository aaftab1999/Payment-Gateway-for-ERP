package com.paymentgateway.settlement.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxMetrics {

    private final Counter created;
    private final Counter polls;
    private final Counter published;
    private final Counter publicationFailures;
    private final Counter retriesScheduled;
    private final Counter deadLetter;
    private final Counter kafkaFailures;
    private final AtomicLong backlog = new AtomicLong();

    public OutboxMetrics(final MeterRegistry meterRegistry) {
        created = Counter.builder("payment.outbox.events.created")
                .description("Payment outbox events created")
                .tag("aggregate", "payment")
                .register(meterRegistry);
        polls = Counter.builder("payment.outbox.polls")
                .description("Outbox polling cycles")
                .register(meterRegistry);
        published = Counter.builder("payment.outbox.published")
                .description("Outbox events acknowledged by Kafka")
                .tag("aggregate", "payment")
                .register(meterRegistry);
        publicationFailures = Counter.builder("payment.outbox.publication.failures")
                .description("Outbox publication attempts that failed")
                .tag("aggregate", "payment")
                .register(meterRegistry);
        retriesScheduled = Counter.builder("payment.outbox.retries.scheduled")
                .description("Outbox retries scheduled")
                .tag("aggregate", "payment")
                .register(meterRegistry);
        deadLetter = Counter.builder("payment.outbox.dead_lettered")
                .description("Outbox events moved to the dead-letter topic")
                .tag("aggregate", "payment")
                .register(meterRegistry);
        kafkaFailures = Counter.builder("payment.kafka.connection.failures")
                .description("Kafka connection or acknowledgement failures")
                .register(meterRegistry);
        Gauge.builder("payment.outbox.backlog", backlog, AtomicLong::get)
                .description("Pending outbox events")
                .tag("aggregate", "payment")
                .register(meterRegistry);
    }

    public void recordCreated() {
        created.increment();
    }

    public void recordPoll(final int dueCount, final long pendingCount) {
        polls.increment();
        backlog.set(pendingCount);
    }

    public void recordPublished() {
        published.increment();
        backlog.decrementAndGet();
    }

    public void recordPublicationFailure() {
        publicationFailures.increment();
    }

    public void recordRetryScheduled() {
        retriesScheduled.increment();
    }

    public void recordDeadLetter() {
        deadLetter.increment();
    }

    public void recordKafkaFailure() {
        kafkaFailures.increment();
    }
}
