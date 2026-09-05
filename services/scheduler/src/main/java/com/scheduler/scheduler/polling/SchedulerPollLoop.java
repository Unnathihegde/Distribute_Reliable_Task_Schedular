package com.scheduler.scheduler.polling;

import com.scheduler.scheduler.config.SchedulerProperties;
import com.scheduler.scheduler.dispatch.TaskDispatcher;
import com.scheduler.shared.domain.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Main polling loop driving task dispatch.
 *
 * <p>Implements the <strong>commit-then-publish</strong> strategy:
 * <ol>
 *   <li>{@link TaskClaimService#claimBatch(int)} claims tasks in a DB transaction (SCHEDULED -> QUEUED).</li>
 *   <li>After the transaction commits, tasks are dispatched to RabbitMQ via {@link TaskDispatcher}.</li>
 * </ol>
 * </p>
 */
@Component
public class SchedulerPollLoop {

    private static final Logger log = LoggerFactory.getLogger(SchedulerPollLoop.class);

    private final TaskClaimService taskClaimService;
    private final TaskDispatcher taskDispatcher;
    private final SchedulerProperties properties;
    private final com.scheduler.shared.metrics.TaskMetrics taskMetrics;

    public SchedulerPollLoop(TaskClaimService taskClaimService,
                             TaskDispatcher taskDispatcher,
                             SchedulerProperties properties,
                             com.scheduler.shared.metrics.TaskMetrics taskMetrics) {
        this.taskClaimService = taskClaimService;
        this.taskDispatcher = taskDispatcher;
        this.properties = properties;
        this.taskMetrics = taskMetrics;
    }

    @Scheduled(fixedDelayString = "${scheduler.poll-interval:5000}")
    public void pollAndDispatch() {
        try {
            List<Task> claimedTasks = taskClaimService.claimBatch(properties.getBatchSize());
            if (claimedTasks.isEmpty()) {
                return;
            }

            log.info("Claimed {} tasks from database, publishing to RabbitMQ", claimedTasks.size());

            for (Task task : claimedTasks) {
                taskMetrics.incrementSchedulerClaims();
                try (var idC = org.slf4j.MDC.putCloseable("task_id", task.getId().toString());
                     var eventC = org.slf4j.MDC.putCloseable("event", "task_claimed")) {
                    log.info("Task claimed from database");
                }

                try {
                    taskDispatcher.dispatch(task);
                } catch (Exception e) {
                    log.error("Failed to publish task {} to RabbitMQ: {}", task.getId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error occurred during scheduler poll loop execution: {}", e.getMessage(), e);
        }
    }
}
