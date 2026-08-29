package com.scheduler.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.api.dto.PagedTaskResponse;
import com.scheduler.api.dto.TaskResponse;
import com.scheduler.api.exception.TaskNotFoundException;
import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.shared.statemachine.TaskStateMachine;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic layer for the Task API.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate request payloads beyond what Bean Validation covers
 *       (JSON validity, size, scheduledAt sanity).</li>
 *   <li>Handle idempotency key collisions (pre-check + catch race).</li>
 *   <li>Delegate state transitions to {@link TaskStateMachine} — NOT re-implemented here.</li>
 *   <li>Encode and decode cursor tokens for keyset pagination.</li>
 *   <li>Map between entities and DTOs.</li>
 * </ul>
 *
 * <h2>State machine reuse</h2>
 * <p>All cancellation logic goes through {@link TaskStateMachine#transition}, which
 * in turn calls {@link TaskRepository#transitionStatus} — the single atomic CAS UPDATE.
 * The API layer has zero knowledge of which transitions are legal; that is entirely
 * encapsulated in the shared module.
 *
 * <h2>JSONB payload handling</h2>
 * <p>The client sends {@code payload} as a JSON object. Jackson deserializes it into
 * a {@code Map<String, Object>} (or similar). We then re-serialize it to a JSON string
 * before storing in the {@code Task.payload} field (which maps to PostgreSQL JSONB via
 * {@code @JdbcTypeCode(SqlTypes.JSON)}). On read, we parse the stored JSON string back
 * into an object for the response. This guarantees the stored value is always valid JSON
 * and that the round-trip is correct.
 */
@Service
@Transactional(readOnly = true)
public class TaskService {

    /** Maximum allowed payload size: 64 KB (blueprint Section 9). */
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    /**
     * How far in the past {@code scheduledAt} may be before we reject it.
     * Blueprint Section 9: "must not be in the past by more than 5 minutes."
     */
    private static final int SCHEDULED_AT_PAST_TOLERANCE_MINUTES = 5;

    private final TaskRepository taskRepository;
    private final TaskStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public TaskService(TaskRepository taskRepository,
                       TaskStateMachine stateMachine,
                       ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.stateMachine   = stateMachine;
        this.objectMapper   = objectMapper;
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/tasks
    // -------------------------------------------------------------------------

    /**
     * Creates a new task or returns an existing one if the idempotency key matches.
     *
     * @param request  validated request body
     * @param headerKey idempotency key from the {@code Idempotency-Key} header (may be null)
     * @return {@code (TaskResponse, isNewlyCreated)} — caller uses the boolean to set 200 vs 201
     */
    @Transactional
    public TaskCreationResult createTask(CreateTaskRequest request, String headerKey) {
        // Merge idempotency key: body field takes precedence, fall back to header
        String idempotencyKey = request.getIdempotencyKey() != null
                ? request.getIdempotencyKey()
                : headerKey;

        // --- Idempotency pre-check ---
        if (idempotencyKey != null) {
            Optional<Task> existing = taskRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return TaskCreationResult.existing(toResponse(existing.get()));
            }
        }

        // --- Validate payload: must be valid JSON and ≤ 64 KB ---
        String payloadJson = serializePayload(request.getPayload());
        validatePayloadSize(payloadJson);

        // --- Validate scheduledAt: must not be more than 5 minutes in the past ---
        OffsetDateTime scheduledAt = request.getScheduledAt();
        if (scheduledAt != null) {
            OffsetDateTime earliest = OffsetDateTime.now()
                    .minusMinutes(SCHEDULED_AT_PAST_TOLERANCE_MINUTES);
            if (scheduledAt.isBefore(earliest)) {
                throw new IllegalArgumentException(
                        "scheduledAt must not be more than 5 minutes in the past. Provided: " + scheduledAt);
            }
        }

        // --- Validate and resolve priority ---
        Priority priority = resolvePriority(request.getPriority());

        // --- Build the Task entity ---
        Task task = new Task();
        task.setTaskType(request.getTaskType().toUpperCase());
        task.setPayload(payloadJson);
        task.setStatus(TaskStatus.SCHEDULED);
        task.setPriority(priority);
        task.setScheduledAt(scheduledAt != null ? scheduledAt : OffsetDateTime.now());
        task.setMaxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts() : 5);
        task.setIdempotencyKey(idempotencyKey);
        task.setCronExpression(request.getCronExpression());
        task.setRecurrenceEnabled(request.getCronExpression() != null);

        // --- Persist ---
        try {
            Task saved = taskRepository.save(task);
            return TaskCreationResult.created(toResponse(saved));
        } catch (DataIntegrityViolationException ex) {
            // Concurrent request with same idempotency key won the unique-index race.
            // Re-query and return the existing task.
            if (idempotencyKey != null) {
                return taskRepository.findByIdempotencyKey(idempotencyKey)
                        .map(t -> TaskCreationResult.existing(toResponse(t)))
                        .orElseThrow(() -> ex); // key was gone — re-throw original
            }
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/tasks/{id}
    // -------------------------------------------------------------------------

    /**
     * Fetches a single task by ID.
     *
     * @throws TaskNotFoundException if no task with the given ID exists
     */
    public TaskResponse getTask(UUID id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
        return toResponse(task);
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/tasks
    // -------------------------------------------------------------------------

    /**
     * Lists tasks with optional status/type filtering and cursor-based pagination.
     *
     * <h2>Cursor strategy</h2>
     * <p>Cursor encodes {@code {"createdAt":"...","id":"..."}} as Base64 JSON.
     * The query uses a keyset predicate:
     * <pre>
     * WHERE (status = ?) AND (created_at < :ts OR (created_at = :ts AND id > :id))
     * ORDER BY created_at DESC, id ASC
     * LIMIT limit + 1
     * </pre>
     * Fetching {@code limit + 1} rows lets us detect whether there is a next page
     * without a separate COUNT query.
     *
     * @param statusFilter optional status filter (null = all statuses)
     * @param typeFilter   optional task type filter (null = all types)
     * @param limit        max rows per page (default 20, max 100)
     * @param cursor       opaque Base64 cursor from previous response (null = first page)
     */
    public PagedTaskResponse listTasks(String statusFilter, String typeFilter,
                                       int limit, String cursor) {
        if (limit < 1) limit = 20;
        if (limit > 100) limit = 100;

        TaskStatus status = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                status = TaskStatus.valueOf(statusFilter.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid status filter: '" + statusFilter + "'. Valid values: " +
                        java.util.Arrays.toString(TaskStatus.values()));
            }
        }

        // Decode cursor
        CursorPayload cursorPayload = cursor != null ? decodeCursor(cursor) : null;

        // Fetch limit+1 to detect hasMore
        List<Task> tasks;
        if (cursorPayload == null) {
            // First page
            tasks = fetchFirstPage(status, typeFilter, limit + 1);
        } else {
            tasks = fetchNextPage(status, typeFilter, limit + 1,
                    cursorPayload.createdAt(), cursorPayload.id());
        }

        boolean hasMore = tasks.size() > limit;
        if (hasMore) {
            tasks = tasks.subList(0, limit);
        }

        List<TaskResponse> responses = tasks.stream()
                .map(this::toResponse)
                .toList();

        String nextCursor = null;
        if (hasMore && !tasks.isEmpty()) {
            Task last = tasks.getLast();
            nextCursor = encodeCursor(last.getCreatedAt(), last.getId());
        }

        return new PagedTaskResponse(responses, nextCursor, hasMore);
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/tasks/{id}/cancel
    // -------------------------------------------------------------------------

    /**
     * Cancels a task that is in SCHEDULED or QUEUED state.
     *
     * <p>Delegates the state transition to {@link TaskStateMachine#transition}, which
     * performs a CAS UPDATE. If the task is not in a cancellable state, the state machine
     * throws {@link com.scheduler.shared.statemachine.IllegalStateTransitionException},
     * which the {@link com.scheduler.api.exception.GlobalExceptionHandler} maps to 409.
     *
     * @throws TaskNotFoundException            if the task does not exist
     * @throws com.scheduler.shared.statemachine.IllegalStateTransitionException
     *                                          if the task is not in SCHEDULED or QUEUED state
     */
    @Transactional
    public TaskResponse cancelTask(UUID id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));

        // Try SCHEDULED → CANCELLED first, then QUEUED → CANCELLED.
        // The state machine's CAS UPDATE will fail (0 rows) if the task moved
        // between our read and the UPDATE — thrown as IllegalStateTransitionException.
        TaskStatus currentStatus = task.getStatus();

        boolean cancellable = stateMachine.canTransition(currentStatus, TaskStatus.CANCELLED);
        if (!cancellable) {
            throw new com.scheduler.shared.statemachine.IllegalStateTransitionException(
                    id, currentStatus, TaskStatus.CANCELLED,
                    "INVALID_TRANSITION — task is in status '" + currentStatus +
                    "' which is not cancellable");
        }

        stateMachine.transition(id, currentStatus, TaskStatus.CANCELLED);

        // Re-fetch the updated entity to return the current state
        return toResponse(taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id)));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Converts a Task entity to a response DTO, parsing the stored JSON payload. */
    private TaskResponse toResponse(Task task) {
        Object parsedPayload = null;
        if (task.getPayload() != null) {
            try {
                parsedPayload = objectMapper.readValue(task.getPayload(), Object.class);
            } catch (JsonProcessingException e) {
                // Stored payload is not valid JSON — return raw string as fallback
                parsedPayload = task.getPayload();
            }
        }
        return TaskResponse.from(task, parsedPayload);
    }

    /** Serializes the client-supplied payload object to a JSON string. */
    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload must be valid JSON: " + e.getMessage());
        }
    }

    /** Validates that the serialized payload does not exceed 64 KB. */
    private void validatePayloadSize(String payloadJson) {
        int bytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "payload exceeds maximum size of 64 KB (actual: " + bytes + " bytes)");
        }
    }

    /** Resolves the priority string to a Priority enum, defaulting to MEDIUM. */
    private Priority resolvePriority(String priorityStr) {
        if (priorityStr == null || priorityStr.isBlank()) {
            return Priority.MEDIUM;
        }
        try {
            return Priority.valueOf(priorityStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "priority must be one of HIGH, MEDIUM, LOW. Got: '" + priorityStr + "'");
        }
    }

    // ---- Pagination helpers ----

    private List<Task> fetchFirstPage(TaskStatus status, String type, int limit) {
        // Use Spring Data JPA's Sort + PageRequest for the first page (no cursor)
        PageRequest page = PageRequest.of(0, limit, Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.asc("id")));

        if (status != null && type != null) {
            return taskRepository.findByStatusAndTaskType(status, type.toUpperCase(), page);
        } else if (status != null) {
            return taskRepository.findByStatus(status, page);
        } else if (type != null) {
            return taskRepository.findByTaskType(type.toUpperCase(), page);
        } else {
            return taskRepository.findAll(page).getContent();
        }
    }

    private List<Task> fetchNextPage(TaskStatus status, String type, int limit,
                                      OffsetDateTime cursorCreatedAt, UUID cursorId) {
        PageRequest page = PageRequest.of(0, limit, Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.asc("id")));

        if (status != null && type != null) {
            return taskRepository.findByStatusAndTypeWithCursor(
                    status, type.toUpperCase(), cursorCreatedAt, cursorId, page);
        } else if (status != null) {
            return taskRepository.findByStatusWithCursor(status, cursorCreatedAt, cursorId, page);
        } else if (type != null) {
            return taskRepository.findByTypeWithCursor(
                    type.toUpperCase(), cursorCreatedAt, cursorId, page);
        } else {
            return taskRepository.findAllWithCursor(cursorCreatedAt, cursorId, page);
        }
    }

    // ---- Cursor encoding / decoding ----

    private String encodeCursor(OffsetDateTime createdAt, UUID id) {
        String json = "{\"createdAt\":\"" + createdAt + "\",\"id\":\"" + id + "\"}";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private CursorPayload decodeCursor(String cursor) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            var node = objectMapper.readTree(decoded);
            OffsetDateTime ts = OffsetDateTime.parse(node.get("createdAt").asText());
            UUID id = UUID.fromString(node.get("id").asText());
            return new CursorPayload(ts, id);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid or corrupted cursor token");
        }
    }

    private record CursorPayload(OffsetDateTime createdAt, UUID id) {}

    // ---- Result type for createTask ----

    /**
     * Carries both the task response and whether it was newly created (201) or
     * returned from idempotency (200).
     */
    public record TaskCreationResult(TaskResponse response, boolean created) {
        public static TaskCreationResult created(TaskResponse r)  { return new TaskCreationResult(r, true);  }
        public static TaskCreationResult existing(TaskResponse r) { return new TaskCreationResult(r, false); }
    }
}
