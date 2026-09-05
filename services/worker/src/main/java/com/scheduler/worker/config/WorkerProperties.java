package com.scheduler.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * Custom configuration properties for the Worker Service.
 */
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    /** Unique worker identifier. */
    private String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    /** Number of concurrent consumer threads per queue (default 5). */
    private int concurrency = 5;

    /** Number of messages prefetched per consumer (default 5). */
    private int prefetch = 5;

    /** Maximum allowed execution time per task in seconds (default 30s). */
    private int taskTimeoutSeconds = 30;

    /** Initial lease duration assigned on claim in seconds (default 60s). */
    private int leaseDurationSeconds = 60;

    /** Maximum period to wait for in-flight tasks during graceful shutdown in seconds (default 30s). */
    private int shutdownTimeoutSeconds = 30;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(int prefetch) {
        this.prefetch = prefetch;
    }

    public int getTaskTimeoutSeconds() {
        return taskTimeoutSeconds;
    }

    public void setTaskTimeoutSeconds(int taskTimeoutSeconds) {
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    public int getLeaseDurationSeconds() {
        return leaseDurationSeconds;
    }

    public void setLeaseDurationSeconds(int leaseDurationSeconds) {
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }
}
