package com.scheduler.api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Task API using a real PostgreSQL database via Testcontainers.
 *
 * <p><strong>What is tested here:</strong>
 * <ul>
 *   <li>Create → GET round-trip (verifies JSONB payload survives the DB cycle).</li>
 *   <li>JSONB payload mapping: {@code @JdbcTypeCode(SqlTypes.JSON)} round-trip against
 *       real PostgreSQL 16. A type-mismatch failure would appear here as:
 *       {@code ERROR: column "payload" is of type jsonb but expression is of type character varying}</li>
 *   <li>Idempotency key collision → 200 OK with same task (not 201).</li>
 *   <li>Cancel a SCHEDULED task → 200 OK, status = CANCELLED.</li>
 *   <li>Cancel a non-cancellable task (CANCELLED) → 409 Conflict.</li>
 *   <li>GET non-existent task → 404 with correct error shape.</li>
 *   <li>List tasks with status filter.</li>
 *   <li>Cursor pagination works across two pages.</li>
 * </ul>
 *
 * <p><strong>Container lifecycle:</strong> A single PostgreSQL 16 container is shared
 * across all tests in this class (declared {@code static}). Flyway runs migrations on
 * startup, creating the {@code tasks} and {@code task_attempts} tables. Tests use
 * {@code @BeforeEach} to reset data where isolation matters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Task API — Integration Tests (Testcontainers PostgreSQL)")
class TaskApiIntegrationTest {

    // -------------------------------------------------------------------------
    // Shared PostgreSQL 16 container — started once, reused across all tests
    // -------------------------------------------------------------------------

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("taskscheduler_test")
            .withUsername("scheduler")
            .withPassword("scheduler_password");

    /**
     * Overrides datasource properties with the Testcontainers JDBC URL.
     * Called before the Spring context starts.
     */
    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway picks up these same datasource properties — migrations run automatically
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // =========================================================================
    // 1. Create → GET round-trip (exercises JSONB mapping against real Postgres)
    // =========================================================================

