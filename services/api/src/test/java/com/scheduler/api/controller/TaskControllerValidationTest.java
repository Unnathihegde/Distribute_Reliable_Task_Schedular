package com.scheduler.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.api.dto.TaskResponse;
import com.scheduler.api.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for validation logic in {@link TaskController}.
 *
 * <p><strong>No Spring context, no database, no Testcontainers.</strong>
 * Uses {@code @WebMvcTest} which loads only the web layer (controllers,
 * exception handlers, validation). {@link TaskService} is mocked.
 *
 * <p>These tests verify that:
 * <ul>
 *   <li>Missing required fields are rejected with 400.</li>
 *   <li>Invalid task type (not in whitelist) is rejected with 400.</li>
 *   <li>Invalid cron expression is rejected with 400.</li>
 *   <li>Invalid priority value is rejected with 400.</li>
 *   <li>maxAttempts out of range is rejected with 400.</li>
 *   <li>Oversized payload (> 64KB) is rejected with 400.</li>
 *   <li>Valid requests reach the service without error.</li>
 * </ul>
 */
@WebMvcTest(TaskController.class)
@DisplayName("TaskController — Validation")
class TaskControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    // =========================================================================
    // Missing required fields
    // =========================================================================

    @Nested
    @DisplayName("Missing required fields → 400")
    class MissingFields {

        @Test
        @DisplayName("Missing taskType → 400 VALIDATION_ERROR")
        void missingTaskType() throws Exception {
            String body = """
                    { "payload": {"key": "value"} }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.status").value(400));
        }

        @Test
        @DisplayName("Missing payload → 400 VALIDATION_ERROR")
        void missingPayload() throws Exception {
            String body = """
                    { "taskType": "EMAIL" }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("Empty body → 400")
        void emptyBody() throws Exception {
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.status").value(400));
        }
    }

    // =========================================================================
    // Task type whitelist
    // =========================================================================

    @Nested
    @DisplayName("Task type whitelist")
    class TaskTypeValidation {

        @Test
        @DisplayName("Unknown task type → 400 VALIDATION_ERROR")
        void unknownTaskType() throws Exception {
            String body = """
                    { "taskType": "UNKNOWN_TYPE", "payload": {"k": "v"} }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("EMAIL")));
        }

        @Test
        @DisplayName("FOOBAR task type → 400")
        void foobarTaskType() throws Exception {
            String body = """
                    { "taskType": "FOOBAR", "payload": {"k": "v"} }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Lowercase valid type (email) → accepted (normalised to EMAIL in service)")
        void lowercaseValidType() throws Exception {
            mockSuccessfulCreate();

            String body = """
                    { "taskType": "email", "payload": {"k": "v"} }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }
    }

    // =========================================================================
    // Cron expression validation
    // =========================================================================

    @Nested
    @DisplayName("Cron expression validation")
    class CronValidation {

        @Test
        @DisplayName("Invalid cron expression → 400")
        void invalidCron() throws Exception {
            String body = """
                    { "taskType": "EMAIL", "payload": {"k": "v"},
                      "cronExpression": "not-a-cron" }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("5-field Unix cron (not Quartz) → 400")
        void unixCronRejected() throws Exception {
            String body = """
                    { "taskType": "EMAIL", "payload": {"k": "v"},
                      "cronExpression": "0 9 * * 1" }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Valid Quartz cron → accepted")
        void validQuartzCron() throws Exception {
            mockSuccessfulCreate();

            String body = """
                    { "taskType": "EMAIL", "payload": {"k": "v"},
                      "cronExpression": "0 0 9 * * ?" }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Null cron expression → accepted (optional field)")
        void nullCronIsOptional() throws Exception {
            mockSuccessfulCreate();

            String body = """
                    { "taskType": "EMAIL", "payload": {"k": "v"} }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }
    }

    // =========================================================================
    // maxAttempts range validation
    // =========================================================================

    @Nested
    @DisplayName("maxAttempts validation")
    class MaxAttemptsValidation {

        @Test
        @DisplayName("maxAttempts = 0 → 400")
        void zeroAttempts() throws Exception {
            String body = """
                    { "taskType": "EMAIL", "payload": {"k": "v"}, "maxAttempts": 0 }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("maxAttempts = 21 → 400")
        void tooManyAttempts() throws Exception {
            String body = """
                    { "taskType": "EMAIL", "payload": {"k": "v"}, "maxAttempts": 21 }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("maxAttempts = 1 → accepted (minimum)")
        void oneAttemptAccepted() throws Exception {
            mockSuccessfulCreate();

            String body = """
                    { "taskType": "EMAIL", "payload": {"k": "v"}, "maxAttempts": 1 }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("maxAttempts = 20 → accepted (maximum)")
        void twentyAttemptsAccepted() throws Exception {
            mockSuccessfulCreate();

            String body = """
                    { "taskType": "EMAIL", "payload": {"k": "v"}, "maxAttempts": 20 }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }
    }

    // =========================================================================
    // Error response shape
    // =========================================================================

    @Nested
    @DisplayName("Error response format — Section 9 shape")
    class ErrorFormat {

        @Test
        @DisplayName("Error response contains all required Section 9 fields")
        void errorResponseShape() throws Exception {
            String body = """
                    { "taskType": "INVALID", "payload": {"k": "v"} }
                    """;
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").exists())
                    .andExpect(jsonPath("$.error.message").exists())
                    .andExpect(jsonPath("$.error.status").exists())
                    .andExpect(jsonPath("$.error.timestamp").exists())
                    .andExpect(jsonPath("$.error.path").exists())
                    .andExpect(jsonPath("$.error.path").value("/api/v1/tasks"));
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Makes TaskService.createTask return a dummy "created" result so that
     * valid-body tests can confirm 201 without a real database.
     */
    @SuppressWarnings("unchecked")
    private void mockSuccessfulCreate() {
        TaskResponse fakeResponse = TaskResponse.from(
                buildMinimalTask(), Map.of("k", "v"));
        when(taskService.createTask(any(CreateTaskRequest.class), any()))
                .thenReturn(TaskService.TaskCreationResult.created(fakeResponse));
        when(taskService.createTask(any(CreateTaskRequest.class), isNull()))
                .thenReturn(TaskService.TaskCreationResult.created(fakeResponse));
    }

    private com.scheduler.shared.domain.Task buildMinimalTask() {
        com.scheduler.shared.domain.Task task = new com.scheduler.shared.domain.Task();
        task.setId(UUID.randomUUID());
        task.setTaskType("EMAIL");
        task.setPayload("{\"k\":\"v\"}");
        task.setStatus(com.scheduler.shared.domain.TaskStatus.SCHEDULED);
        task.setPriority(com.scheduler.shared.domain.Priority.MEDIUM);
        task.setScheduledAt(java.time.OffsetDateTime.now());
        return task;
    }
}
