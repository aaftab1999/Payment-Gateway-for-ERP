package com.paymentgateway.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the Payment Gateway &amp; Settlement Core Engine.
 *
 * <p>All values can be overridden via environment variables following
 * Spring Boot relaxed binding rules, e.g.
 * {@code APP_CORRELATION_ID_HEADER_NAME}.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app")
@Validated
public class PaymentGatewayProperties {

    private CorrelationId correlationId = new CorrelationId("X-Correlation-Id");

    public CorrelationId getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(final CorrelationId correlationId) {
        this.correlationId = correlationId;
    }

    public static class CorrelationId {
        private String headerName;

        public CorrelationId() {
        }

        public CorrelationId(final String headerName) {
            this.headerName = headerName;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(final String headerName) {
            this.headerName = headerName;
        }
    }
}
