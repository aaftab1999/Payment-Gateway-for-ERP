package com.paymentgateway.settlement.observability;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * Request filter that populates the SLF4J {@link MDC} with correlation &amp; request IDs
 * so every log line for a given request is traceable.
 *
 * <p>Fields populated:</p>
 * <ul>
 *   <li>{@code correlationId} — from {@code X-Correlation-Id} header or generated UUID</li>
 *   <li>{@code requestId}    — unique per request</li>
 *   <li>{@code traceId}      — aliases correlationId for compatibility with OTel</li>
 * </ul>
 *
 * <p>Sensitive headers (Authorization, tokens) are never logged.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter implements Filter {

    @Override
    public void doFilter(final ServletRequest request,
                         final ServletResponse response,
                         final FilterChain chain) throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest &&
            response instanceof HttpServletResponse httpResponse) {

            String correlationId = extractOrGenerate(httpRequest, "X-Correlation-Id");
            String requestId = UUID.randomUUID().toString();

            MDC.put("correlationId", correlationId);
            MDC.put("requestId", requestId);
            MDC.put("traceId", correlationId);

            // Expose the resolved correlation ID as a request attribute so
            // controllers can include the same value in response bodies,
            // guaranteeing header/body consistency. Stored as a UUID object
            // so downstream code can read it without re-parsing.
            httpRequest.setAttribute("correlationId", UUID.fromString(correlationId));

            httpResponse.setHeader("X-Correlation-Id", correlationId);

            try {
                chain.doFilter(httpRequest, httpResponse);
            } finally {
                MDC.clear();
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private static String extractOrGenerate(final HttpServletRequest request,
                                            final String headerName) {
        String value = request.getHeader(headerName);
        if (!StringUtils.hasText(value)) {
            return UUID.randomUUID().toString();
        }
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException e) {
            // Malformed header — generate a new UUID consistent with the
            // policy used by PaymentController and the exception handler.
            return UUID.randomUUID().toString();
        }
    }

    @Override
    public void destroy() {
        MDC.clear();
    }
}
