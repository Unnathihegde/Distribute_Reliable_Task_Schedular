package com.scheduler.scheduler.recovery;

import com.scheduler.scheduler.config.SchedulerProperties;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.shared.statemachine.TaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Background recovery sweep for tasks stuck in {@code QUEUED} status past the staleness threshold.
 *
 * <p>Per Section 15 of the blueprint, handles cases where a task was committed as {@code QUEUED}
 * but the RabbitMQ publish failed or the message was lost. Resetting the task back to {@code SCHEDULED}
 * allows the normal poll -> claim -> dispatch cycle to re-claim and re-dispatch it automatically.</p>
 */
@Component
public class StaleQueuedRecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(StaleQueuedRecoverySweep.class);

    private final TaskRepository taskRepository;
    private final TaskStateMachine taskStateMachine;
    private final SchedulerProperties schedulerProperties;

    public StaleQueuedRecoverySweep(TaskRepository taskRepository,
                                   TaskStateMachine taskStateMachine,
                                   SchedulerProperties schedulerProperties) {
        this.taskRepository = taskRepository;
        this.taskStateMachine = taskStateMachine;
        this.schedulerProperties = schedulerProperties;
    }

    @Scheduled(fixedDelayString = "${scheduler.stale-queued-interval:60000}")
    public void sweepStaleQueuedTasks() {
        try {
            int count = executeSweep();
            if (count > 0) {
                log.info("Reset {} stale QUEUED tasks back to SCHEDULED", count);
            }
        } catch (Exception e) {
            log.error("Error occurred during stale-QUEUED recovery sweep: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public int executeSweep() {
        long thresholdMinutes = schedulerProperties.getStaleQueuedThresholdMinutes();
        Instant threshold = Instant.now().minus(Duration.ofMinutes(thresholdMinutes));

        List<Task> staleTasks = taskRepository.findStaleQueuedTasks(threshold);
        if (staleTasks.isEmpty()) {
            return 0;
        }

        for (Task task : staleTasks) {
            taskStateMachine.assertTransitionAllowed(task.getId(), task.getStatus(), TaskStatus.SCHEDULED);
            task.setStatus(TaskStatus.SCHEDULED);
        }

        taskRepository.saveAll(staleTasks);
        return staleTasks.size();
    }
}
