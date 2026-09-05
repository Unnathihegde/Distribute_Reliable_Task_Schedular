package com.scheduler.worker.integration;

import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskAttemptRepository;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.worker.execution.TaskExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Worker Service — Recurring Task Integration Tests")
class RecurringTaskIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskAttemptRepository taskAttemptRepository;

    @Autowired
    private TaskExecutionService taskExecutionService;

    @BeforeEach
    void setUp() {
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
    }

    @Test
    @DisplayName("Recurring task completion creates a new child task in SCHEDULED status linked via parent_task_id")
    void recurringTaskCompletion_createsChildTaskWithParentLink() {
        Task parentTask = taskRepository.save(Task.builder()
                .taskType("DEMO")
                .payload("{\"recurring\":true}")
                .priority(Priority.HIGH)
                .status(TaskStatus.QUEUED)
                .cronExpression("0 0 12 * * ?") // Daily noon
                .recurrenceEnabled(true)
                .build());

        boolean processed = taskExecutionService.processTask(parentTask.getId());
        assertThat(processed).isTrue();

        // 1. Verify parent task is marked SUCCESS
        Task updatedParent = taskRepository.findById(parentTask.getId()).orElseThrow();
        assertThat(updatedParent.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(updatedParent.getCompletedAt()).isNotNull();

        // 2. Verify child task was created
        List<Task> allTasks = taskRepository.findAll();
        assertThat(allTasks).hasSize(2);

        Task childTask = allTasks.stream()
                .filter(t -> !t.getId().equals(parentTask.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(childTask.getStatus()).isEqualTo(TaskStatus.SCHEDULED);
        assertThat(childTask.getParentTask()).isNotNull();
        assertThat(childTask.getParentTask().getId()).isEqualTo(parentTask.getId());
        assertThat(childTask.getCronExpression()).isEqualTo("0 0 12 * * ?");
        assertThat(childTask.isRecurrenceEnabled()).isTrue();
        assertThat(childTask.getScheduledAt()).isAfter(parentTask.getScheduledAt());
    }
}
