package com.paymentgateway.settlement.observability;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
 import java.io.IOException;
 import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Logs HTTP requests and responses at DEBUG level
 * <strong>without</strong> exposing sensitive payloads.
 *
 * <p>Scrubbed headers/fields:</p>
 * <ul>
 *   <li>Authorization, X-Correlation-Id headers</li>
 *   <li>password, token, paymentToken, cardNumber, cvv JSON fields</li>
 * </ul>
 *
 * <p>Only the request method, path, status and elapsed time are logged at INFO;
 * payloads are logged at DEBUG for local troubleshooting.</p>
 */
@Component
public final class RequestLoggingFilter implements Filter {

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "x-correlation-id", "cookie");

    private static final Set<String> SENSITIVE_JSON_FIELDS = Set.of(
            "password", "token", "paymentToken", "cardNumber", "cvv",
            "card_token", "secret");

    @Override
    public void doFilter(final ServletRequest request,
                         final ServletResponse response,
                         final FilterChain chain) throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest &&
            response instanceof HttpServletResponse httpResponse) {

            var wrappedRequest = new ContentCachingRequestWrapper(httpRequest);
            var wrappedResponse = new ContentCachingResponseWrapper(httpResponse);

            long start = System.nanoTime();
            String method = wrappedRequest.getMethod();
            String uri = wrappedRequest.getRequestURI();

            try {
                chain.doFilter(wrappedRequest, wrappedResponse);
            } finally {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                int status = wrappedResponse.getStatus();

                String headers = Collections.list(wrappedRequest.getHeaderNames())
                        .stream()
                        .filter(h -> !SENSITIVE_HEADERS.contains(h.toLowerCase()))
                        .collect(Collectors.joining(",", "[", "]"));

                String body = scrubBody(wrappedRequest.getContentAsString());

                var log = org.slf4j.LoggerFactory.getLogger("HTTP_ACCESS");
                log.info("{} {} -> {} ({}ms) headers={}", method, uri, status, elapsedMs, headers);
                if (log.isDebugEnabled()) {
                    log.debug("request body: {}", body);
                }
                wrappedResponse.copyBodyToResponse();
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private static String scrubBody(final String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String scrubbed = body;
        for (String field : SENSITIVE_JSON_FIELDS) {
            scrubbed = scrubbed.replaceAll(
                    "(?i)(\"" + field + "\"\\s*:\\s*\")([^\"]*)(\")",
                    "$1****$3");
        }
        return scrubbed;
    }
}
