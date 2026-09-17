package com.paymentgateway.settlement.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.domain.event.PaymentEventType;
import com.paymentgateway.settlement.domain.event.PaymentLifecycleEvent;
import com.paymentgateway.settlement.domain.payment.Payment;
import com.paymentgateway.settlement.domain.payment.PaymentStatus;
import com.paymentgateway.settlement.infrastructure.messaging.OutboxMetrics;
import com.paymentgateway.settlement.infrastructure.persistence.entity.OutboxEventEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.OutboxEventRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class OutboxEventService {

    public static final String PAYMENT_AGGREGATE_TYPE = "PAYMENT";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final OutboxMetrics metrics;

    public OutboxEventService(final OutboxEventRepository repository,
                              final ObjectMapper objectMapper,
                              final OutboxMetrics metrics) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional
    public OutboxEventEntity append(final Payment payment,
                                    final PaymentEventType eventType,
                                    final PaymentStatus previousStatus,
                                    final String reasonCode,
                                    final UUID causationId,
                                    final Integer retryAttempt,
                                    final Instant nextAttemptAt) {
        Instant occurredAt = Instant.now();
        long eventOrder = repository.nextEventOrder();
        PaymentLifecycleEvent event = new PaymentLifecycleEvent(
                UUID.randomUUID(),
                eventType.wireValue(),
                eventType.version(),
                eventOrder,
                payment.getPaymentId().toUuid(),
                payment.getMerchantId(),
                payment.getBillRef(),
                payment.getAmount().toMinorUnits(),
                payment.getAmount().getCurrency().name(),
                payment.getStatus(),
                previousStatus,
                payment.getProviderReference(),
                payment.getFailureCode(),
                occurredAt,
                nextAttemptAt,
                retryAttempt,
                payment.getCorrelationId(),
                causationId,
                reasonCode
        );

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize payment event", e);
        }

        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                PAYMENT_AGGREGATE_TYPE,
                event.paymentId(),
                event.eventType(),
                event.eventVersion(),
                eventOrder,
                payload,
                event.paymentId().toString(),
                occurredAt,
                occurredAt,
                occurredAt
        );
        OutboxEventEntity saved = repository.saveAndFlush(entity);
        metrics.recordCreated();
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findFirstEventId(final UUID paymentId, final PaymentEventType eventType) {
        return repository.findFirstByAggregateIdAndEventTypeOrderByEventOrderAsc(
                        paymentId, eventType.wireValue())
                .map(OutboxEventEntity::getEventId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findLatestEventId(final UUID paymentId) {
        return repository.findFirstByAggregateIdOrderByEventOrderDesc(paymentId)
                .map(OutboxEventEntity::getEventId);
    }
}
