package com.scheduler.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Custom configuration properties for the Scheduler service.
 */
@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {

    /** Poll loop interval in milliseconds (default 5000ms). */
    private long pollInterval = 5000;

    /** Maximum number of tasks to claim per poll iteration (default 50). */
    private int batchSize = 50;

    /** Retry-wait recovery sweep interval in milliseconds (default 10000ms). */
    private long retrySweepInterval = 10000;

    /** Lease-expiry recovery sweep interval in milliseconds (default 30000ms). */
    private long leaseRecoveryInterval = 30000;

    /** Stale-queued recovery sweep interval in milliseconds (default 60000ms). */
    private long staleQueuedInterval = 60000;

    /** Threshold in minutes past which a QUEUED task is considered stale (default 5 min). */
    private long staleQueuedThresholdMinutes = 5;

    public long getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(long pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getRetrySweepInterval() {
        return retrySweepInterval;
    }

    public void setRetrySweepInterval(long retrySweepInterval) {
        this.retrySweepInterval = retrySweepInterval;
    }

    public long getLeaseRecoveryInterval() {
        return leaseRecoveryInterval;
    }

    public void setLeaseRecoveryInterval(long leaseRecoveryInterval) {
        this.leaseRecoveryInterval = leaseRecoveryInterval;
    }

    public long getStaleQueuedInterval() {
        return staleQueuedInterval;
    }

    public void setStaleQueuedInterval(long staleQueuedInterval) {
        this.staleQueuedInterval = staleQueuedInterval;
    }

    public long getStaleQueuedThresholdMinutes() {
        return staleQueuedThresholdMinutes;
    }

    public void setStaleQueuedThresholdMinutes(long staleQueuedThresholdMinutes) {
        this.staleQueuedThresholdMinutes = staleQueuedThresholdMinutes;
    }
}
