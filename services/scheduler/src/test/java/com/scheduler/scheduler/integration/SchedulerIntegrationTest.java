package com.scheduler.scheduler.integration;

import com.scheduler.scheduler.config.RabbitMqConfig;
import com.scheduler.scheduler.dispatch.TaskDispatchMessage;
import com.scheduler.scheduler.polling.SchedulerPollLoop;
import com.scheduler.scheduler.polling.TaskClaimService;
import com.scheduler.scheduler.recovery.RetryWaitRecoverySweep;
import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
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
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Scheduler Service — Integration Tests")
class SchedulerIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskClaimService taskClaimService;

    @Autowired
    private SchedulerPollLoop pollLoop;

    @Autowired
    private RetryWaitRecoverySweep recoverySweep;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TaskAttemptRepository taskAttemptRepository;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void setUp() {
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        rabbitAdmin.purgeQueue(RabbitMqConfig.QUEUE_HIGH);
        rabbitAdmin.purgeQueue(RabbitMqConfig.QUEUE_MEDIUM);
        rabbitAdmin.purgeQueue(RabbitMqConfig.QUEUE_LOW);
        rabbitAdmin.purgeQueue(RabbitMqConfig.QUEUE_DLQ);
    }

    @Test
    @DisplayName("Due task gets claimed to QUEUED and published to RabbitMQ")
    void dueTask_claimedAndPublished() {
        Task task = taskRepository.save(Task.builder()
                .taskType("EMAIL")
                .payload("{\"to\":\"user@example.com\"}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.SCHEDULED)
                .scheduledAt(Instant.now().minusSeconds(60))
                .build());

        pollLoop.pollAndDispatch();

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.QUEUED);

        TaskDispatchMessage message = (TaskDispatchMessage) rabbitTemplate.receiveAndConvert(RabbitMqConfig.QUEUE_MEDIUM, 3000);
        assertThat(message).isNotNull();
        assertThat(message.getTaskId()).isEqualTo(task.getId());
        assertThat(message.getTaskType()).isEqualTo("EMAIL");
        assertThat(message.getPriority()).isEqualTo(Priority.MEDIUM);
    }

    @Test
    @DisplayName("Not-yet-due task is NOT claimed")
    void futureTask_notClaimed() {
        Task task = taskRepository.save(Task.builder()
                .taskType("EMAIL")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.SCHEDULED)
                .scheduledAt(Instant.now().plusSeconds(3600))
                .build());

        pollLoop.pollAndDispatch();

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.SCHEDULED);

        TaskDispatchMessage message = (TaskDispatchMessage) rabbitTemplate.receiveAndConvert(RabbitMqConfig.QUEUE_MEDIUM, 500);
        assertThat(message).isNull();
    }

    @Test
    @DisplayName("CANCELLED task is never claimed")
    void cancelledTask_notClaimed() {
        Task task = taskRepository.save(Task.builder()
                .taskType("EMAIL")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.CANCELLED)
                .scheduledAt(Instant.now().minusSeconds(60))
                .build());

        pollLoop.pollAndDispatch();

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("Priority ordering claims HIGH priority tasks before MEDIUM and LOW")
    void priorityOrdering_claimsHighFirst() {
        Instant past = Instant.now().minusSeconds(60);

        Task low = taskRepository.save(Task.builder()
                .taskType("LOG")
                .payload("{}")
                .priority(Priority.LOW)
                .status(TaskStatus.SCHEDULED)
                .scheduledAt(past)
                .build());

        Task medium = taskRepository.save(Task.builder()
                .taskType("REPORT")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.SCHEDULED)
                .scheduledAt(past)
                .build());

        Task high = taskRepository.save(Task.builder()
                .taskType("ALERT")
                .payload("{}")
                .priority(Priority.HIGH)
                .status(TaskStatus.SCHEDULED)
                .scheduledAt(past)
                .build());

        List<Task> claimedBatch = taskClaimService.claimBatch(10);

        assertThat(claimedBatch).hasSize(3);
        assertThat(claimedBatch.get(0).getId()).isEqualTo(high.getId());
        assertThat(claimedBatch.get(1).getId()).isEqualTo(medium.getId());
        assertThat(claimedBatch.get(2).getId()).isEqualTo(low.getId());
    }

    @Test
    @DisplayName("Retry-wait recovery sweep transitions RETRY_WAIT tasks back to SCHEDULED")
    void retryWaitRecovery_transitionsToScheduled() {
        Task task = Task.builder()
                .taskType("RETRY_JOB")
                .payload("{}")
                .priority(Priority.HIGH)
                .status(TaskStatus.RETRY_WAIT)
                .scheduledAt(Instant.now().minusSeconds(120))
                .build();
        task.setNextRetryAt(Instant.now().minusSeconds(10));
        task = taskRepository.save(task);

        int count = recoverySweep.executeSweep();
        assertThat(count).isEqualTo(1);

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.SCHEDULED);
    }
}
