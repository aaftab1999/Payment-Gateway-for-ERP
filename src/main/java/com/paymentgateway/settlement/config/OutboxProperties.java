package com.paymentgateway.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    private boolean enabled = true;
    private Duration pollingInterval = Duration.ofSeconds(1);
    private int batchSize = 50;
    private int maxAttempts = 10;
    private Duration retryBaseBackoff = Duration.ofSeconds(1);
    private Duration retryMaxBackoff = Duration.ofSeconds(30);
    private Duration sendTimeout = Duration.ofSeconds(10);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Kafka kafka = new Kafka();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(final Duration pollingInterval) {
        this.pollingInterval = pollingInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(final int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(final int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBaseBackoff() {
        return retryBaseBackoff;
    }

    public void setRetryBaseBackoff(final Duration retryBaseBackoff) {
        this.retryBaseBackoff = retryBaseBackoff;
    }

    public Duration getRetryMaxBackoff() {
        return retryMaxBackoff;
    }

    public void setRetryMaxBackoff(final Duration retryMaxBackoff) {
        this.retryMaxBackoff = retryMaxBackoff;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(final Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(final Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(final Kafka kafka) {
        this.kafka = kafka;
    }

    public static class Kafka {
        private String topic = "payment.events";
        private String deadLetterTopic = "payment.events.DLQ";
        private String consumerGroup = "external-erp-payment-events";

        public String getTopic() {
            return topic;
        }

        public void setTopic(final String topic) {
            this.topic = topic;
        }

        public String getDeadLetterTopic() {
            return deadLetterTopic;
        }

        public void setDeadLetterTopic(final String deadLetterTopic) {
            this.deadLetterTopic = deadLetterTopic;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(final String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }
    }
}
