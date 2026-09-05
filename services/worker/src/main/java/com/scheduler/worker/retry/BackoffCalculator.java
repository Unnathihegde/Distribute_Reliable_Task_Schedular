package com.scheduler.worker.retry;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Random;

/**
 * Calculates exponential backoff retry delays with jitter per Section 13 of the blueprint.
 *
 * <p>Formula: {@code delay = min(base_delay_ms * 2^(attempt - 1), max_delay_ms) + random(0, jitter_ms)}</p>
 */
@Component
public class BackoffCalculator {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final long jitterMs;
    private final Random random;

    public BackoffCalculator() {
        this(1000L, 300000L, 500L, new Random());
    }

    public BackoffCalculator(long baseDelayMs, long maxDelayMs, long jitterMs, Random random) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitterMs = jitterMs;
        this.random = random;
    }

    /**
     * Calculates the future Instant when the next retry should be attempted.
     *
     * @param attemptCount current attempt number (1-based)
     * @param now reference timestamp
     * @return Instant of next retry
     */
    public Instant computeNextRetryAt(int attemptCount, Instant now) {
        long delayMs = calculateDelayMs(attemptCount);
        return now.plusMillis(delayMs);
    }

    /**
     * Calculates the retry delay duration in milliseconds.
     *
     * @param attemptCount current attempt number (1-based)
     * @return delay in milliseconds
     */
    public long calculateDelayMs(int attemptCount) {
        int attempt = Math.max(1, attemptCount);
        double baseExponential = baseDelayMs * Math.pow(2, attempt - 1);
        long cappedBase = (long) Math.min(baseExponential, (double) maxDelayMs);
        long jitter = jitterMs > 0 ? (long) (random.nextDouble() * jitterMs) : 0L;
        return cappedBase + jitter;
    }

    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public long getJitterMs() {
        return jitterMs;
    }
}
