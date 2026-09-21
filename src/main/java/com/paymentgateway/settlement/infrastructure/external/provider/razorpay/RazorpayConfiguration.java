package com.paymentgateway.settlement.infrastructure.external.provider.razorpay;

import com.paymentgateway.settlement.config.RazorpayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Spring configuration for the Razorpay Orders API integration.
 *
 * <p>Only active when {@code app.razorpay.enabled=true}. When inactive, the
 * {@link com.paymentgateway.settlement.infrastructure.external.provider.SimulatedPaymentProcessor}
 * bean is used instead (it carries the complementary
 * {@code @ConditionalOnProperty} condition).</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.razorpay.enabled", havingValue = "true")
public class RazorpayConfiguration {

    @Bean
    public RestClient razorpayRestClient(final RazorpayProperties properties) {
        String credentials = properties.getKeyId() + ":" + properties.getKeySecret();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.US_ASCII));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Basic " + encoded)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
