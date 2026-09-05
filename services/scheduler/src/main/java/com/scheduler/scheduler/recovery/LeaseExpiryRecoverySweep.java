package com.scheduler.scheduler.recovery;

import com.scheduler.scheduler.dispatch.TaskDispatcher;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.shared.statemachine.TaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background recovery sweep for tasks in {@code RUNNING} status whose worker lease has expired.
 *
 * <p>Per Section 15 of the blueprint, when a worker holding a task lease crashes or loses network
 * connectivity, its lease expires ({@code lease_expires_at < now()}). This sweep finds such tasks,
 * resets them to {@code QUEUED} status, clears worker lease attributes, and re-publishes them
 * to RabbitMQ so another worker can pick them up.</p>
 */
@Component
public class LeaseExpiryRecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpiryRecoverySweep.class);

    private final TaskRepository taskRepository;
    private final TaskStateMachine taskStateMachine;
    private final TaskDispatcher taskDispatcher;
    private final com.scheduler.shared.metrics.TaskMetrics taskMetrics;

    public LeaseExpiryRecoverySweep(TaskRepository taskRepository,
                                   TaskStateMachine taskStateMachine,
                                   TaskDispatcher taskDispatcher,
                                   com.scheduler.shared.metrics.TaskMetrics taskMetrics) {
        this.taskRepository = taskRepository;
        this.taskStateMachine = taskStateMachine;
        this.taskDispatcher = taskDispatcher;
        this.taskMetrics = taskMetrics;
    }

    @Scheduled(fixedDelayString = "${scheduler.lease-recovery-interval:30000}")
    public void sweepExpiredLeaseTasks() {
        try {
            List<Task> recoveredTasks = executeSweep();
            if (!recoveredTasks.isEmpty()) {
                log.info("Recovered {} expired RUNNING tasks back to QUEUED and re-published to RabbitMQ", recoveredTasks.size());
            }
        } catch (Exception e) {
            log.error("Error occurred during lease-expiry recovery sweep: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public List<Task> executeSweep() {
        List<Task> expiredTasks = taskRepository.findExpiredLeaseTasks();
        if (expiredTasks.isEmpty()) {
            return List.of();
        }

        for (Task task : expiredTasks) {
            taskStateMachine.assertTransitionAllowed(task.getId(), task.getStatus(), TaskStatus.QUEUED);
            task.setStatus(TaskStatus.QUEUED);
            task.setWorkerId(null);
            task.setLeaseId(null);
            task.setLeaseExpiresAt(null);

            taskMetrics.incrementLeaseRecoveries();
            try (var idC = org.slf4j.MDC.putCloseable("task_id", task.getId().toString());
                 var eventC = org.slf4j.MDC.putCloseable("event", "lease_expired_recovery")) {
                log.info("Lease expired for RUNNING task; reset to QUEUED and recovering");
            }
        }

        List<Task> saved = taskRepository.saveAll(expiredTasks);

        // Re-dispatch recovered QUEUED tasks to RabbitMQ
        for (Task task : saved) {
            taskDispatcher.dispatch(task);
        }

        return saved;
    }
}
