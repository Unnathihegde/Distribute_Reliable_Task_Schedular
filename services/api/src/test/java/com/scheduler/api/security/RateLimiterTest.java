package com.scheduler.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private ZSetOperations<String, String> zSetOps;
    private ObjectMapper objectMapper;
    private RateLimitingFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        zSetOps = Mockito.mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        objectMapper = new ObjectMapper();
        filter = new RateLimitingFilter(redisTemplate, objectMapper, 5);
    }

    @Test
    @DisplayName("Rate limiter blocks when Redis request count reaches configured permits threshold (5/min)")
    void rateLimiter_blocksAfterConfiguredThreshold_returns429TooManyRequests() throws Exception {
        when(zSetOps.zCard("rate_limit:test-api-key")).thenReturn(5L);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        request.addHeader("X-API-Key", "test-api-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(429, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("TOO_MANY_REQUESTS"));
    }

    @Test
    @DisplayName("Rate limiter builds Redis key using caller's X-API-Key (rate_limit:{apiKey}), maintaining independent buckets per key")
    void rateLimiter_usesCallerApiKeyInRedisBucketKey_independentLimitersPerKey() throws Exception {
        // Key A is at limit (5 requests)
        when(zSetOps.zCard("rate_limit:key-A")).thenReturn(5L);
        // Key B has 0 requests
        when(zSetOps.zCard("rate_limit:key-B")).thenReturn(0L);

        // Request with Key B succeeds even though Key A is blocked
        MockHttpServletRequest requestB = new MockHttpServletRequest("POST", "/api/v1/tasks");
        requestB.addHeader("X-API-Key", "key-B");
        MockHttpServletResponse responseB = new MockHttpServletResponse();
        MockFilterChain filterChainB = new MockFilterChain();

        filter.doFilterInternal(requestB, responseB, filterChainB);

        assertEquals(200, responseB.getStatus());
        verify(zSetOps).zCard("rate_limit:key-B");
        verify(zSetOps).add(eq("rate_limit:key-B"), anyString(), anyDouble());
    }
}
