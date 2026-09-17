package com.paymentgateway.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.application.service.OutboxEventService;
import com.paymentgateway.settlement.domain.event.PaymentEventType;
import com.paymentgateway.settlement.domain.payment.Money;
import com.paymentgateway.settlement.domain.payment.Payment;
import com.paymentgateway.settlement.domain.payment.PaymentId;
import com.paymentgateway.settlement.domain.payment.PaymentMethodType;
import com.paymentgateway.settlement.infrastructure.messaging.OutboxPublicationStatus;
import com.paymentgateway.settlement.infrastructure.messaging.OutboxPublisher;
import com.paymentgateway.settlement.infrastructure.persistence.entity.OutboxEventEntity;
import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.OutboxEventRepository;
import com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.outbox.enabled=true",
        "app.outbox.polling-interval=1h",
        "app.recovery.enabled=false",
        "app.outbox.max-attempts=3",
        "app.outbox.retry-base-backoff=25ms",
        "app.outbox.retry-max-backoff=100ms",
        "spring.kafka.bootstrap-servers=localhost:1"
})
class OutboxPublisherRetryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void kafkaFailureLeavesEventRetryableWithBackoff() throws Exception {
        Payment payment = createPayment();
        OutboxEventEntity row = transactionTemplate.execute(status -> outboxEventService.append(
                payment, PaymentEventType.PAYMENT_CREATED, null,
                "PAYMENT_CREATED", null, null, null));
        String key = payment.getPaymentId().toUuid().toString();
        CompletableFuture<SendResult<String, String>> failed =
                CompletableFuture.failedFuture(new RuntimeException("connection refused"));
        when(kafkaTemplate.send(eq("payment.events"), eq(key), anyString())).thenReturn(failed);

        publisher.publishDueEvents();

        OutboxEventEntity stored = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxPublicationStatus.PENDING);
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        assertThat(Duration.between(stored.getCreatedAt(), stored.getNextAttemptAt()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(20));
        assertThat(stored.getLastError()).contains("connection refused");
        assertThat(stored.getLockOwner()).isNull();
    }

    private Payment createPayment() {
        Payment payment = Payment.create(
                PaymentId.generate(), "merchant-retry", "customer-retry", "INV-RETRY",
                Money.of("1.00", com.paymentgateway.settlement.domain.payment.Currency.USD),
                PaymentMethodType.UPI, "tok_retry", UUID.randomUUID());
        paymentRepository.saveAndFlush(PaymentEntity.fromDomain(payment));
        return payment;
    }
}
