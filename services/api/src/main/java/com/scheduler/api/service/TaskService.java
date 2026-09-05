package com.scheduler.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.api.dto.PagedTaskResponse;
import com.scheduler.api.dto.TaskResponse;
import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.exception.TaskNotFoundException;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.shared.statemachine.TaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for the Task REST API.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Validates business rules that cannot be expressed with Bean Validation
 *       annotations (e.g., payload size, stale scheduledAt).</li>
 *   <li>Implements idempotent task creation with optimistic insert and
 *       race-condition-safe fallback.</li>
 *   <li>Encodes/decodes keyset pagination cursors.</li>
 *   <li>Delegates state transitions to {@link TaskStateMachine}.</li>
 * </ul>
 *
 * <p>This class is kept thin — it orchestrates rather than implements policies
 * that belong in the domain (state machine) or infrastructure (repository).</p>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** Maximum payload size in bytes (65 536 = 64 KiB). */
    private static final int MAX_PAYLOAD_BYTES = 65_536;

    /**
     * Grace window for scheduledAt validation. Tasks with a scheduledAt up to
     * 5 minutes in the past are accepted — this tolerates minor clock skew between
     * client and server without rejecting legitimate near-real-time submissions.
     */
    private static final long SCHEDULED_AT_GRACE_SECONDS = 300;

    private final TaskRepository taskRepository;
    private final TaskStateMachine taskStateMachine;
    private final ObjectMapper objectMapper;
    private final com.scheduler.shared.metrics.TaskMetrics taskMetrics;
    private final io.opentelemetry.api.OpenTelemetry openTelemetry;

    public TaskService(TaskRepository taskRepository,
                       TaskStateMachine taskStateMachine,
                       ObjectMapper objectMapper,
                       com.scheduler.shared.metrics.TaskMetrics taskMetrics,
                       io.opentelemetry.api.OpenTelemetry openTelemetry) {
        this.taskRepository   = taskRepository;
        this.taskStateMachine = taskStateMachine;
        this.objectMapper     = objectMapper;
        this.taskMetrics      = taskMetrics;
        this.openTelemetry    = openTelemetry;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Creates a new task, or returns the existing task if an idempotency key collision
     * is detected.
     *
     * <h3>Idempotency protocol</h3>
     * <ol>
     *   <li>If {@code idempotencyKey} is present, query the DB for an existing task.
     *       If found, return it immediately with HTTP 200 (caller checks via returned flag).</li>
     *   <li>If not found, insert the new task.</li>
     *   <li>If a concurrent request wins the unique-index race and causes a
     *       {@link DataIntegrityViolationException}, catch it, re-query, and return
     *       the winning task. The index {@code idx_tasks_idempotency_key} is the
     *       actual enforcement layer; the pre-check just avoids the exception in the
     *       common case.</li>
     * </ol>
     *
     * @return a pair of (task, isNew): isNew=false means the task already existed
     */
    @Transactional
    public CreateResult createTask(CreateTaskRequest req) {
        validatePayload(req.getPayload());
        validateScheduledAt(req.getScheduledAt());

        String key = req.getIdempotencyKey();
        if (key != null && !key.isBlank()) {
            // Happy-path idempotency check — avoids the unique constraint exception
            var existing = taskRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                log.debug("Idempotency hit for key={}", key);
                return new CreateResult(existing.get(), false);
            }
        }

        java.util.Map<String, String> traceMap = new java.util.HashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator().inject(
                io.opentelemetry.context.Context.current(),
                traceMap,
                java.util.Map::put
        );
        String currentTraceparent = traceMap.get("traceparent");

        Task task = Task.builder()
            .taskType(req.getTaskType().toUpperCase())
            .payload(req.getPayload())
            .priority(req.getPriority() != null ? req.getPriority() : Priority.MEDIUM)
            .scheduledAt(req.getScheduledAt() != null ? req.getScheduledAt() : Instant.now())
            .maxAttempts(req.getMaxAttempts())
            .idempotencyKey(key != null && !key.isBlank() ? key : null)
            .cronExpression(req.getCronExpression())
            .recurrenceEnabled(req.isRecurrenceEnabled())
            .createdBy(req.getCreatedBy())
            .traceparent(currentTraceparent)
            .build();

        try {
            Task saved = taskRepository.save(task);
            io.opentelemetry.api.trace.Span.current().setAttribute("task_id", saved.getId().toString());
            taskMetrics.incrementTasksSubmitted(saved.getTaskType(), saved.getPriority().name());
            try (var idC = org.slf4j.MDC.putCloseable("task_id", saved.getId().toString());
                 var eventC = org.slf4j.MDC.putCloseable("event", "task_created")) {
                log.info("Task created successfully with traceparent={}", currentTraceparent);
            }
            return new CreateResult(saved, true);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request won the unique-index race — re-query and return winner
            if (key != null) {
                return taskRepository.findByIdempotencyKey(key)
                    .map(t -> new CreateResult(t, false))
                    .orElseThrow(() -> e); // re-throw if re-query also fails
            }
            throw e;
        }
    }

    /** Holds the result of a create operation, including whether the task is newly created. */
    public record CreateResult(Task task, boolean isNew) {}

    // -------------------------------------------------------------------------
    // Get by ID
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TaskResponse getTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.getDeletedAt() != null) {
            throw new TaskNotFoundException(taskId);
        }
        return TaskResponse.from(task);
    }

    // -------------------------------------------------------------------------
    // List (cursor pagination)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedTaskResponse listTasks(TaskStatus status, int limit, String cursor) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }

        var pageable = PageRequest.of(0, limit + 1);
        List<Task> rows;

        if (cursor == null || cursor.isBlank()) {
            rows = taskRepository.findFirstPage(status, pageable);
        } else {
            CursorPayload cp = decodeCursor(cursor);
            rows = taskRepository.findNextPage(status, cp.createdAt(), cp.id(), pageable);
        }

        boolean hasMore = rows.size() > limit;
        List<Task> page = hasMore ? rows.subList(0, limit) : rows;

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            Task last = page.get(page.size() - 1);
            nextCursor = encodeCursor(last.getCreatedAt(), last.getId());
        }

        List<TaskResponse> data = page.stream().map(TaskResponse::from).toList();
        return new PagedTaskResponse(data, new PagedTaskResponse.Pagination(nextCursor, hasMore, limit));
    }

    // -------------------------------------------------------------------------
    // Retry (Manual Dead-Letter Recovery)
    // -------------------------------------------------------------------------

    @Transactional
    public TaskResponse retryTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.getDeletedAt() != null) {
            throw new TaskNotFoundException(taskId);
        }

        taskStateMachine.assertTransitionAllowed(taskId, task.getStatus(), TaskStatus.SCHEDULED);

        task.setStatus(TaskStatus.SCHEDULED);
        task.setAttemptCount(0);
        task.setScheduledAt(Instant.now());
        task.setLastError(null);
        task.setCompletedAt(null);

        Task updated = taskRepository.save(task);
        log.info("Manually retried DEAD_LETTER task {}, reset to SCHEDULED", taskId);
        return TaskResponse.from(updated);
    }

    // -------------------------------------------------------------------------
    // Delete (Soft Delete)
    // -------------------------------------------------------------------------

    @Transactional
    public void deleteTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.getDeletedAt() != null) {
            throw new TaskNotFoundException(taskId);
        }

        task.setDeletedAt(Instant.now());
        taskRepository.save(task);
        log.info("Soft-deleted task {}", taskId);
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    @Transactional
    public TaskResponse cancelTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.getDeletedAt() != null) {
            throw new TaskNotFoundException(taskId);
        }

        Task cancelled = taskStateMachine.cancel(taskId);
        taskMetrics.incrementTasksCompleted(cancelled.getTaskType(), "CANCELLED");
        try (var idC = org.slf4j.MDC.putCloseable("task_id", taskId.toString());
             var eventC = org.slf4j.MDC.putCloseable("event", "task_cancelled")) {
            log.info("Task cancelled successfully");
        }
        return TaskResponse.from(cancelled);
    }

    // -------------------------------------------------------------------------
    // Cursor encoding / decoding
    // -------------------------------------------------------------------------

    /**
     * Encodes a keyset cursor as {@code Base64(JSON)}.
     *
     * <p>Example payload: {@code {"createdAt":"2026-08-29T10:00:00Z","id":"f47ac10b-..."}}</p>
     */
    private String encodeCursor(Instant createdAt, UUID id) {
        String json = String.format(
            "{\"createdAt\":\"%s\",\"id\":\"%s\"}", createdAt.toString(), id.toString()
        );
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes a Base64 cursor string back to its constituent fields. */
    private CursorPayload decodeCursor(String cursor) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            var node = objectMapper.readTree(bytes);
            Instant createdAt = Instant.parse(node.get("createdAt").asText());
            UUID id = UUID.fromString(node.get("id").asText());
            return new CursorPayload(createdAt, id);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor: " + e.getMessage());
        }
    }

    private record CursorPayload(Instant createdAt, UUID id) {}

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that the payload is:
     * <ol>
     *   <li>Parseable as JSON (rejects plain strings, numbers, etc. that are
     *       technically valid JSON scalars but not useful as task data).</li>
     *   <li>Within the 64 KiB byte limit.</li>
     * </ol>
     */
    private void validatePayload(String payload) {
        if (payload == null) return; // @NotBlank handles null

        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                "payload exceeds maximum size of " + MAX_PAYLOAD_BYTES + " bytes"
            );
        }

        try {
            objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload must be valid JSON: " + e.getOriginalMessage());
        }
    }

    /**
     * Validates that {@code scheduledAt}, if present, is not more than the grace
     * window in the past. This rejects clearly stale timestamps while tolerating
     * minor clock skew between client and server.
     */
    private void validateScheduledAt(Instant scheduledAt) {
        if (scheduledAt == null) return;
        Instant earliest = Instant.now().minusSeconds(SCHEDULED_AT_GRACE_SECONDS);
        if (scheduledAt.isBefore(earliest)) {
            throw new IllegalArgumentException(
                "scheduledAt cannot be more than " + SCHEDULED_AT_GRACE_SECONDS
                    + " seconds in the past"
            );
        }
    }
}
