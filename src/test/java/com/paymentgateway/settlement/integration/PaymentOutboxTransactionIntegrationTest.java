package com.paymentgateway.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.application.service.ChargeService;
import com.paymentgateway.settlement.application.service.OutboxEventService;
import com.paymentgateway.settlement.domain.event.PaymentEventType;
import com.paymentgateway.settlement.domain.event.PaymentLifecycleEvent;
import com.paymentgateway.settlement.domain.idempotency.IdempotencyKey;
import com.paymentgateway.settlement.domain.payment.Money;
import com.paymentgateway.settlement.domain.payment.Payment;
import com.paymentgateway.settlement.domain.payment.PaymentId;
import com.paymentgateway.settlement.domain.payment.PaymentMethodType;
import com.paymentgateway.settlement.domain.payment.PaymentStatus;
import com.paymentgateway.settlement.domain.payment.ProviderResult;
import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import com.paymentgateway.settlement.infrastructure.persistence.entity.OutboxEventEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.OutboxEventRepository;
import com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(properties = {
        "app.outbox.enabled=false",
        "app.recovery.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:1"
})
class PaymentOutboxTransactionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private ChargeService chargeService;

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
    private com.paymentgateway.settlement.application.port.PaymentProcessor processor;

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(processor);
    }

    @Test
    void paymentAndOutboxEventsCommitTogether() throws Exception {
        when(processor.process(anyString(), anyLong(), anyString(), any(UUID.class), anyString()))
                .thenReturn(ProviderResult.success("prov_safe_123"));

        Payment payment = chargeService.charge(
                "merchant-test", "customer-test", "INV-100",
                "12.50", "USD", "UPI", "tok_test",
                UUID.randomUUID(), "idempotency-commit");

        List<OutboxEventEntity> rows =
                outboxRepository.findByAggregateIdOrderByEventOrderAsc(payment.getPaymentId().toUuid());

        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(row -> row.getEventType()).toList())
                .containsExactly(
                        PaymentEventType.PAYMENT_CREATED.wireValue(),
                        PaymentEventType.PAYMENT_PROCESSING_STARTED.wireValue(),
                        PaymentEventType.PAYMENT_SUCCEEDED.wireValue());
        assertThat(rows.get(0).getEventOrder()).isLessThan(rows.get(1).getEventOrder());
        assertThat(rows.get(1).getEventOrder()).isLessThan(rows.get(2).getEventOrder());
        assertThat(rows.get(2).getEventPayload()).doesNotContain("tok_test", "customer-test");
        PaymentLifecycleEvent succeeded = objectMapper.readValue(
                rows.get(2).getEventPayload(), PaymentLifecycleEvent.class);
        assertThat(succeeded.eventId()).isEqualTo(rows.get(2).getEventId());
        assertThat(succeeded.paymentId()).isEqualTo(payment.getPaymentId().toUuid());
        assertThat(succeeded.erpReference()).isEqualTo("INV-100");
        assertThat(succeeded.providerReference()).isEqualTo("prov_safe_123");
        assertThat(succeeded.eventVersion()).isEqualTo(1);
        assertThat(succeeded.eventOrder()).isEqualTo(rows.get(2).getEventOrder());
    }

    @ParameterizedTest
    @MethodSource("providerResults")
    void supportedResultTransitionsCreateExpectedEvents(
            final ProviderResult result,
            final PaymentEventType expectedEventType) {
        when(processor.process(anyString(), anyLong(), anyString(), any(UUID.class), anyString()))
                .thenReturn(result);

        Payment payment = chargeService.charge(
                "merchant-test", "customer-test", "INV-101",
                "5.00", "USD", "UPI", "tok_test",
                UUID.randomUUID(), "idempotency-" + expectedEventType);

        List<OutboxEventEntity> rows =
                outboxRepository.findByAggregateIdOrderByEventOrderAsc(payment.getPaymentId().toUuid());
        assertThat(rows).extracting(row -> row.getEventType())
                .containsExactly(
                        PaymentEventType.PAYMENT_CREATED.wireValue(),
                        PaymentEventType.PAYMENT_PROCESSING_STARTED.wireValue(),
                        expectedEventType.wireValue());
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> providerResults() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        ProviderResult.success("prov-success"), PaymentEventType.PAYMENT_SUCCEEDED),
                org.junit.jupiter.params.provider.Arguments.of(
                        ProviderResult.declined("DECLINED", "declined"), PaymentEventType.PAYMENT_FAILED),
                org.junit.jupiter.params.provider.Arguments.of(
                        ProviderResult.unknown("UNKNOWN", "ambiguous"), PaymentEventType.PAYMENT_UNKNOWN)
        );
    }

    @Test
    void paymentRollbackAlsoRollsBackOutboxInsertion() {
        PaymentId paymentId = PaymentId.generate();
        Payment payment = Payment.create(
                paymentId, "merchant-test", "customer-test", "INV-102",
                Money.of("7.00", com.paymentgateway.settlement.domain.payment.Currency.USD),
                PaymentMethodType.UPI, "tok_test", UUID.randomUUID());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            paymentRepository.saveAndFlush(PaymentEntity.fromDomain(payment));
            outboxEventService.append(
                    payment, PaymentEventType.PAYMENT_CREATED, null,
                    "PAYMENT_CREATED", null, null, null);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");

        assertThat(paymentRepository.findByPaymentId(paymentId.toUuid())).isEmpty();
        assertThat(outboxRepository.findByAggregateIdOrderByEventOrderAsc(paymentId.toUuid())).isEmpty();
    }

    @Test
    void invalidTransitionDoesNotCreateAnotherEvent() {
        PaymentId paymentId = PaymentId.generate();
        Payment payment = Payment.create(
                paymentId, "merchant-test", "customer-test", "INV-103",
                Money.of("9.00", com.paymentgateway.settlement.domain.payment.Currency.USD),
                PaymentMethodType.UPI, "tok_test", UUID.randomUUID());

        transactionTemplate.executeWithoutResult(status -> {
            paymentRepository.saveAndFlush(PaymentEntity.fromDomain(payment));
            outboxEventService.append(
                    payment, PaymentEventType.PAYMENT_CREATED, null,
                    "PAYMENT_CREATED", null, null, null);
            payment.markProcessing();
            assertThatThrownBy(payment::markProcessing)
                    .isInstanceOf(com.paymentgateway.settlement.domain.payment.IllegalStateTransitionException.class);
        });

        List<OutboxEventEntity> rows =
                outboxRepository.findByAggregateIdOrderByEventOrderAsc(paymentId.toUuid());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType())
                .isEqualTo(PaymentEventType.PAYMENT_CREATED.wireValue());
    }
}
