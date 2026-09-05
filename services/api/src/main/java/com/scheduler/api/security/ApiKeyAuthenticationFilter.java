package com.scheduler.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.exception.ApiError;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Filter enforcing X-API-Key header authentication for API endpoints.
 *
 * <p>Executes at highest precedence before rate limiting to ensure unauthenticated
 * requests fail immediately (401 Unauthorized) without consuming rate limit buckets.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    public static final String API_KEY_HEADER = "X-API-Key";

    private final String configuredApiKey;
    private final ObjectMapper objectMapper;

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/actuator/health",
            "/actuator/prometheus",
            "/swagger-ui",
            "/v3/api-docs",
            "/swagger-ui.html"
    );

    public ApiKeyAuthenticationFilter(@Value("${scheduler.security.api-key:#{null}}") String configuredApiKey,
                                      ObjectMapper objectMapper) {
        this.configuredApiKey = configuredApiKey;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            throw new IllegalStateException("API_KEY environment variable (scheduler.security.api-key) is required and must not be blank.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestApiKey = request.getHeader(API_KEY_HEADER);

        if (requestApiKey == null || requestApiKey.isBlank() || !configuredApiKey.equals(requestApiKey)) {
            log.warn("Unauthorized API access attempt on {} from IP {}", request.getRequestURI(), request.getRemoteAddr());
            sendUnauthorizedError(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorizedError(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiError apiError = ApiError.of(
                "UNAUTHORIZED",
                "Invalid or missing API key in " + API_KEY_HEADER + " header",
                HttpStatus.UNAUTHORIZED.value(),
                request.getRequestURI()
        );

        objectMapper.writeValue(response.getOutputStream(), apiError);
    }
}
