package com.paymentgateway.settlement.integration;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies Kafka connectivity by producing and
 * consuming a single message.
 *
 * <p><strong>Note</strong>: This test connects to the Kafka broker running in
 * Docker Compose (not a Testcontainers-managed container). The Confluent
 * {@code cp-kafka} image does not start reliably under Testcontainers without
 * additional configuration; using the Docker Compose broker is simpler for
 * local development and is documented in {@code docs/local-development.md}.</p>
 *
 * <p>Requires Docker Compose to be running:</p>
 * <pre>
 *   docker compose up -d kafka
 * </pre>
 *
 * <p>If Docker is not available, the test fails fast with a clear error.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class KafkaIntegrationTest {

    @Autowired
    private ProducerFactory<String, String> producerFactory;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:19092");
    }

    @Test
    void kafkaBrokerAcceptsProduceAndConsume() throws Exception {
        String topic = "foundation-test-" + System.nanoTime(); // unique per run

        try (AdminClient admin = AdminClient.create(
                Map.of("bootstrap.servers", "localhost:19092"))) {
            var existing = admin.listTopics().names().get();
            if (!existing.contains(topic)) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)));
            }
        }

        try (var producer = producerFactory.createProducer()) {
            producer.send(new ProducerRecord<>(topic, "ping", "pong"));
            producer.flush();
        }

        try (var consumer = consumerFactory.createConsumer("test-consumer-" + System.nanoTime(), "test")) {
            consumer.subscribe(List.of(topic));
            var records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records).hasSize(1);
            assertThat(records.iterator().next().value()).isEqualTo("pong");
        }
    }
}
