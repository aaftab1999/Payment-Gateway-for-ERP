package com.paymentgateway.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.paymentgateway.settlement.config.PaymentGatewayProperties;

/**
 * Payment Gateway &amp; Settlement Core Engine.
 *
 * <p>Single Spring Boot monolith implementing the payment gateway and
 * settlement core engine, excluding the external ERP (invoice/customer UI).</p>
 *
 * <p>Run locally:</p>
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
 * </pre>
 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentGatewayProperties.class)
public class PaymentGatewaySettlementApplication {

    public static void main(final String[] args) {
        SpringApplication.run(PaymentGatewaySettlementApplication.class, args);
    }
}
