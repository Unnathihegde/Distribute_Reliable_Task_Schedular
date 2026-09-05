package com.scheduler.scheduler.recovery;

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
 * Background recovery sweep for tasks in {@code RETRY_WAIT} status whose
 * retry backoff delay has expired.
 *
 * <p>Transitions due tasks back to {@code SCHEDULED} state, making them
 * available for pickup by the main scheduler poll loop.</p>
 */
@Component
public class RetryWaitRecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(RetryWaitRecoverySweep.class);

    private final TaskRepository taskRepository;
    private final TaskStateMachine taskStateMachine;

    public RetryWaitRecoverySweep(TaskRepository taskRepository, TaskStateMachine taskStateMachine) {
        this.taskRepository = taskRepository;
        this.taskStateMachine = taskStateMachine;
    }

    @Scheduled(fixedDelayString = "${scheduler.retry-sweep-interval:10000}")
    public void sweepDueRetryWaitTasks() {
        try {
            int count = executeSweep();
            if (count > 0) {
                log.info("Recovered {} RETRY_WAIT tasks back to SCHEDULED", count);
            }
        } catch (Exception e) {
            log.error("Error occurred during RETRY_WAIT recovery sweep: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public int executeSweep() {
        List<Task> dueTasks = taskRepository.findRetryWaitTasksDue();
        if (dueTasks.isEmpty()) {
            return 0;
        }

        for (Task task : dueTasks) {
            taskStateMachine.assertTransitionAllowed(task.getId(), task.getStatus(), TaskStatus.SCHEDULED);
            task.setStatus(TaskStatus.SCHEDULED);
        }

        taskRepository.saveAll(dueTasks);
        return dueTasks.size();
    }
}
