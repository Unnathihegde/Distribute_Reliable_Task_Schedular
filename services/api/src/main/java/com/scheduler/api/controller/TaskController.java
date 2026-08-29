package com.scheduler.api.controller;

import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.api.dto.PagedTaskResponse;
import com.scheduler.api.dto.TaskResponse;
import com.scheduler.api.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for the Task API (blueprint Section 9).
 *
 * <p>Base path: {@code /api/v1/tasks}
 *
 * <p>This controller is intentionally thin — all business logic, validation,
 * idempotency, and state machine delegation live in {@link TaskService}.
 *
 * <h2>Endpoints implemented</h2>
 * <ul>
 *   <li>{@code POST /tasks}              — create task (201) or return existing (200)</li>
 *   <li>{@code GET  /tasks/{id}}         — get by ID (200 or 404)</li>
 *   <li>{@code GET  /tasks}              — list with filters + cursor pagination (200)</li>
 *   <li>{@code POST /tasks/{id}/cancel}  — atomic CAS cancel (200 or 409)</li>
 * </ul>
 *
 * <h2>Not yet implemented</h2>
 * <ul>
 *   <li>{@code POST /tasks/{id}/retry}   — requires DEAD_LETTER state (worker phase)</li>
 *   <li>{@code DELETE /tasks/{id}}        — soft-delete (deferred)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "Task submission, querying, and lifecycle management")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    // =========================================================================
    // POST /api/v1/tasks
    // =========================================================================

    /**
     * Creates a new task.
     *
     * <p>Returns 201 Created on success. If an {@code Idempotency-Key} header or
     * {@code idempotencyKey} body field is provided and a task with that key already
     * exists, returns 200 OK with the existing task rather than creating a duplicate.
     *
     * @param request       validated request body
     * @param idempotencyKey optional {@code Idempotency-Key} header
     */
    @PostMapping
    @Operation(summary = "Create a task",
               description = "Creates a new task. If an idempotency key is provided and a " +
                             "task with that key already exists, returns the existing task (200).")
    public ResponseEntity<TaskResponse> createTask(
            @Valid @RequestBody CreateTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        TaskService.TaskCreationResult result = taskService.createTask(request, idempotencyKey);

        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
        } else {
            return ResponseEntity.ok(result.response());
        }
    }

    // =========================================================================
    // GET /api/v1/tasks/{id}
    // =========================================================================

    /**
     * Fetches a single task by its UUID.
     *
     * @param id task UUID
     * @return 200 OK with task body, or 404 if not found
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a task by ID")
    public ResponseEntity<TaskResponse> getTask(
            @PathVariable @Parameter(description = "Task UUID") UUID id) {
        return ResponseEntity.ok(taskService.getTask(id));
    }

    // =========================================================================
    // GET /api/v1/tasks
    // =========================================================================

    /**
     * Lists tasks with optional status/type filtering and cursor-based pagination.
     *
     * <p>Example: {@code GET /api/v1/tasks?status=FAILED&limit=20&cursor=eyJjcm...}
     *
     * <p>The {@code cursor} is an opaque Base64-encoded token from the previous response's
     * {@code pagination.nextCursor}. Pass it verbatim to retrieve the next page.
     *
     * @param status optional status filter (SCHEDULED, QUEUED, RUNNING, etc.)
     * @param type   optional task type filter (EMAIL, HTTP, DEMO)
     * @param limit  max results per page (default 20, max 100)
     * @param cursor cursor from previous page (omit for first page)
     */
    @GetMapping
    @Operation(summary = "List tasks",
               description = "Returns a paginated list of tasks. Supports filtering by status " +
                             "and task type. Uses cursor-based pagination for O(limit) performance.")
    public ResponseEntity<PagedTaskResponse> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {

        return ResponseEntity.ok(taskService.listTasks(status, type, limit, cursor));
    }

    // =========================================================================
    // POST /api/v1/tasks/{id}/cancel
    // =========================================================================

    /**
     * Cancels a task that is in SCHEDULED or QUEUED state.
     *
     * <p>Uses an atomic CAS UPDATE (via {@link com.scheduler.shared.statemachine.TaskStateMachine})
     * to transition the task to CANCELLED. Returns 409 Conflict if the task is not
     * in a cancellable state.
     *
     * @param id task UUID to cancel
     * @return 200 OK with updated task, or 404/409 on failure
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a task",
               description = "Atomically cancels a task. Only SCHEDULED and QUEUED tasks can " +
                             "be cancelled. Returns 409 if the task is in an incompatible state.")
    public ResponseEntity<TaskResponse> cancelTask(
            @PathVariable @Parameter(description = "Task UUID to cancel") UUID id) {
        return ResponseEntity.ok(taskService.cancelTask(id));
    }
}
