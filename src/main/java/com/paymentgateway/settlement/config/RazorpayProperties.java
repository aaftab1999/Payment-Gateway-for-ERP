package com.paymentgateway.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Razorpay payment provider integration.
 *
 * <p>All values can be overridden via environment variables following
 * Spring Boot relaxed binding rules, e.g.
 * {@code APP_RAZORPAY_KEY_ID}.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.razorpay")
public class RazorpayProperties {

    /** Whether the Razorpay processor is enabled (vs. the in-process simulator). */
    private boolean enabled = false;

    /** Razorpay API key ID (starts with "rzp_"). */
    private String keyId = "";

    /** Razorpay API key secret. */
    private String keySecret = "";

    /** Webhook signing secret used to verify {@code X-Razorpay-Signature}. */
    private String webhookSecret = "";

    /** Base URL for the Razorpay API. */
    private String baseUrl = "https://api.razorpay.com/v1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(final String keyId) {
        this.keyId = keyId;
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(final String keySecret) {
        this.keySecret = keySecret;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(final String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
