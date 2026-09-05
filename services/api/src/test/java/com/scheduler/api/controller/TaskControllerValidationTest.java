package com.scheduler.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.api.exception.GlobalExceptionHandler;
import com.scheduler.api.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link TaskController} request validation.
 *
 * <p>{@code @WebMvcTest} starts only the web layer (no DB, no service beans).
 * {@link TaskService} is mocked so tests focus exclusively on what the controller
 * validates and rejects before delegating to the service.</p>
 *
 * <h3>What we test</h3>
 * <ul>
 *   <li>Missing required fields → 400 VALIDATION_ERROR</li>
 *   <li>Invalid taskType (not in whitelist) → 400 VALIDATION_ERROR</li>
 *   <li>Invalid priority (unrecognised enum) → 400 INVALID_REQUEST_BODY</li>
 *   <li>maxAttempts out of range → 400 VALIDATION_ERROR</li>
 *   <li>Invalid Quartz cron expression → 400 VALIDATION_ERROR</li>
 *   <li>Malformed JSON body → 400 INVALID_REQUEST_BODY</li>
 * </ul>
 */
@WebMvcTest(TaskController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("TaskController — Request Validation")
class TaskControllerValidationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private String json(CreateTaskRequest req) throws Exception {
        return objectMapper.writeValueAsString(req);
    }

    private CreateTaskRequest validRequest() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTaskType("EMAIL");
        req.setPayload("{\"to\":\"test@example.com\"}");
        req.setMaxAttempts(3);
        return req;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Missing taskType → 400 VALIDATION_ERROR")
    void missingTaskType_returns400() throws Exception {
        CreateTaskRequest req = validRequest();
        req.setTaskType(null);

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.status").value(400));
    }

    @Test
    @DisplayName("Unknown taskType → 400 VALIDATION_ERROR")
    void unknownTaskType_returns400() throws Exception {
        CreateTaskRequest req = validRequest();
        req.setTaskType("FOOBAR");

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.message").value(
                org.hamcrest.Matchers.containsString("taskType")));
    }

    @Test
    @DisplayName("Missing payload → 400 VALIDATION_ERROR")
    void missingPayload_returns400() throws Exception {
        CreateTaskRequest req = validRequest();
        req.setPayload(null);

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("maxAttempts below 1 → 400 VALIDATION_ERROR")
    void maxAttemptsTooLow_returns400() throws Exception {
        CreateTaskRequest req = validRequest();
        req.setMaxAttempts(0);

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("maxAttempts above 20 → 400 VALIDATION_ERROR")
    void maxAttemptsTooHigh_returns400() throws Exception {
        CreateTaskRequest req = validRequest();
        req.setMaxAttempts(21);

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Invalid cron expression → 400 VALIDATION_ERROR")
    void invalidCronExpression_returns400() throws Exception {
        CreateTaskRequest req = validRequest();
        req.setCronExpression("not-a-cron");

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.message").value(
                org.hamcrest.Matchers.containsString("cronExpression")));
    }

    @Test
    @DisplayName("Invalid priority enum value → 400 INVALID_REQUEST_BODY")
    void invalidPriority_returns400() throws Exception {
        // Jackson throws HttpMessageNotReadableException for unrecognised enum values
        String body = """
            {
              "taskType": "EMAIL",
              "payload": "{\\"to\\":\\"x@y.com\\"}",
              "priority": "URGENT"
            }
            """;

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("Completely malformed JSON body → 400 INVALID_REQUEST_BODY")
    void malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("this is not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("Valid DEMO task type passes validation")
    void validDemoType_passesBeanValidation() throws Exception {
        CreateTaskRequest req = validRequest();
        req.setTaskType("DEMO");

        // Service is mocked — returning null here means we won't get a 2xx from TaskService,
        // but we also won't get a 4xx from the controller layer validation.
        // We verify the controller doesn't reject it with a 400.
        org.mockito.Mockito.when(taskService.createTask(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new RuntimeException("downstream-mock"));

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)))
            .andExpect(status().isInternalServerError()) // from GlobalExceptionHandler catch-all
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        // If we got 400, validation rejected it — this confirms validation passes
    }

    @Test
    @DisplayName("Valid Quartz cron expression passes validation")
    void validCron_passesBeanValidation() throws Exception {
        CreateTaskRequest req = validRequest();
        req.setCronExpression("0 0 12 * * ?");

        org.mockito.Mockito.when(taskService.createTask(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new RuntimeException("downstream-mock"));

        mvc.perform(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("GET /api/v1/tasks/{id} with non-UUID path variable → 400")
    void getNonUuidId_returns400() throws Exception {
        mvc.perform(get("/api/v1/tasks/not-a-uuid"))
            .andExpect(status().isBadRequest());
    }
}
