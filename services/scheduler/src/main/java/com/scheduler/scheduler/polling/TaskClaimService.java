package com.scheduler.scheduler.polling;

import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.shared.statemachine.TaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes DB claim transactions for due SCHEDULED tasks.
 *
 * <p>Uses {@code FOR UPDATE SKIP LOCKED} via {@link TaskRepository#claimScheduledTasks(int)}
 * to atomically lock and claim tasks without blocking concurrent scheduler instances.</p>
 */
@Service
public class TaskClaimService {

    private static final Logger log = LoggerFactory.getLogger(TaskClaimService.class);

    private final TaskRepository taskRepository;
    private final TaskStateMachine taskStateMachine;

    public TaskClaimService(TaskRepository taskRepository, TaskStateMachine taskStateMachine) {
        this.taskRepository = taskRepository;
        this.taskStateMachine = taskStateMachine;
    }

    /**
     * Atomically claims up to {@code batchSize} due tasks, transitioning their status
     * from {@code SCHEDULED} to {@code QUEUED}.
     *
     * @param batchSize max tasks to claim in this transaction
     * @return list of claimed and updated tasks
     */
    @Transactional
    public List<Task> claimBatch(int batchSize) {
        List<Task> dueTasks = taskRepository.claimScheduledTasks(batchSize);
        if (dueTasks.isEmpty()) {
            return List.of();
        }

        List<Task> claimedTasks = new ArrayList<>(dueTasks.size());
        for (Task task : dueTasks) {
            taskStateMachine.assertTransitionAllowed(task.getId(), task.getStatus(), TaskStatus.QUEUED);
            task.setStatus(TaskStatus.QUEUED);
            claimedTasks.add(task);
        }

        List<Task> savedTasks = taskRepository.saveAll(claimedTasks);
        log.debug("Successfully claimed and updated {} tasks to QUEUED", savedTasks.size());
        return savedTasks;
    }
}
