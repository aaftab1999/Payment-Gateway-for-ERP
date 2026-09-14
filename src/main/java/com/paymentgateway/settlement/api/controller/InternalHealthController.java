package com.paymentgateway.settlement.api.controller;

import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Non-business technical verification endpoint.
 *
 * <p>Returns the application status, name and the current correlation ID
 * so an external load balancer or Kubernetes liveness probe can parse
 * a structured response.</p>
 *
 * <p>This is a lightweight technical check that does not depend on
 * external infrastructure health — use Spring Boot Actuator's
 * {@code /actuator/health} for full down-stream health checks.</p>
 */
@RestController
@RequestMapping("/internal")
public final class InternalHealthController {

    private final String applicationName;

    public InternalHealthController(final org.springframework.core.env.Environment env) {
        this.applicationName = env.getProperty("spring.application.name", "payment-gateway");
    }

    /**
     * {@code GET /internal/health}
     *
     * @return 200 with application status; 503 if the app failed to start
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InternalHealthResponse> health(final HttpServletRequest request) {
        var body = new InternalHealthResponse(
                "UP",
                applicationName,
                Instant.now(),
                correlationFromRequest(request)
        );
        return ResponseEntity.ok(body);
    }

    private static String correlationFromRequest(final HttpServletRequest request) {
        String correlation = request.getHeader("X-Correlation-Id");
        return (correlation != null && !correlation.isBlank())
                ? correlation
                : "none";
    }

    /** Record response — no sensitive fields. */
    public record InternalHealthResponse(
            String status,
            String applicationName,
            Instant timestamp,
            String correlationId) {}
}
