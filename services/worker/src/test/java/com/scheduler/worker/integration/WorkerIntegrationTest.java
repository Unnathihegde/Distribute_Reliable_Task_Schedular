package com.scheduler.worker.integration;

import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskAttempt;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskAttemptRepository;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.worker.config.WorkerProperties;
import com.scheduler.worker.execution.TaskExecutionService;
import com.scheduler.worker.handler.TaskHandler;
import com.scheduler.worker.handler.TaskHandlerRegistry;
import com.scheduler.worker.shutdown.WorkerGracefulShutdownHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Worker Service — Comprehensive Integration Tests ((a)-(g))")
class WorkerIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskAttemptRepository taskAttemptRepository;

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Autowired
    private TaskHandlerRegistry taskHandlerRegistry;

    @Autowired
    private WorkerProperties workerProperties;

    @Autowired
    private WorkerGracefulShutdownHandler shutdownHandler;

    @BeforeEach
    void setUp() {
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
    }

    @Test
    @DisplayName("(a) CAS Lease Acquisition: QUEUED -> RUNNING updates worker_id, lease_id, lease_expires_at")
    void casLeaseAcquisition_queuedToRunning() {
        Task task = taskRepository.save(Task.builder()
                .taskType("DEMO")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.QUEUED)
                .scheduledAt(Instant.now().minusSeconds(10))
                .build());

        TaskExecutionService.ClaimContext context = taskExecutionService.claimLease(task.getId());

        assertThat(context).isNotNull();
        assertThat(context.task().getId()).isEqualTo(task.getId());

        Task dbTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(dbTask.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(dbTask.getWorkerId()).isEqualTo(workerProperties.getWorkerId());
        assertThat(dbTask.getLeaseId()).isNotNull();
        assertThat(dbTask.getLeaseExpiresAt()).isAfter(Instant.now());
        assertThat(dbTask.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("(b) Execution Timeout Enforcement: Slow task handler times out and records failure")
    void executionTimeoutEnforcement_slowHandlerTimesOut() {
        TaskHandler slowHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "SLOW_TASK";
            }

            @Override
            public void execute(Task task) throws Exception {
                Thread.sleep(3000);
            }
        };
        taskHandlerRegistry.registerHandler(slowHandler);

        Task task = taskRepository.save(Task.builder()
                .taskType("SLOW_TASK")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.QUEUED)
                .maxAttempts(3)
                .build());

        boolean result = taskExecutionService.processTask(task.getId());
        assertThat(result).isTrue();

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.RETRY_WAIT);
        assertThat(updated.getLastError()).contains("timed out");

        List<TaskAttempt> attempts = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(attempts.get(0).getErrorMessage()).contains("timed out");
    }

    @Test
    @DisplayName("(c) Successful Execution End-to-End: RUNNING -> SUCCESS and TaskAttempt saved")
    void successfulExecution_endToEnd() {
        Task task = taskRepository.save(Task.builder()
                .taskType("DEMO")
                .payload("{\"key\":\"value\"}")
                .priority(Priority.HIGH)
                .status(TaskStatus.QUEUED)
                .build());

        boolean processed = taskExecutionService.processTask(task.getId());
        assertThat(processed).isTrue();

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(updated.getCompletedAt()).isNotNull();

        List<TaskAttempt> attempts = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(attempts.get(0).getWorkerId()).isEqualTo(workerProperties.getWorkerId());
    }

    @Test
    @DisplayName("(d) Failure with Retries Remaining: RUNNING -> RETRY_WAIT with next_retry_at backoff")
    void failureWithRetriesLeft_transitionsToRetryWait() {
        TaskHandler failingHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "FAILING";
            }

            @Override
            public void execute(Task task) throws Exception {
                throw new RuntimeException("Transient DB error");
            }
        };
        taskHandlerRegistry.registerHandler(failingHandler);

        Task task = taskRepository.save(Task.builder()
                .taskType("FAILING")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.QUEUED)
                .maxAttempts(5)
                .build());

        boolean processed = taskExecutionService.processTask(task.getId());
        assertThat(processed).isTrue();

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.RETRY_WAIT);
        assertThat(updated.getNextRetryAt()).isAfter(Instant.now());
        assertThat(updated.getLastError()).isEqualTo("Transient DB error");

        List<TaskAttempt> attempts = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(attempts.get(0).getErrorMessage()).isEqualTo("Transient DB error");
    }

    @Test
    @DisplayName("(e) Retries Exhausted: RUNNING -> FAILED -> DEAD_LETTER when attempt_count reaches max_attempts")
    void retriesExhausted_transitionsToDeadLetter() {
        TaskHandler failingHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "FATAL";
            }

            @Override
            public void execute(Task task) throws Exception {
                throw new RuntimeException("Fatal error");
            }
        };
        taskHandlerRegistry.registerHandler(failingHandler);

        Task task = Task.builder()
                .taskType("FATAL")
                .payload("{}")
                .priority(Priority.LOW)
                .status(TaskStatus.QUEUED)
                .maxAttempts(3)
                .build();
        task.setAttemptCount(2); // 2 previous attempts; 3rd attempt exhausts maxAttempts
        task = taskRepository.save(task);

        boolean processed = taskExecutionService.processTask(task.getId());
        assertThat(processed).isTrue();

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.DEAD_LETTER);
        assertThat(updated.getCompletedAt()).isNotNull();
        assertThat(updated.getLastError()).isEqualTo("Fatal error");

        List<TaskAttempt> attempts = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(3);
        assertThat(attempts.get(0).getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("(f) Idempotency Proof: Redelivered message for an already-SUCCESS task is skipped without re-execution")
    void redeliveredMessageForSuccessTask_skippedWithoutReExecution() {
        AtomicInteger executionCounter = new AtomicInteger(0);
        TaskHandler countingHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "COUNTING";
            }

            @Override
            public void execute(Task task) throws Exception {
                executionCounter.incrementAndGet();
            }
        };
        taskHandlerRegistry.registerHandler(countingHandler);

        Task task = taskRepository.save(Task.builder()
                .taskType("COUNTING")
                .payload("{}")
                .priority(Priority.HIGH)
                .status(TaskStatus.QUEUED)
                .build());

        // First delivery: processes successfully
        boolean firstProcessed = taskExecutionService.processTask(task.getId());
        assertThat(firstProcessed).isTrue();
        assertThat(executionCounter.get()).isEqualTo(1);

        Task afterFirst = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(TaskStatus.SUCCESS);

        // Second delivery (duplicate/redelivered message from RabbitMQ):
        boolean secondProcessed = taskExecutionService.processTask(task.getId());
        assertThat(secondProcessed).isTrue();

        // Prove idempotency: handler was NOT invoked a second time
        assertThat(executionCounter.get())
                .withFailMessage("Idempotency violation! Task handler was executed on redelivery of already SUCCESS task")
                .isEqualTo(1);

        Task afterSecond = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }

    @Test
    @DisplayName("(g) Graceful Shutdown: Tracks active tasks and drains cleanly")
    void gracefulShutdown_tracksActiveTasksAndDrains() {
        assertThat(shutdownHandler.getActiveTaskCount()).isEqualTo(0);

        shutdownHandler.taskStarted();
        assertThat(shutdownHandler.getActiveTaskCount()).isEqualTo(1);

        shutdownHandler.taskCompleted();
        assertThat(shutdownHandler.getActiveTaskCount()).isEqualTo(0);

        assertThat(shutdownHandler.isRunning()).isTrue();
    }
}
