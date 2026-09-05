package com.scheduler.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * Centralized Micrometer metric definitions and helper methods for the Task Scheduler platform.
 *
 * <p>Depends ONLY on {@code micrometer-core}. Exporters (such as Prometheus) live in individual service modules.</p>
 */
@Component
public class TaskMetrics {

    private final MeterRegistry registry;

    public TaskMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    // --- Counter Metrics ---

    public void incrementTasksSubmitted(String type, String priority) {
        Counter.builder("tasks_submitted_total")
                .description("Total number of tasks submitted via API")
                .tag("type", type != null ? type : "UNKNOWN")
                .tag("priority", priority != null ? priority : "NORMAL")
                .register(registry)
                .increment();
    }

    public void incrementTasksCompleted(String type, String status) {
        Counter.builder("tasks_completed_total")
                .description("Total number of tasks completed")
                .tag("type", type != null ? type : "UNKNOWN")
                .tag("status", status != null ? status : "UNKNOWN")
                .register(registry)
                .increment();
    }

    public void incrementTasksRetried(String type) {
        Counter.builder("tasks_retried_total")
                .description("Total number of task retries scheduled")
                .tag("type", type != null ? type : "UNKNOWN")
                .register(registry)
                .increment();
    }

    public void incrementTasksDeadLettered(String type) {
        Counter.builder("tasks_dead_lettered_total")
                .description("Total number of tasks moved to dead letter queue")
                .tag("type", type != null ? type : "UNKNOWN")
                .register(registry)
                .increment();
    }

    public void incrementSchedulerClaims() {
        Counter.builder("scheduler_claims_total")
                .description("Total number of tasks claimed by scheduler poll loops")
                .register(registry)
                .increment();
    }

    public void incrementSchedulerDispatches() {
        Counter.builder("scheduler_dispatches_total")
                .description("Total number of tasks dispatched to RabbitMQ queues")
                .register(registry)
                .increment();
    }

    public void incrementLeaseRecoveries() {
        Counter.builder("lease_recoveries_total")
                .description("Total number of tasks recovered from expired worker leases")
                .register(registry)
                .increment();
    }

    // --- Gauge Metrics ---

    public <T> void registerQueueDepthGauge(String queueName, T stateObj, ToDoubleFunction<T> depthFunction) {
        Gauge.builder("queue_depth", stateObj, depthFunction)
                .description("Current RabbitMQ queue depth")
                .tag("queue", queueName)
                .register(registry);
    }

    public <T> void registerActiveWorkersGauge(T stateObj, ToDoubleFunction<T> activeCountFunction) {
        Gauge.builder("active_workers", stateObj, activeCountFunction)
                .description("Current number of active worker tasks in execution")
                .register(registry);
    }

    public <T> void registerTasksInStatusGauge(String status, T stateObj, ToDoubleFunction<T> countFunction) {
        Gauge.builder("tasks_in_status", stateObj, countFunction)
                .description("Current count of tasks in PostgreSQL by status")
                .tag("status", status)
                .register(registry);
    }

    // --- Timer / Histogram Metrics ---

    public void recordTaskExecutionDuration(Duration duration) {
        Timer.builder("task_execution_duration_seconds")
                .description("Task execution duration in seconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void recordTaskSchedulingLatency(Duration duration) {
        Timer.builder("task_scheduling_latency_seconds")
                .description("Latency from scheduled_at to actual dispatch time in seconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void recordTaskQueueWait(Duration duration) {
        Timer.builder("task_queue_wait_seconds")
                .description("Wait time from QUEUED to RUNNING state in seconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void recordTaskEndToEnd(Duration duration) {
        Timer.builder("task_end_to_end_seconds")
                .description("End-to-end duration from created_at to SUCCESS completion in seconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }
}
