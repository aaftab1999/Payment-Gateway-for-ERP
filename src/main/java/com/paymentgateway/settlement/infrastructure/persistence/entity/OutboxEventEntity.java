package com.paymentgateway.settlement.infrastructure.persistence.entity;

import com.paymentgateway.settlement.infrastructure.messaging.OutboxPublicationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
public class OutboxEventEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true, columnDefinition = "UUID")
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, columnDefinition = "UUID")
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_payload", nullable = false, columnDefinition = "JSONB")
    private String eventPayload;

    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;

    @Column(name = "event_order", nullable = false, updatable = false)
    private Long eventOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OutboxPublicationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant availableAt;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "published_at", columnDefinition = "TIMESTAMPTZ")
    private Instant publishedAt;

    @Column(name = "last_error", length = 2048)
    private String lastError;

    @Column(name = "lock_owner", length = 64)
    private String lockOwner;

    @Column(name = "locked_until", columnDefinition = "TIMESTAMPTZ")
    private Instant lockedUntil;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(final UUID id,
                             final UUID eventId,
                             final String aggregateType,
                             final UUID aggregateId,
                             final String eventType,
                             final int eventVersion,
                             final long eventOrder,
                             final String eventPayload,
                             final String eventKey,
                             final Instant availableAt,
                             final Instant nextAttemptAt,
                             final Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.eventOrder = eventOrder;
        this.eventPayload = eventPayload;
        this.eventKey = eventKey;
        this.status = OutboxPublicationStatus.PENDING;
        this.attemptCount = 0;
        this.availableAt = availableAt;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public String getEventPayload() {
        return eventPayload;
    }

    public String getEventKey() {
        return eventKey;
    }

    public Long getEventOrder() {
        return eventOrder;
    }

    public OutboxPublicationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLockOwner() {
        return lockOwner;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockOwner(final String lockOwner) {
        this.lockOwner = lockOwner;
    }

    public void setLockedUntil(final Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }
}
