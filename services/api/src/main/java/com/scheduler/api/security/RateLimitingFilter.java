package com.scheduler.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed sliding-window rate limiter for task creation endpoints.
 *
 * <p>Executes after {@link ApiKeyAuthenticationFilter} (Order: HIGHEST_PRECEDENCE + 20).
 * Uses the caller's validated API key as the bucket key: {@code rate_limit:{apiKey}}.</p>
 *
 * <p><b>Fail-Open Strategy:</b> If Redis is unavailable or throws a connection exception,
 * the limiter logs a WARN and allows the request to proceed (fail-open), preserving system
 * availability per blueprint specifications.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int permitsPerMinute;

    @Autowired
    public RateLimitingFilter(@Autowired(required = false) StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               @Value("${scheduler.security.rate-limit.permits-per-minute:100}") int permitsPerMinute) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.permitsPerMinute = permitsPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only rate limit task submission mutations (POST /api/v1/tasks)
        return !(request.getMethod().equalsIgnoreCase("POST") && request.getRequestURI().endsWith("/api/v1/tasks"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER);
        String bucketKey = "rate_limit:" + (apiKey != null ? apiKey : "anonymous");

        if (redisTemplate == null) {
            log.warn("StringRedisTemplate bean is null. Failing open for request on {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        try {
            long now = System.currentTimeMillis();
            long windowStart = now - 60_000;

            // Remove expired entries older than 60s
            redisTemplate.opsForZSet().removeRangeByScore(bucketKey, 0, windowStart);

            // Count requests in current window
            Long count = redisTemplate.opsForZSet().zCard(bucketKey);

            if (count != null && count >= permitsPerMinute) {
                log.warn("Rate limit exceeded for key bucket {}. Count: {}, Limit: {}", bucketKey, count, permitsPerMinute);
                sendTooManyRequestsError(request, response);
                return;
            }

            // Record request & refresh 60s key expiration
            redisTemplate.opsForZSet().add(bucketKey, String.valueOf(now), (double) now);
            redisTemplate.expire(bucketKey, Duration.ofSeconds(60));

        } catch (Exception e) {
            // EXPLICIT FAIL-OPEN REQUIREMENT: Redis error must log WARN and allow request to proceed
            log.warn("Redis rate limiter encountered error: {}. Failing open (allowing request).", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private void sendTooManyRequestsError(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiError apiError = ApiError.of(
                "TOO_MANY_REQUESTS",
                "Rate limit exceeded. Maximum " + permitsPerMinute + " requests per minute allowed.",
                HttpStatus.TOO_MANY_REQUESTS.value(),
                request.getRequestURI()
        );

        objectMapper.writeValue(response.getOutputStream(), apiError);
    }

    public int getPermitsPerMinute() {
        return permitsPerMinute;
    }
}
