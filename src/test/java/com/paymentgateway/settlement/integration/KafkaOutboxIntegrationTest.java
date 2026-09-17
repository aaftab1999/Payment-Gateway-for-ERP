package com.paymentgateway.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.application.service.OutboxEventService;
import com.paymentgateway.settlement.domain.event.PaymentEventType;
import com.paymentgateway.settlement.domain.event.PaymentLifecycleEvent;
import com.paymentgateway.settlement.domain.payment.Money;
import com.paymentgateway.settlement.domain.payment.Payment;
import com.paymentgateway.settlement.domain.payment.PaymentId;
import com.paymentgateway.settlement.domain.payment.PaymentMethodType;
import com.paymentgateway.settlement.domain.payment.PaymentStatus;
import com.paymentgateway.settlement.domain.payment.ProviderResult;
import com.paymentgateway.settlement.infrastructure.messaging.ErpConsumerMetrics;
import com.paymentgateway.settlement.infrastructure.messaging.OutboxPublicationStatus;
import com.paymentgateway.settlement.infrastructure.messaging.OutboxPublisher;
import com.paymentgateway.settlement.infrastructure.persistence.entity.OutboxEventEntity;
import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.OutboxEventRepository;
import com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository;
import com.paymentgateway.settlement.integration.fixture.ErpPaymentEventFixture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Testcontainers
@SpringBootTest(properties = {
        "app.outbox.enabled=true",
        "app.outbox.polling-interval=1h",
        "app.recovery.enabled=false",
        "app.outbox.send-timeout=2s"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(
            org.testcontainers.utility.DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
            .withKraft()
            .withStartupTimeout(Duration.ofSeconds(90));

    static {
        POSTGRES.start();
        KAFKA.start();
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
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ErpPaymentEventFixture erpFixture;

    @AfterAll
    void closeKafkaProducer() {
        kafkaTemplate.destroy();
    }

    @BeforeEach
    void clearData() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox, idempotency, payment RESTART IDENTITY CASCADE");
    }

    @Test
    void publishesStableEnvelopeAndMarksRowPublishedAfterAcknowledgement() throws Exception {
        Payment payment = createPayment();
        payment.markProcessing();
        payment.applyProviderResult(ProviderResult.success("prov-kafka"));
        OutboxEventEntity row = append(payment, PaymentEventType.PAYMENT_SUCCEEDED,
                PaymentStatus.PROCESSING, "PROVIDER_SUCCESS");

        publisher.publishDueEvents();

        ConsumerRecord<String, String> record = consumeOne("payment.events",
                "test-success-" + UUID.randomUUID(), payment.getPaymentId().toUuid().toString());
        assertThat(record.key()).isEqualTo(payment.getPaymentId().toUuid().toString());
        assertThat(objectMapper.readTree(record.value()))
                .isEqualTo(objectMapper.readTree(row.getEventPayload()));
        PaymentLifecycleEvent event = objectMapper.readValue(record.value(), PaymentLifecycleEvent.class);
        assertThat(event.eventId()).isEqualTo(row.getEventId());
        assertThat(event.eventType()).isEqualTo(PaymentEventType.PAYMENT_SUCCEEDED.wireValue());
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.paymentId()).isEqualTo(payment.getPaymentId().toUuid());
        assertThat(event.erpReference()).isEqualTo("INV-KAFKA-1");
        assertThat(event.correlationId()).isEqualTo(payment.getCorrelationId());
        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxPublicationStatus.PUBLISHED);
    }

    @Test
    void twoPublisherWorkersDoNotConcurrentlyPublishOneRow() throws Exception {
        Payment payment = createPayment();
        OutboxEventEntity row = append(payment, PaymentEventType.PAYMENT_PROCESSING_STARTED,
                PaymentStatus.CREATED, "SUBMITTED_TO_PROVIDER");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Void>> tasks = List.of(
                    () -> {
                        publisher.publishDueEvents();
                        return null;
                    },
                    () -> {
                        publisher.publishDueEvents();
                        return null;
                    }
            );
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        ConsumerRecord<String, String> record = consumeOne("payment.events",
                "test-concurrency-" + UUID.randomUUID(), payment.getPaymentId().toUuid().toString());
        assertThat(record.key()).isEqualTo(payment.getPaymentId().toUuid().toString());
        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxPublicationStatus.PUBLISHED);
        assertThat(countRecords("payment.events", "test-concurrency-count-" + UUID.randomUUID(),
                Duration.ofSeconds(2), payment.getPaymentId().toUuid().toString()))
                .isEqualTo(1);
    }

    @Test
    void preservesEventOrderForOnePayment() throws Exception {
        Payment payment = createPayment();
        payment.markProcessing();
        OutboxEventEntity processing = append(payment, PaymentEventType.PAYMENT_PROCESSING_STARTED,
                PaymentStatus.CREATED, "SUBMITTED_TO_PROVIDER");
        payment.applyProviderResult(ProviderResult.success("prov-order"));
        OutboxEventEntity succeeded = append(payment, PaymentEventType.PAYMENT_SUCCEEDED,
                PaymentStatus.PROCESSING, "PROVIDER_SUCCESS");

        publisher.publishDueEvents();
        publisher.publishDueEvents();

        List<ConsumerRecord<String, String>> records = countRecords(
                "payment.events", "test-order-" + UUID.randomUUID(),
                Duration.ofSeconds(10), payment.getPaymentId().toUuid().toString(), false);
        assertThat(records).hasSize(2);
        PaymentLifecycleEvent first = objectMapper.readValue(records.get(0).value(), PaymentLifecycleEvent.class);
        PaymentLifecycleEvent second = objectMapper.readValue(records.get(1).value(), PaymentLifecycleEvent.class);
        assertThat(first.eventOrder()).isLessThan(second.eventOrder());
        assertThat(first.eventType()).isEqualTo(PaymentEventType.PAYMENT_PROCESSING_STARTED.wireValue());
        assertThat(second.eventType()).isEqualTo(PaymentEventType.PAYMENT_SUCCEEDED.wireValue());
    }

    @Test
    void erpFixtureConsumesCorrelatedEventsAndIgnoresDuplicates() throws Exception {
        Payment payment = createPayment();
        payment.markProcessing();
        payment.applyProviderResult(ProviderResult.success("prov-kafka"));
        OutboxEventEntity row = append(payment, PaymentEventType.PAYMENT_SUCCEEDED,
                PaymentStatus.PROCESSING, "PROVIDER_SUCCESS");
        publisher.publishDueEvents();
        ConsumerRecord<String, String> record = consumeOne("payment.events",
                "test-erp-" + UUID.randomUUID(), payment.getPaymentId().toUuid().toString());

        assertThat(erpFixture.process(record.value())).isTrue();
        assertThat(erpFixture.process(record.value())).isFalse();
        assertThat(erpFixture.invoiceState("INV-KAFKA-1"))
                .isEqualTo(ErpPaymentEventFixture.ErpInvoiceState.PAID);
        assertThat(erpFixture.businessEffectCount()).isEqualTo(1);
        assertThat(erpFixture.processedEventCount()).isEqualTo(1);
    }

    @Test
    void erpFixtureHandlesUnknownAndRetryExhaustedExplicitly() throws Exception {
        Payment unknown = createPayment("INV-KAFKA-2");
        unknown.markProcessing();
        unknown.applyProviderResult(ProviderResult.unknown("UNKNOWN", "ambiguous"));
        OutboxEventEntity unknownRow = append(unknown, PaymentEventType.PAYMENT_UNKNOWN,
                PaymentStatus.PROCESSING, "PROVIDER_UNKNOWN_OUTCOME");

        Payment exhausted = createPayment("INV-KAFKA-3");
        exhausted.markRetryExhausted("Retry budget exhausted");
        OutboxEventEntity exhaustedRow = append(exhausted, PaymentEventType.PAYMENT_RETRY_EXHAUSTED,
                null, "RETRY_EXHAUSTED");

        publisher.publishDueEvents();
        ConsumerRecord<String, String> unknownRecord = consumeOne("payment.events",
                "test-unknown-" + UUID.randomUUID(), unknown.getPaymentId().toUuid().toString());
        ConsumerRecord<String, String> exhaustedRecord = consumeOne("payment.events",
                "test-exhausted-" + UUID.randomUUID(), exhausted.getPaymentId().toUuid().toString());

        erpFixture.process(unknownRecord.value());
        erpFixture.process(exhaustedRecord.value());
        assertThat(erpFixture.invoiceState("INV-KAFKA-2"))
                .isEqualTo(ErpPaymentEventFixture.ErpInvoiceState.REVIEW_REQUIRED);
        assertThat(erpFixture.invoiceState("INV-KAFKA-3"))
                .isEqualTo(ErpPaymentEventFixture.ErpInvoiceState.PAYMENT_FAILED);
        assertThat(unknownRow.getEventPayload()).contains(unknown.getPaymentId().toUuid().toString());
        assertThat(exhaustedRow.getEventPayload()).contains(exhausted.getPaymentId().toUuid().toString());
    }

    private Payment createPayment() {
        return createPayment("INV-KAFKA-1");
    }

    private Payment createPayment(final String billRef) {
        Payment payment = Payment.create(
                PaymentId.generate(), "merchant-kafka", "customer-kafka", billRef,
                Money.of("10.00", com.paymentgateway.settlement.domain.payment.Currency.USD),
                PaymentMethodType.UPI, "tok_kafka", UUID.randomUUID());
        paymentRepository.saveAndFlush(PaymentEntity.fromDomain(payment));
        return payment;
    }

    private OutboxEventEntity append(final Payment payment,
                                     final PaymentEventType eventType,
                                     final PaymentStatus previousStatus,
                                     final String reasonCode) {
        return transactionTemplate.execute(status -> outboxEventService.append(
                payment, eventType, previousStatus, reasonCode, null, null, null));
    }

    private ConsumerRecord<String, String> consumeOne(final String topic,
                                                       final String groupId,
                                                       final String expectedKey) {
        List<ConsumerRecord<String, String>> records = countRecords(
                topic, groupId, Duration.ofSeconds(10), expectedKey, true);
        return records.isEmpty() ? null : records.get(0);
    }

    private int countRecords(final String topic,
                             final String groupId,
                             final Duration timeout,
                             final String expectedKey) {
        return countRecords(topic, groupId, timeout, expectedKey, false).size();
    }

    private List<ConsumerRecord<String, String>> countRecords(final String topic,
                                                               final String groupId,
                                                               final Duration timeout,
                                                               final String expectedKey,
                                                               final boolean returnFirst) {
        Map<String, Object> configs = new HashMap<>(consumerFactory.getConfigurationProperties());
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(configs)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                if (!records.isEmpty()) {
                    java.util.ArrayList<ConsumerRecord<String, String>> matching = new java.util.ArrayList<>();
                    records.forEach(record -> {
                        if (expectedKey.equals(record.key())) {
                            matching.add(record);
                        }
                    });
                    if (!matching.isEmpty()) {
                        return returnFirst ? matching.subList(0, 1) : matching;
                    }
                }
            }
        }
        return List.of();
    }
}
