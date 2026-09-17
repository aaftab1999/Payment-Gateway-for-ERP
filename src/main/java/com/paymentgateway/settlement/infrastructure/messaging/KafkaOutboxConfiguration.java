package com.paymentgateway.settlement.infrastructure.messaging;

import com.paymentgateway.settlement.config.OutboxProperties;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaOutboxConfiguration {

    private final OutboxProperties properties;
    private final String bootstrapServers;

    public KafkaOutboxConfiguration(final OutboxProperties properties,
                                    @Value("${spring.kafka.bootstrap-servers:localhost:19092}")
                                    final String bootstrapServers) {
        this.properties = properties;
        this.bootstrapServers = bootstrapServers;
    }

    @Bean(name = "paymentKafkaAdmin")
    public KafkaAdmin paymentKafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaAdmin admin = new KafkaAdmin(configs);
        admin.setFatalIfBrokerNotAvailable(false);
        admin.setOperationTimeout(1);
        return admin;
    }

    @Bean
    public NewTopic paymentEventsTopic() {
        return new NewTopic(properties.getKafka().getTopic(), 6, (short) 1)
                .configs(Map.of(
                        "cleanup.policy", "delete",
                        "retention.ms", Long.toString(7L * 24L * 60L * 60L * 1000L)
                ));
    }

    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return new NewTopic(properties.getKafka().getDeadLetterTopic(), 1, (short) 1)
                .configs(Map.of("cleanup.policy", "delete"));
    }

    @Bean(name = "paymentOutboxProducerFactory")
    public ProducerFactory<String, String> paymentOutboxProducerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        configs.put(ProducerConfig.ACKS_CONFIG, "all");
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configs.put(ProducerConfig.RETRIES_CONFIG, 3);
        configs.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        return new DefaultKafkaProducerFactory<>(configs);
    }

    @Bean(name = "paymentOutboxKafkaTemplate")
    public KafkaTemplate<String, String> paymentOutboxKafkaTemplate(
            final ProducerFactory<String, String> paymentOutboxProducerFactory) {
        return new KafkaTemplate<>(paymentOutboxProducerFactory);
    }

    @Bean(name = "erpPaymentEventConsumerFactory")
    public org.springframework.kafka.core.ConsumerFactory<String, String> erpPaymentEventConsumerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKafka().getConsumerGroup());
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configs.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new DefaultKafkaConsumerFactory<>(configs);
    }
}
