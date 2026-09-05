package com.scheduler.api.integration;

import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Task API Advanced Integration Tests — Retry & Soft-Delete")
class TaskApiAdvancedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private com.scheduler.shared.repository.TaskAttemptRepository taskAttemptRepository;

    @BeforeEach
    void setUp() {
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /tasks/{id}/retry resets a DEAD_LETTER task back to SCHEDULED with attempt_count = 0")
    void retryDeadLetterTaskSuccess() throws Exception {
        Task task = Task.builder()
                .taskType("EMAIL")
                .payload("{\"to\":\"retry@example.com\"}")
                .priority(Priority.HIGH)
                .status(TaskStatus.DEAD_LETTER)
                .maxAttempts(3)
                .build();
        task.setAttemptCount(3);
        task.setLastError("Fatal error");
        task.setCompletedAt(Instant.now().minusSeconds(60));
        task = taskRepository.save(task);

        mockMvc.perform(post("/api/v1/tasks/{id}/retry", task.getId())
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(task.getId().toString()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.lastError").doesNotExist());

        Task dbTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(dbTask.getStatus()).isEqualTo(TaskStatus.SCHEDULED);
        assertThat(dbTask.getAttemptCount()).isEqualTo(0);
        assertThat(dbTask.getLastError()).isNull();
        assertThat(dbTask.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("POST /tasks/{id}/retry returns 409 Conflict if task is not in DEAD_LETTER status")
    void retryNonDeadLetterTaskConflict() throws Exception {
        Task task = taskRepository.save(Task.builder()
                .taskType("DEMO")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.SUCCESS)
                .build());

        mockMvc.perform(post("/api/v1/tasks/{id}/retry", task.getId())
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /tasks/{id}/retry returns 404 Not Found for non-existent task")
    void retryNonExistentTaskNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/{id}/retry", UUID.randomUUID())
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /tasks/{id} soft-deletes a task, hiding it from GET endpoints")
    void softDeleteTaskSuccess() throws Exception {
        Task task = taskRepository.save(Task.builder()
                .taskType("HTTP")
                .payload("{\"url\":\"http://example.com\"}")
                .priority(Priority.LOW)
                .status(TaskStatus.SCHEDULED)
                .build());

        // 1. Soft-delete task
        mockMvc.perform(delete("/api/v1/tasks/{id}", task.getId())
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isNoContent());

        // Verify deleted_at set in DB
        Task dbTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(dbTask.getDeletedAt()).isNotNull();

        // 2. GET /tasks/{id} returns 404
        mockMvc.perform(get("/api/v1/tasks/{id}", task.getId())
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isNotFound());

        // 3. GET /tasks list excludes soft-deleted task
        mockMvc.perform(get("/api/v1/tasks")
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        // 4. Cancel soft-deleted task returns 404
        mockMvc.perform(post("/api/v1/tasks/{id}/cancel", task.getId())
                        .header("X-API-Key", "test-api-key"))
                .andExpect(status().isNotFound());
    }
}
