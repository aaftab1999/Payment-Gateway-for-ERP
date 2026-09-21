package com.paymentgateway.settlement.infrastructure.external.provider.razorpay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Verifies Razorpay webhook signatures.
 *
 * <p>Razorpay signs each webhook with the {@code X-Razorpay-Signature}
 * header, which is the HMAC-SHA256 of the raw request body using the
 * webhook secret as the key, encoded as a lowercase hex string:</p>
 *
 * <pre>
 *   signature = HMAC-SHA256(webhookSecret, rawRequestBody)  →  hex
 * </pre>
 *
 * <p>The comparison uses {@link java.security.MessageDigest#isEqual}
 * (a constant-time comparison) to prevent timing attacks.</p>
 */
public final class RazorpaySignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private RazorpaySignatureVerifier() {
    }

    /**
     * Verifies a Razorpay webhook signature against the raw request body.
     *
     * @param signature     the value of the {@code X-Razorpay-Signature} header
     * @param rawBody       the raw, unparsed HTTP request body
     * @param webhookSecret the configured Razorpay webhook secret
     * @return {@code true} if the signature is valid
     */
    public static boolean verify(final String signature,
                                 final byte[] rawBody,
                                 final String webhookSecret) {
        if (signature == null || signature.isBlank()
                || rawBody == null || rawBody.length == 0
                || webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(rawBody);
            String expected = HexFormat.of().formatHex(digest);
            return java.security.MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
