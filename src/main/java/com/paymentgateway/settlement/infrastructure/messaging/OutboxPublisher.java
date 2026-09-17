package com.paymentgateway.settlement.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.config.OutboxProperties;
import com.paymentgateway.settlement.domain.event.PaymentLifecycleEvent;
import com.paymentgateway.settlement.infrastructure.persistence.entity.OutboxEventEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.OutboxEventRepository;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.outbox.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final ObjectMapper objectMapper;
    private final OutboxMetrics metrics;
    private volatile boolean running = true;

    public OutboxPublisher(final OutboxEventRepository repository,
                           final KafkaTemplate<String, String> kafkaTemplate,
                           final OutboxProperties properties,
                           final ObjectMapper objectMapper,
                           final OutboxMetrics metrics) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.outbox.polling-interval:1s}")
    @Transactional
    public void publishDueEvents() {
        if (!running || !properties.isEnabled()) {
            return;
        }

        Instant now = Instant.now();
        List<OutboxEventEntity> due = repository.findDue(
                now,
                PageRequest.of(0, Math.max(1, properties.getBatchSize()))
        );
        metrics.recordPoll(due.size(), repository.countByStatus(OutboxPublicationStatus.PENDING));
        log.debug("Outbox poll: due={}", due.size());
        for (OutboxEventEntity event : due) {
            publishOne(event, now);
        }
    }

    private void publishOne(final OutboxEventEntity event, final Instant now) {
        String owner = UUID.randomUUID().toString();
        Instant lockedUntil = now.plus(properties.getLeaseDuration());
        if (repository.claim(event.getId(), owner, now, lockedUntil) != 1) {
            return;
        }

        try {
            objectMapper.readValue(
                    event.getEventPayload(), PaymentLifecycleEvent.class);
            kafkaTemplate.send(
                            properties.getKafka().getTopic(),
                            event.getEventKey(),
                            event.getEventPayload())
                    .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            repository.markPublished(event.getId(), owner, Instant.now());
            metrics.recordPublished();
            log.info("Outbox event published: eventId={}, paymentId={}, eventType={}, key={}",
                    event.getEventId(), event.getAggregateId(), event.getEventType(), event.getEventKey());
        } catch (Exception e) {
            metrics.recordPublicationFailure();
            metrics.recordKafkaFailure();
            log.error("Outbox publication failed: eventId={}, paymentId={}, eventType={}, error={}",
                    event.getEventId(), event.getAggregateId(), event.getEventType(), e.getMessage());
            handleFailure(event, owner, now, e);
        }
    }

    private void handleFailure(final OutboxEventEntity event,
                               final String owner,
                               final Instant now,
                               final Exception exception) {
        int attemptCount = event.getAttemptCount() + 1;
        if (attemptCount >= properties.getMaxAttempts()) {
            try {
                kafkaTemplate.send(
                                properties.getKafka().getDeadLetterTopic(),
                                event.getEventKey(),
                                event.getEventPayload())
                        .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                repository.markDeadLettered(
                        event.getId(), owner, Instant.now(), truncate(exception.getMessage()));
                metrics.recordDeadLetter();
                log.error("Outbox event dead-lettered: eventId={}, paymentId={}, eventType={}",
                        event.getEventId(), event.getAggregateId(), event.getEventType());
            } catch (Exception deadLetterException) {
                repository.markRetryOrFailed(
                        event.getId(), owner, attemptCount, attemptCount,
                        null, truncate(deadLetterException.getMessage()));
                log.error("Outbox event and dead-letter publish failed: eventId={}, paymentId={}, error={}",
                        event.getEventId(), event.getAggregateId(), deadLetterException.getMessage());
            }
            return;
        }

        Duration backoff = boundedBackoff(attemptCount);
        Instant nextAttemptAt = now.plus(backoff);
        repository.markRetryOrFailed(
                event.getId(), owner, attemptCount, properties.getMaxAttempts(),
                nextAttemptAt, truncate(exception.getMessage()));
        metrics.recordRetryScheduled();
        log.warn("Outbox retry scheduled: eventId={}, paymentId={}, attempt={}, nextAttemptAt={}",
                event.getEventId(), event.getAggregateId(), attemptCount, nextAttemptAt);
    }

    private Duration boundedBackoff(final int attemptCount) {
        long baseMillis = Math.max(1L, properties.getRetryBaseBackoff().toMillis());
        long maxMillis = Math.max(baseMillis, properties.getRetryMaxBackoff().toMillis());
        int shift = Math.min(Math.max(0, attemptCount - 1), 62);
        long multiplier = 1L << shift;
        long backoffMillis = multiplier > maxMillis / baseMillis
                ? maxMillis
                : Math.min(maxMillis, baseMillis * multiplier);
        return Duration.ofMillis(backoffMillis);
    }

    private static String truncate(final String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 2048 ? message : message.substring(0, 2048);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        try {
            kafkaTemplate.flush();
        } catch (RuntimeException e) {
            log.warn("Kafka producer flush failed during shutdown: {}", e.getMessage());
        }
    }
}
