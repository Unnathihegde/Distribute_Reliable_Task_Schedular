package com.scheduler.scheduler.recovery;

import com.scheduler.scheduler.config.RabbitMqConfig;
import com.scheduler.scheduler.dispatch.TaskDispatchMessage;
import com.scheduler.scheduler.polling.SchedulerPollLoop;
import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskAttempt;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskAttemptRepository;
import com.scheduler.shared.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Scheduler Service — Recovery Sweeps Integration Tests & Worker Crash Recovery Proof")
class RecoverySweepsIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskAttemptRepository taskAttemptRepository;

    @Autowired
    private LeaseExpiryRecoverySweep leaseExpiryRecoverySweep;

    @Autowired
    private StaleQueuedRecoverySweep staleQueuedRecoverySweep;

    @Autowired
    private SchedulerPollLoop pollLoop;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        rabbitAdmin.purgeQueue(RabbitMqConfig.QUEUE_HIGH);
        rabbitAdmin.purgeQueue(RabbitMqConfig.QUEUE_MEDIUM);
        rabbitAdmin.purgeQueue(RabbitMqConfig.QUEUE_LOW);
        rabbitAdmin.purgeQueue(RabbitMqConfig.QUEUE_DLQ);
    }

    @Test
    @DisplayName("1. Expired RUNNING lease task gets recovered to QUEUED and re-published to RabbitMQ")
    void expiredLeaseTask_isRecoveredToQueuedAndRedispatched() {
        UUID initialLeaseId = UUID.randomUUID();
        Task task = Task.builder()
                .taskType("EMAIL")
                .payload("{\"to\":\"expired@example.com\"}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.RUNNING)
                .scheduledAt(Instant.now().minusSeconds(120))
                .build();
        task.setWorkerId("worker-crashed");
        task.setLeaseId(initialLeaseId);
        task.setLeaseExpiresAt(Instant.now().minusSeconds(30)); // Expired 30 seconds ago
        task = taskRepository.save(task);

        List<Task> recovered = leaseExpiryRecoverySweep.executeSweep();

        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).getId()).isEqualTo(task.getId());

        Task dbTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(dbTask.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(dbTask.getWorkerId()).isNull();
        assertThat(dbTask.getLeaseId()).isNull();
        assertThat(dbTask.getLeaseExpiresAt()).isNull();

        // Verify message was re-published to RabbitMQ
        TaskDispatchMessage message = (TaskDispatchMessage) rabbitTemplate.receiveAndConvert(RabbitMqConfig.QUEUE_MEDIUM, 3000);
        assertThat(message).isNotNull();
        assertThat(message.getTaskId()).isEqualTo(task.getId());
    }

    @Test
    @DisplayName("2. RUNNING task with active (unexpired) lease is left untouched")
    void activeLeaseTask_isNotRecovered() {
        UUID activeLeaseId = UUID.randomUUID();
        Task task = Task.builder()
                .taskType("EMAIL")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.RUNNING)
                .build();
        task.setWorkerId("worker-active");
        task.setLeaseId(activeLeaseId);
        task.setLeaseExpiresAt(Instant.now().plusSeconds(300)); // Expires in 5 minutes
        task = taskRepository.save(task);

        List<Task> recovered = leaseExpiryRecoverySweep.executeSweep();

        assertThat(recovered).isEmpty();

        Task dbTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(dbTask.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(dbTask.getWorkerId()).isEqualTo("worker-active");
        assertThat(dbTask.getLeaseId()).isEqualTo(activeLeaseId);

        TaskDispatchMessage message = (TaskDispatchMessage) rabbitTemplate.receiveAndConvert(RabbitMqConfig.QUEUE_MEDIUM, 500);
        assertThat(message).isNull();
    }

    @Test
    @DisplayName("3. QUEUED task past staleness threshold gets reset to SCHEDULED")
    void staleQueuedTask_isResetToScheduled() {
        Task task = taskRepository.save(Task.builder()
                .taskType("DEMO")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.QUEUED)
                .build());

        // Explicitly backdate updated_at past 5-minute threshold using native SQL
        jdbcTemplate.update("UPDATE tasks SET updated_at = now() - INTERVAL '10 minutes' WHERE id = ?", task.getId());

        int count = staleQueuedRecoverySweep.executeSweep();

        assertThat(count).isEqualTo(1);

        Task dbTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(dbTask.getStatus()).isEqualTo(TaskStatus.SCHEDULED);
    }

    @Test
    @DisplayName("4. QUEUED task within staleness threshold is left untouched")
    void recentQueuedTask_isNotReset() {
        Task task = taskRepository.save(Task.builder()
                .taskType("DEMO")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.QUEUED)
                .build());

        int count = staleQueuedRecoverySweep.executeSweep();

        assertThat(count).isEqualTo(0);

        Task dbTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(dbTask.getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    @DisplayName("5. End-to-End Worker Crash Recovery Proof: Worker 1 crashes mid-execution, Lease Recovery Sweep re-dispatches, Worker 2 completes task to SUCCESS")
    void endToEndWorkerCrashRecovery() {
        // --- Step 1: Submit SCHEDULED task ---
        final Task task = taskRepository.save(Task.builder()
                .taskType("EMAIL")
                .payload("{\"to\":\"crash_recovery_test@example.com\"}")
                .priority(Priority.HIGH)
                .status(TaskStatus.SCHEDULED)
                .scheduledAt(Instant.now().minusSeconds(10))
                .build());

        // Scheduler polls and dispatches task (SCHEDULED -> QUEUED, published to RabbitMQ)
        pollLoop.pollAndDispatch();

        Task queuedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(queuedTask.getStatus()).isEqualTo(TaskStatus.QUEUED);

        // --- Step 2: Worker 1 consumes message and acquires lease (QUEUED -> RUNNING) ---
        TaskDispatchMessage msg1 = (TaskDispatchMessage) rabbitTemplate.receiveAndConvert(RabbitMqConfig.QUEUE_HIGH, 3000);
        assertThat(msg1).isNotNull();
        assertThat(msg1.getTaskId()).isEqualTo(task.getId());

        UUID worker1LeaseId = UUID.randomUUID();
        Instant worker1StartedAt = Instant.now();
        int claimed1 = transactionTemplate.execute(status -> taskRepository.claimLeaseQueuedToRunning(
                task.getId(),
                "worker-instance-1",
                worker1LeaseId,
                Instant.now().plusSeconds(60),
                worker1StartedAt
        ));
        assertThat(claimed1).isEqualTo(1);

        Task runningTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(runningTask.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(runningTask.getWorkerId()).isEqualTo("worker-instance-1");

        // --- Step 3: Simulate Worker 1 crashing mid-execution ---
        // Worker 1 process dies abruptly (SIGKILL). Its lease expires without completing the task or releasing the DB lock.
        jdbcTemplate.update("UPDATE tasks SET lease_expires_at = now() - INTERVAL '1 second' WHERE id = ?", task.getId());

        // --- Step 4: Scheduler Lease Expiry Recovery Sweep runs ---
        List<Task> recovered = leaseExpiryRecoverySweep.executeSweep();
        assertThat(recovered).extracting(Task::getId).contains(task.getId());

        Task recoveredTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(recoveredTask.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(recoveredTask.getWorkerId()).isNull();
        assertThat(recoveredTask.getLeaseId()).isNull();

        // --- Step 5: Worker 2 consumes re-published message from RabbitMQ and completes task ---
        TaskDispatchMessage msg2 = (TaskDispatchMessage) rabbitTemplate.receiveAndConvert(RabbitMqConfig.QUEUE_HIGH, 3000);
        assertThat(msg2).isNotNull();
        assertThat(msg2.getTaskId()).isEqualTo(task.getId());

        UUID worker2LeaseId = UUID.randomUUID();
        Instant worker2StartedAt = Instant.now();
        int claimed2 = transactionTemplate.execute(status -> taskRepository.claimLeaseQueuedToRunning(
                task.getId(),
                "worker-instance-2",
                worker2LeaseId,
                Instant.now().plusSeconds(60),
                worker2StartedAt
        ));
        assertThat(claimed2).isEqualTo(1);

        Instant completedAt = Instant.now();
        int successResult = transactionTemplate.execute(status -> taskRepository.markSuccess(task.getId(), worker2LeaseId, completedAt));
        assertThat(successResult).isEqualTo(1);

        taskAttemptRepository.save(new TaskAttempt(
                task.getId(),
                2,
                "worker-instance-2",
                worker2StartedAt,
                completedAt,
                "SUCCESS",
                null,
                150L
        ));

        // --- Step 6: Final End-to-End Assertions ---
        Task finalTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(finalTask.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(finalTask.getCompletedAt()).isNotNull();

        List<TaskAttempt> attempts = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getWorkerId()).isEqualTo("worker-instance-2");
        assertThat(attempts.get(0).getStatus()).isEqualTo("SUCCESS");
    }
}
