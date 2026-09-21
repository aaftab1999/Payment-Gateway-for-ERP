package com.paymentgateway.settlement.infrastructure.external.provider.razorpay;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignatureVerifierTest {

    private static final String WEBHOOK_SECRET = "my_webhook_secret_123";

    private String sign(final String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Test
    void validSignatureReturnsTrue() throws Exception {
        String body = "{\"event\":\"payment.captured\",\"payload\":{}}";
        String signature = sign(body);

        assertThat(RazorpaySignatureVerifier.verify(signature, body.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET))
                .isTrue();
    }

    @Test
    void tamperedBodyReturnsFalse() throws Exception {
        String body = "{\"event\":\"payment.captured\",\"payload\":{}}";
        String signature = sign(body);

        String tampered = "{\"event\":\"payment.failed\",\"payload\":{}}";
        assertThat(RazorpaySignatureVerifier.verify(signature, tampered.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET))
                .isFalse();
    }

    @Test
    void wrongSecretReturnsFalse() throws Exception {
        String body = "{\"event\":\"payment.captured\",\"payload\":{}}";
        String signature = sign(body);

        assertThat(RazorpaySignatureVerifier.verify(signature, body.getBytes(StandardCharsets.UTF_8), "wrong_secret"))
                .isFalse();
    }

    @Test
    void nullSignatureReturnsFalse() {
        assertThat(RazorpaySignatureVerifier.verify(
                null, "{}".getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET))
                .isFalse();
    }

    @Test
    void nullBodyReturnsFalse() throws Exception {
        String signature = sign("{}");
        assertThat(RazorpaySignatureVerifier.verify(
                signature, null, WEBHOOK_SECRET))
                .isFalse();
    }

    @Test
    void nullSecretReturnsFalse() {
        assertThat(RazorpaySignatureVerifier.verify(
                "abc123", "{}".getBytes(StandardCharsets.UTF_8), null))
                .isFalse();
    }

    @Test
    void blankInputsReturnFalse() {
        assertThat(RazorpaySignatureVerifier.verify(
                "", "{}".getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET))
                .isFalse();
    }
}