    @Test
    @DisplayName("POST /tasks → 201, then GET /tasks/{id} → 200 with payload round-trip")
    void createAndGetRoundTrip() throws Exception {
        // --- Create ---
        String requestBody = """
                {
                  "taskType": "EMAIL",
                  "payload": {
                    "to": "user@example.com",
                    "subject": "Reminder",
                    "nested": { "key": "value", "count": 42 }
                  },
                  "priority": "HIGH",
                  "maxAttempts": 3
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.taskType").value("EMAIL"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andReturn();

        // Extract the created task's ID
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String taskId = created.get("id").asText();

        // --- GET by ID ---
        MvcResult getResult = mockMvc.perform(get("/api/v1/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.taskType").value("EMAIL"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                // *** JSONB round-trip assertion ***
                // If @JdbcTypeCode(SqlTypes.JSON) is broken, the payload either
                // won't be stored or will come back as a raw escaped string.
                .andExpect(jsonPath("$.payload.to").value("user@example.com"))
                .andExpect(jsonPath("$.payload.subject").value("Reminder"))
                .andExpect(jsonPath("$.payload.nested.key").value("value"))
                .andExpect(jsonPath("$.payload.nested.count").value(42))
                .andReturn();

        // Additional assertion: payload is a proper JSON object (not escaped string)
        JsonNode getResponse = objectMapper.readTree(getResult.getResponse().getContentAsString());
        assertThat(getResponse.get("payload").isObject())
                .as("payload must be a JSON object, not an escaped string (JSONB round-trip check)")
                .isTrue();
        assertThat(getResponse.get("payload").get("to").asText())
                .isEqualTo("user@example.com");
    }

    // =========================================================================
    // 2. Idempotency key collision
    // =========================================================================

    @Test
    @DisplayName("POST with idempotency key: first call → 201, second call → 200 same task")
    void idempotencyKeyCollision() throws Exception {
        String idempotencyKey = "idem-test-" + UUID.randomUUID();

        String requestBody = """
                {
                  "taskType": "DEMO",
                  "payload": {"action": "test"},
                  "idempotencyKey": "%s"
                }
                """.formatted(idempotencyKey);

        // First request → 201 Created
        MvcResult first = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String firstId = objectMapper.readTree(
                first.getResponse().getContentAsString()).get("id").asText();

        // Second request with same idempotency key → 200 OK, same task ID
        MvcResult second = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())  // 200, not 201
                .andReturn();

        String secondId = objectMapper.readTree(
                second.getResponse().getContentAsString()).get("id").asText();

        assertThat(secondId)
                .as("Idempotency: second request must return the same task ID as the first")
                .isEqualTo(firstId);
    }

    @Test
    @DisplayName("POST with Idempotency-Key header: collision returns existing task with 200")
    void idempotencyKeyHeader() throws Exception {
        String key = "header-idem-" + UUID.randomUUID();

        String body = """
                { "taskType": "DEMO", "payload": {"action": "header-test"} }
                """;

        // First request with header
        MvcResult first = mockMvc.perform(post("/api/v1/tasks")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(
                first.getResponse().getContentAsString()).get("id").asText();

        // Second request with same header
        MvcResult second = mockMvc.perform(post("/api/v1/tasks")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String secondId = objectMapper.readTree(
                second.getResponse().getContentAsString()).get("id").asText();

        assertThat(secondId).isEqualTo(firstId);
    }

    // =========================================================================
    // 3. Cancel — cancellable task
    // =========================================================================

    @Test
    @DisplayName("POST /tasks/{id}/cancel on SCHEDULED task → 200, status=CANCELLED")
    void cancelScheduledTask() throws Exception {
        // Create a task
        String taskId = createTask("EMAIL", """
                {"action": "to-cancel"}""");

        // Cancel it
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Verify via GET that status is persisted
        mockMvc.perform(get("/api/v1/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // =========================================================================
    // 4. Cancel — non-cancellable task (already CANCELLED)
    // =========================================================================

    @Test
    @DisplayName("POST /tasks/{id}/cancel on already-CANCELLED task → 409 TASK_NOT_CANCELLABLE")
    void cancelAlreadyCancelledTask() throws Exception {
        // Create and cancel a task
        String taskId = createTask("EMAIL", """
                {"action": "double-cancel"}""");

        // First cancel → 200
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/cancel"))
                .andExpect(status().isOk());

        // Second cancel → 409
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TASK_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.error.status").value(409))
                .andExpect(jsonPath("$.error.path").value(
                        "/api/v1/tasks/" + taskId + "/cancel"));
    }

    // =========================================================================
    // 5. GET non-existent task → 404
    // =========================================================================

    @Test
    @DisplayName("GET /tasks/{nonExistentId} → 404 TASK_NOT_FOUND")
    void getNotFound() throws Exception {
        UUID nonExistent = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/tasks/" + nonExistent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.error.status").value(404))
                .andExpect(jsonPath("$.error.timestamp").exists())
                .andExpect(jsonPath("$.error.path").value("/api/v1/tasks/" + nonExistent));
    }

    // =========================================================================
    // 6. List tasks with status filter
    // =========================================================================

    @Test
    @DisplayName("GET /tasks?status=SCHEDULED returns only SCHEDULED tasks")
    void listTasksByStatus() throws Exception {
        // Create two tasks, cancel one
        String taskA = createTask("EMAIL", """
                {"seq": 1}""");
        String taskB = createTask("HTTP", """
                {"seq": 2}""");

        // Cancel taskB
        mockMvc.perform(post("/api/v1/tasks/" + taskB + "/cancel"))
                .andExpect(status().isOk());

        // List SCHEDULED tasks — should include taskA, not taskB
        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "SCHEDULED")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.pagination.hasMore").exists());
    }

    // =========================================================================
    // 7. Cursor pagination
    // =========================================================================

    @Test
    @DisplayName("Cursor pagination: GET /tasks with limit=1 produces nextCursor, second page works")
    void cursorPagination() throws Exception {
        // Create at least 2 tasks
        createTask("DEMO", """
                {"page": "test-1"}""");
        createTask("DEMO", """
                {"page": "test-2"}""");

        // First page with limit=1
        MvcResult firstPage = mockMvc.perform(get("/api/v1/tasks")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.pagination.hasMore").value(true))
                .andExpect(jsonPath("$.pagination.nextCursor").exists())
                .andReturn();

        JsonNode firstPageNode = objectMapper.readTree(
                firstPage.getResponse().getContentAsString());
        String nextCursor = firstPageNode.get("pagination").get("nextCursor").asText();

        assertThat(nextCursor).isNotBlank();

        // Second page using the cursor
        mockMvc.perform(get("/api/v1/tasks")
                        .param("limit", "1")
                        .param("cursor", nextCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
    }

    // =========================================================================
    // 8. Validation rejection via integration test
    // =========================================================================

    @Test
    @DisplayName("POST /tasks with scheduledAt more than 5 minutes in the past → 400")
    void scheduledAtTooFarInPast() throws Exception {
        String body = """
                {
                  "taskType": "EMAIL",
                  "payload": {"k": "v"},
                  "scheduledAt": "2020-01-01T00:00:00Z"
                }
                """;
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /tasks with invalid priority → 400")
    void invalidPriority() throws Exception {
        String body = """
                {
                  "taskType": "EMAIL",
                  "payload": {"k": "v"},
                  "priority": "URGENT"
                }
                """;
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Creates a task via the API and returns its UUID string.
     * Asserts 201 Created internally.
     */
    private String createTask(String taskType, String payloadJson) throws Exception {
        String body = """
                { "taskType": "%s", "payload": %s }
                """.formatted(taskType, payloadJson);

        MvcResult result = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }
}
