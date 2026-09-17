package com.paymentgateway.settlement.infrastructure.persistence.repository;

import com.paymentgateway.settlement.domain.event.PaymentEventType;
import com.paymentgateway.settlement.infrastructure.persistence.entity.OutboxEventEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query(value = """
            SELECT o.*
            FROM outbox o
            WHERE o.status = 'PENDING'
              AND o.next_attempt_at <= :now
              AND NOT EXISTS (
                  SELECT 1
                  FROM outbox earlier
                  WHERE earlier.aggregate_id = o.aggregate_id
                    AND earlier.event_order < o.event_order
                    AND earlier.status = 'PENDING'
              )
            ORDER BY o.event_order ASC, o.created_at ASC, o.id ASC
            FOR UPDATE SKIP LOCKED
            """,
            countQuery = """
            SELECT count(*)
            FROM outbox o
            WHERE o.status = 'PENDING'
              AND o.next_attempt_at <= :now
              AND NOT EXISTS (
                  SELECT 1
                  FROM outbox earlier
                  WHERE earlier.aggregate_id = o.aggregate_id
                    AND earlier.event_order < o.event_order
                    AND earlier.status = 'PENDING'
              )
            """, nativeQuery = true)
    List<OutboxEventEntity> findDue(@Param("now") Instant now, Pageable pageable);

    @Query(value = "SELECT nextval('outbox_event_order_seq')", nativeQuery = true)
    long nextEventOrder();

    List<OutboxEventEntity> findByAggregateIdOrderByEventOrderAsc(UUID aggregateId);

    Optional<OutboxEventEntity> findFirstByAggregateIdAndEventTypeOrderByEventOrderAsc(
            UUID aggregateId, String eventType);

    Optional<OutboxEventEntity> findFirstByAggregateIdOrderByEventOrderDesc(UUID aggregateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox
               SET lock_owner = :owner,
                   locked_until = :lockedUntil
              WHERE id = :id
                AND (lock_owner IS NULL OR locked_until < :claimedAt)
            """, nativeQuery = true)
    int claim(@Param("id") UUID id,
              @Param("owner") String owner,
              @Param("claimedAt") Instant claimedAt,
              @Param("lockedUntil") Instant lockedUntil);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox
               SET status = 'PUBLISHED',
                   published_at = :publishedAt,
                   lock_owner = NULL,
                   locked_until = NULL,
                   last_error = NULL
             WHERE id = :id
               AND lock_owner = :owner
            """, nativeQuery = true)
    int markPublished(@Param("id") UUID id,
                      @Param("owner") String owner,
                      @Param("publishedAt") Instant publishedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox
               SET attempt_count = :attemptCount,
                   status = CASE WHEN :attemptCount >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                   next_attempt_at = :nextAttemptAt,
                   last_error = :lastError,
                   lock_owner = NULL,
                   locked_until = NULL
             WHERE id = :id
               AND lock_owner = :owner
            """, nativeQuery = true)
    int markRetryOrFailed(@Param("id") UUID id,
                          @Param("owner") String owner,
                          @Param("attemptCount") int attemptCount,
                          @Param("maxAttempts") int maxAttempts,
                          @Param("nextAttemptAt") Instant nextAttemptAt,
                          @Param("lastError") String lastError);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox
               SET status = 'DEAD_LETTERED',
                   published_at = :publishedAt,
                   last_error = :lastError,
                   lock_owner = NULL,
                   locked_until = NULL
             WHERE id = :id
               AND lock_owner = :owner
            """, nativeQuery = true)
    int markDeadLettered(@Param("id") UUID id,
                         @Param("owner") String owner,
                         @Param("publishedAt") Instant publishedAt,
                         @Param("lastError") String lastError);

    long countByStatus(com.paymentgateway.settlement.infrastructure.messaging.OutboxPublicationStatus status);
}
