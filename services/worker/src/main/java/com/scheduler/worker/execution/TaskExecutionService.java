package com.scheduler.worker.execution;

import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskAttempt;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskAttemptRepository;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.shared.statemachine.TaskStateMachine;
import com.scheduler.worker.config.WorkerProperties;
import com.scheduler.worker.handler.TaskHandler;
import com.scheduler.worker.handler.TaskHandlerRegistry;
import com.scheduler.worker.retry.BackoffCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Core task execution service executing task handlers with timeouts and recording outcomes.
 *
 * <p>Uses atomic Compare-And-Swap (CAS) transitions via {@link TaskRepository} and validates all
 * lifecycle arcs against {@link TaskStateMachine}.</p>
 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final TaskStateMachine taskStateMachine;
    private final TaskHandlerRegistry taskHandlerRegistry;
    private final BackoffCalculator backoffCalculator;
    private final WorkerProperties workerProperties;
    private final com.scheduler.worker.shutdown.WorkerGracefulShutdownHandler shutdownHandler;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final com.scheduler.shared.metrics.TaskMetrics taskMetrics;
    private final io.opentelemetry.api.OpenTelemetry openTelemetry;
    private final io.opentelemetry.api.trace.Tracer tracer;
    private final java.util.concurrent.atomic.AtomicInteger inFlightCount = new java.util.concurrent.atomic.AtomicInteger(0);

    public TaskExecutionService(TaskRepository taskRepository,
                                TaskAttemptRepository taskAttemptRepository,
                                TaskStateMachine taskStateMachine,
                                TaskHandlerRegistry taskHandlerRegistry,
                                BackoffCalculator backoffCalculator,
                                WorkerProperties workerProperties,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                com.scheduler.worker.shutdown.WorkerGracefulShutdownHandler shutdownHandler,
                                org.springframework.transaction.PlatformTransactionManager transactionManager,
                                com.scheduler.shared.metrics.TaskMetrics taskMetrics,
                                io.opentelemetry.api.OpenTelemetry openTelemetry) {
        this.taskRepository = taskRepository;
        this.taskAttemptRepository = taskAttemptRepository;
        this.taskStateMachine = taskStateMachine;
        this.taskHandlerRegistry = taskHandlerRegistry;
        this.backoffCalculator = backoffCalculator;
        this.workerProperties = workerProperties;
        this.shutdownHandler = shutdownHandler;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.taskMetrics = taskMetrics;
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("com.scheduler.worker");
        this.taskMetrics.registerActiveWorkersGauge(inFlightCount, java.util.concurrent.atomic.AtomicInteger::get);
    }

    /**
     * Executes the lifecycle of a task upon message delivery.
     *
     * @param taskId ID of task to process
     * @return {@code true} if processed/skipped cleanly, {@code false} if lease claim failed
     */
    public boolean processTask(UUID taskId) {
        return processTask(taskId, null);
    }

    public boolean processTask(UUID taskId, io.opentelemetry.context.Context parentContext) {
        // 1. Idempotency guard & CAS claim (QUEUED -> RUNNING)
        ClaimContext claimContext = claimLease(taskId);
        if (claimContext == null) {
            log.info("Task {} is not in QUEUED status or lease claim failed — skipping execution", taskId);
            return true; // ACK message (idempotency guard)
        }

        Task task = claimContext.task();
        UUID leaseId = claimContext.leaseId();
        Instant startedAt = claimContext.startedAt();

        inFlightCount.incrementAndGet();
        if (shutdownHandler != null) {
            shutdownHandler.taskStarted();
        }

        try {
            // 2. Resolve Handler
            Optional<TaskHandler> handlerOpt = taskHandlerRegistry.getHandler(task.getTaskType());
            if (handlerOpt.isEmpty()) {
                String errorMsg = "No registered TaskHandler for task_type: " + task.getTaskType();
                log.error(errorMsg);
                recordFailure(task, leaseId, startedAt, new IllegalArgumentException(errorMsg));
                return true;
            }

            TaskHandler handler = handlerOpt.get();

            // 3. Execute with Virtual Threads, Explicit MDC & OTel Context Propagation, and Timeout
            Instant startTime = Instant.now();
            Throwable executionError = null;
            int currentAttempt = task.getAttemptCount() + 1;
            io.opentelemetry.context.Context otelCtx = parentContext != null ? parentContext : io.opentelemetry.context.Context.current();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> future = executor.submit(() -> {
                    // Explicitly propagate OTel Context and MDC into Virtual Thread
                    try (io.opentelemetry.context.Scope scope = otelCtx.makeCurrent()) {
                        io.opentelemetry.api.trace.Span executionSpan = tracer.spanBuilder("worker.executeTask")
                                .setAttribute("task_id", task.getId().toString())
                                .setAttribute("worker_id", workerProperties.getWorkerId())
                                .setAttribute("attempt", String.valueOf(currentAttempt))
                                .setAttribute("task_type", task.getTaskType())
                                .startSpan();

                        try (io.opentelemetry.context.Scope spanScope = executionSpan.makeCurrent();
                             var idC = org.slf4j.MDC.putCloseable("task_id", task.getId().toString());
                             var workerC = org.slf4j.MDC.putCloseable("worker_id", workerProperties.getWorkerId());
                             var attemptC = org.slf4j.MDC.putCloseable("attempt", String.valueOf(currentAttempt));
                             var eventC = org.slf4j.MDC.putCloseable("event", "task_execution_started");
                             var traceIdC = org.slf4j.MDC.putCloseable("traceId", executionSpan.getSpanContext().getTraceId());
                             var spanIdC = org.slf4j.MDC.putCloseable("spanId", executionSpan.getSpanContext().getSpanId())) {
                            log.info("Task execution started");
                            handler.execute(task);
                            return null;
                        } catch (Throwable t) {
                            executionSpan.recordException(t);
                            throw t;
                        } finally {
                            executionSpan.end();
                        }
                    }
                });
                future.get(workerProperties.getTaskTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                executionError = e.getCause() != null ? e.getCause() : e;
            } catch (TimeoutException e) {
                executionError = new TimeoutException("Task execution timed out after " + workerProperties.getTaskTimeoutSeconds() + " seconds");
            } catch (Exception e) {
                executionError = e;
            }

            long durationMs = Duration.between(startTime, Instant.now()).toMillis();

            // 4. Record Outcome
            if (executionError == null) {
                recordSuccess(task, leaseId, startedAt, durationMs);
            } else {
                recordFailure(task, leaseId, startedAt, executionError, durationMs);
            }

            return true;
        } finally {
            inFlightCount.decrementAndGet();
            if (shutdownHandler != null) {
                shutdownHandler.taskCompleted();
            }
        }
    }

    public ClaimContext claimLease(UUID taskId) {
        return transactionTemplate.execute(status -> {
            Optional<Task> taskOpt = taskRepository.findById(taskId);
            if (taskOpt.isEmpty()) {
                return null;
            }

            Task task = taskOpt.get();
            if (task.getStatus() != TaskStatus.QUEUED) {
                return null; // Idempotency check: task not QUEUED
            }

            // Validate state machine rule: QUEUED -> RUNNING
            taskStateMachine.assertTransitionAllowed(task.getId(), task.getStatus(), TaskStatus.RUNNING);

            UUID leaseId = UUID.randomUUID();
            Instant startedAt = Instant.now();
            Instant leaseExpiresAt = startedAt.plusSeconds(workerProperties.getLeaseDurationSeconds());

            int updated = taskRepository.claimLeaseQueuedToRunning(
                    taskId,
                    workerProperties.getWorkerId(),
                    leaseId,
                    leaseExpiresAt,
                    startedAt
            );

            if (updated == 0) {
                return null; // Concurrency conflict
            }

            return new ClaimContext(task, leaseId, startedAt);
        });
    }

    public void recordSuccess(Task task, UUID leaseId, Instant startedAt, long durationMs) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant completedAt = Instant.now();

            // Validate state transition RUNNING -> SUCCESS
            taskStateMachine.assertTransitionAllowed(task.getId(), TaskStatus.RUNNING, TaskStatus.SUCCESS);

            taskRepository.markSuccess(task.getId(), leaseId, completedAt);

            int attemptNumber = task.getAttemptCount() + 1;
            TaskAttempt attempt = new TaskAttempt(
                    task.getId(),
                    attemptNumber,
                    workerProperties.getWorkerId(),
                    startedAt,
                    completedAt,
                    "SUCCESS",
                    null,
                    durationMs
            );
            taskAttemptRepository.save(attempt);

            // Record metrics
            taskMetrics.incrementTasksCompleted(task.getTaskType(), "SUCCESS");
            taskMetrics.recordTaskExecutionDuration(Duration.ofMillis(durationMs));
            if (task.getScheduledAt() != null) {
                Duration queueWait = Duration.between(task.getScheduledAt(), startedAt);
                if (!queueWait.isNegative()) {
                    taskMetrics.recordTaskQueueWait(queueWait);
                }
            }
            if (task.getCreatedAt() != null) {
                Duration endToEnd = Duration.between(task.getCreatedAt(), completedAt);
                if (!endToEnd.isNegative()) {
                    taskMetrics.recordTaskEndToEnd(endToEnd);
                }
            }

            // Structured JSON logging
            try (var idC = org.slf4j.MDC.putCloseable("task_id", task.getId().toString());
                 var workerC = org.slf4j.MDC.putCloseable("worker_id", workerProperties.getWorkerId());
                 var attemptC = org.slf4j.MDC.putCloseable("attempt", String.valueOf(attemptNumber));
                 var durationC = org.slf4j.MDC.putCloseable("duration_ms", String.valueOf(durationMs));
                 var statusC = org.slf4j.MDC.putCloseable("status", "SUCCESS");
                 var eventC = org.slf4j.MDC.putCloseable("event", "task_execution_completed")) {
                log.info("Task {} completed successfully (attempt {})", task.getId(), attemptNumber);
            }

            // Handle recurring task: schedule next occurrence if enabled
            if (task.isRecurrenceEnabled() && task.getCronExpression() != null) {
                try {
                    Optional<Instant> nextRunOpt = com.scheduler.shared.util.CronNextRunCalculator.computeNextRunAt(
                            task.getCronExpression(), completedAt
                    );
                    if (nextRunOpt.isPresent()) {
                        Task childTask = Task.builder()
                                .taskType(task.getTaskType())
                                .payload(task.getPayload())
                                .priority(task.getPriority())
                                .status(TaskStatus.SCHEDULED)
                                .scheduledAt(nextRunOpt.get())
                                .maxAttempts(task.getMaxAttempts())
                                .cronExpression(task.getCronExpression())
                                .recurrenceEnabled(true)
                                .parentTask(task)
                                .createdBy(task.getCreatedBy())
                                .build();
                        taskRepository.save(childTask);
                        log.info("Scheduled next recurring instance of task {} (child task {}) for {}",
                                task.getId(), childTask.getId(), nextRunOpt.get());
                    } else {
                        log.error("Failed to compute next execution time for recurring task {} with cron expression '{}'",
                                task.getId(), task.getCronExpression());
                    }
                } catch (Exception e) {
                    log.error("Failed to schedule next recurring occurrence for task {} with cron expression '{}': {}",
                            task.getId(), task.getCronExpression(), e.getMessage(), e);
                }
            }
        });
    }

    public void recordFailure(Task task, UUID leaseId, Instant startedAt, Throwable error) {
        recordFailure(task, leaseId, startedAt, error, 0L);
    }

    public void recordFailure(Task task, UUID leaseId, Instant startedAt, Throwable error, long durationMs) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant completedAt = Instant.now();
            int attemptNumber = task.getAttemptCount() + 1;
            String errorMsg = error.getMessage() != null ? error.getMessage() : error.toString();

            if (attemptNumber < task.getMaxAttempts()) {
                // Retries remaining -> RUNNING -> RETRY_WAIT
                taskStateMachine.assertTransitionAllowed(task.getId(), TaskStatus.RUNNING, TaskStatus.RETRY_WAIT);
                Instant nextRetryAt = backoffCalculator.computeNextRetryAt(attemptNumber, completedAt);

                taskRepository.markRetryWait(task.getId(), leaseId, nextRetryAt, errorMsg);

                taskMetrics.incrementTasksRetried(task.getTaskType());
                taskMetrics.recordTaskExecutionDuration(Duration.ofMillis(durationMs));

                try (var idC = org.slf4j.MDC.putCloseable("task_id", task.getId().toString());
                     var workerC = org.slf4j.MDC.putCloseable("worker_id", workerProperties.getWorkerId());
                     var attemptC = org.slf4j.MDC.putCloseable("attempt", String.valueOf(attemptNumber));
                     var durationC = org.slf4j.MDC.putCloseable("duration_ms", String.valueOf(durationMs));
                     var statusC = org.slf4j.MDC.putCloseable("status", "RETRY_WAIT");
                     var eventC = org.slf4j.MDC.putCloseable("event", "task_execution_failed")) {
                    log.warn("Task {} failed attempt {}/{}, retry scheduled at {}: {}",
                            task.getId(), attemptNumber, task.getMaxAttempts(), nextRetryAt, errorMsg);
                }
            } else {
                // Retries exhausted -> RUNNING -> FAILED -> DEAD_LETTER
                taskStateMachine.assertTransitionAllowed(task.getId(), TaskStatus.RUNNING, TaskStatus.FAILED);
                taskStateMachine.assertTransitionAllowed(task.getId(), TaskStatus.FAILED, TaskStatus.DEAD_LETTER);

                taskRepository.markDeadLetter(task.getId(), leaseId, completedAt, errorMsg);

                taskMetrics.incrementTasksCompleted(task.getTaskType(), "DEAD_LETTER");
                taskMetrics.incrementTasksDeadLettered(task.getTaskType());
                taskMetrics.recordTaskExecutionDuration(Duration.ofMillis(durationMs));

                try (var idC = org.slf4j.MDC.putCloseable("task_id", task.getId().toString());
                     var workerC = org.slf4j.MDC.putCloseable("worker_id", workerProperties.getWorkerId());
                     var attemptC = org.slf4j.MDC.putCloseable("attempt", String.valueOf(attemptNumber));
                     var durationC = org.slf4j.MDC.putCloseable("duration_ms", String.valueOf(durationMs));
                     var statusC = org.slf4j.MDC.putCloseable("status", "DEAD_LETTER");
                     var eventC = org.slf4j.MDC.putCloseable("event", "task_dead_lettered")) {
                    log.error("Task {} failed attempt {}/{} (exhausted), moved to DEAD_LETTER: {}",
                            task.getId(), attemptNumber, task.getMaxAttempts(), errorMsg);
                }
            }

            TaskAttempt attempt = new TaskAttempt(
                    task.getId(),
                    attemptNumber,
                    workerProperties.getWorkerId(),
                    startedAt,
                    completedAt,
                    "FAILED",
                    errorMsg,
                    durationMs
            );
            taskAttemptRepository.save(attempt);
        });
    }

    public record ClaimContext(Task task, UUID leaseId, Instant startedAt) {}
}
