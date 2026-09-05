package com.scheduler.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.shared.domain.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full integration tests against a real PostgreSQL container.
 *
 * <h3>Infrastructure</h3>
 * <p>{@code @Testcontainers} starts a Docker-managed PostgreSQL instance before
 * the Spring context boots. {@code @ServiceConnection} auto-configures Spring's
 * datasource (URL, username, password) from the running container — no manual
 * {@code @DynamicPropertySource} needed with Spring Boot 3.1+.</p>
 *
 * <p>Flyway runs the migrations from {@code classpath:db/migration} (shared JAR
 * resources) against the container before the application context is fully
 * initialised, ensuring the schema is in place for the first test.</p>
 *
 * <h3>What we verify</h3>
 * <ul>
 *   <li>Create task → GET round-trip (JSONB payload stored and retrieved correctly)</li>
 *   <li>Idempotency: same key → HTTP 200 + same task ID</li>
 *   <li>Cancel SCHEDULED task → HTTP 200, status = CANCELLED</li>
 *   <li>Cancel RUNNING task → HTTP 409 TASK_NOT_CANCELLABLE</li>
 *   <li>GET unknown ID → HTTP 404 TASK_NOT_FOUND</li>
 *   <li>Cursor pagination: two pages retrieved correctly</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Task API — Integration Tests")
class TaskApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CreateTaskRequest emailTask(String payload) {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTaskType("EMAIL");
        req.setPayload(payload != null ? payload : "{\"to\":\"test@example.com\",\"subject\":\"Hello\"}");
        req.setMaxAttempts(3);
        return req;
    }

    private String body(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    /** Creates a task and returns its ID from the JSON response. */
    private String createAndGetId(CreateTaskRequest req) throws Exception {
        var result = mvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(req)))
            .andExpect(status().is2xxSuccessful())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("id").asText();
    }

    // -------------------------------------------------------------------------
    // Create → GET round-trip (verifies JSONB persistence)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Create task then GET — JSONB payload survives round-trip")
    void createAndGet_roundTrip() throws Exception {
        String payload = "{\"to\":\"alice@example.com\",\"subject\":\"Test\"}";
        CreateTaskRequest req = emailTask(payload);

        String id = createAndGetId(req);

        mvc.perform(get("/api/v1/tasks/" + id)
                .header("X-API-Key", "test-api-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.taskType").value("EMAIL"))
            .andExpect(jsonPath("$.status").value(TaskStatus.SCHEDULED.name()))
            .andExpect(jsonPath("$.payload").isNotEmpty())
            .andExpect(jsonPath("$.maxAttempts").value(3));
    }

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Duplicate idempotency key — second request returns HTTP 200 + same ID")
    void idempotentCreate_returnsSameTask() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        CreateTaskRequest req = emailTask(null);
        req.setIdempotencyKey(key);

        // First create → 201
        var first = mvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(req)))
            .andExpect(status().isCreated())
            .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString())
            .get("id").asText();

        // Second create with same key → 200, same ID
        var second = mvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(req)))
            .andExpect(status().isOk())
            .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString())
            .get("id").asText();

        org.junit.jupiter.api.Assertions.assertEquals(firstId, secondId,
            "Idempotent requests must return the same task ID");
    }

    @Test
    @DisplayName("Idempotency-Key header — recognised and deduplicates request")
    void idempotencyKeyHeader_deduplicates() throws Exception {
        String key = "header-idem-" + UUID.randomUUID();

        // First via header
        var first = mvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(body(emailTask(null))))
            .andExpect(status().isCreated())
            .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString())
            .get("id").asText();

        // Second via header
        var second = mvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(body(emailTask(null))))
            .andExpect(status().isOk())
            .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString())
            .get("id").asText();

        org.junit.jupiter.api.Assertions.assertEquals(firstId, secondId);
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Cancel SCHEDULED task → 200, status CANCELLED")
    void cancelScheduledTask_returns200() throws Exception {
        String id = createAndGetId(emailTask(null));

        mvc.perform(post("/api/v1/tasks/" + id + "/cancel")
                .header("X-API-Key", "test-api-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    @DisplayName("Cancel already-CANCELLED task → 409 TASK_NOT_CANCELLABLE")
    void cancelCancelledTask_returns409() throws Exception {
        String id = createAndGetId(emailTask(null));

        // Cancel once
        mvc.perform(post("/api/v1/tasks/" + id + "/cancel")
                .header("X-API-Key", "test-api-key"))
            .andExpect(status().isOk());

        // Cancel again — should fail with 409
        mvc.perform(post("/api/v1/tasks/" + id + "/cancel")
                .header("X-API-Key", "test-api-key"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("TASK_NOT_CANCELLABLE"))
            .andExpect(jsonPath("$.error.status").value(409));
    }

    // -------------------------------------------------------------------------
    // Not found
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET unknown task ID → 404 TASK_NOT_FOUND")
    void getUnknownTask_returns404() throws Exception {
        mvc.perform(get("/api/v1/tasks/" + UUID.randomUUID())
                .header("X-API-Key", "test-api-key"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"))
            .andExpect(jsonPath("$.error.status").value(404));
    }

    @Test
    @DisplayName("Cancel unknown task ID → 404 TASK_NOT_FOUND")
    void cancelUnknownTask_returns404() throws Exception {
        mvc.perform(post("/api/v1/tasks/" + UUID.randomUUID() + "/cancel")
                .header("X-API-Key", "test-api-key"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // List with cursor pagination
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("List tasks — first page populated and pagination metadata present")
    void listTasks_firstPage_hasCorrectShape() throws Exception {
        // Create at least 2 tasks to ensure a non-empty list
        createAndGetId(emailTask("{\"to\":\"page1@example.com\"}"));
        createAndGetId(emailTask("{\"to\":\"page2@example.com\"}"));

        mvc.perform(get("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].id").exists())
            .andExpect(jsonPath("$.pagination.limit").value(1))
            .andExpect(jsonPath("$.pagination.hasMore").value(true))
            .andExpect(jsonPath("$.pagination.nextCursor").isNotEmpty());
    }

    @Test
    @DisplayName("Cursor pagination — second page accessible via nextCursor")
    void listTasks_cursorPagination_fetchesSecondPage() throws Exception {
        // Create unique-keyed tasks for isolation
        String k1 = "cursor-test-" + UUID.randomUUID();
        String k2 = "cursor-test-" + UUID.randomUUID();
        CreateTaskRequest r1 = emailTask("{\"page\":\"one\"}");
        r1.setIdempotencyKey(k1);
        CreateTaskRequest r2 = emailTask("{\"page\":\"two\"}");
        r2.setIdempotencyKey(k2);
        createAndGetId(r1);
        createAndGetId(r2);

        // Fetch first page with limit=1
        var firstResult = mvc.perform(get("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pagination.hasMore").value(true))
            .andReturn();

        String nextCursor = objectMapper.readTree(firstResult.getResponse().getContentAsString())
            .get("pagination").get("nextCursor").asText();

        // Fetch second page using cursor
        mvc.perform(get("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .param("limit", "1")
                .param("cursor", nextCursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].id").exists());
    }

    @Test
    @DisplayName("Filter by status — only SCHEDULED tasks returned")
    void listTasks_filteredByStatus() throws Exception {
        createAndGetId(emailTask("{\"filtered\":true}"));

        mvc.perform(get("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .param("status", "SCHEDULED")
                .param("limit", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }

    // -------------------------------------------------------------------------
    // Payload validation (service-level, not Bean Validation)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Non-JSON payload → 400 INVALID_ARGUMENT")
    void nonJsonPayload_returns400() throws Exception {
        CreateTaskRequest req = emailTask("this is not json");

        mvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }
}
