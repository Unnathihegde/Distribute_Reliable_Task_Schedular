package com.scheduler.api.controller;

import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.api.dto.PagedTaskResponse;
import com.scheduler.api.dto.TaskResponse;
import com.scheduler.api.service.TaskService;
import com.scheduler.shared.domain.TaskStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for the Task resource.
 *
 * <p>All endpoints are mounted at {@code /api/v1/tasks}. The {@code /v1} prefix
 * enables forward-compatible API versioning — if the response contract changes
 * incompatibly, we introduce {@code /api/v2/tasks} without breaking existing
 * clients.</p>
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li>Controllers are deliberately thin: validation is handled by Bean Validation
 *       annotations, business logic is in {@link TaskService}, and error mapping is
 *       in {@link com.scheduler.api.exception.GlobalExceptionHandler}.</li>
 *   <li>The {@code Idempotency-Key} header is supported as an alternative to the
 *       JSON body field {@code idempotencyKey}, consistent with Stripe's API style.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "Task lifecycle management — create, query, and cancel tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/tasks — Create task
    // -------------------------------------------------------------------------

    @PostMapping
    @Operation(
        summary = "Submit a new task",
        description = "Creates a task and persists it. If an Idempotency-Key is provided " +
                      "and a task with that key already exists, the existing task is returned " +
                      "with HTTP 200 (not 201).",
        responses = {
            @ApiResponse(responseCode = "201", description = "Task created"),
            @ApiResponse(responseCode = "200", description = "Task already existed (idempotency hit)"),
            @ApiResponse(responseCode = "400", description = "Validation error or malformed body")
        }
    )
    public ResponseEntity<TaskResponse> createTask(
            @Valid @RequestBody CreateTaskRequest request,
            @Parameter(description = "Client-generated idempotency key (alternative to body field)")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader) {

        // Header takes precedence; body field is a fallback
        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            request.setIdempotencyKey(idempotencyKeyHeader);
        }

        TaskService.CreateResult result = taskService.createTask(request);
        HttpStatus status = result.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(TaskResponse.from(result.task()));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/tasks/{id} — Get task by ID
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @Operation(
        summary = "Get a task by ID",
        responses = {
            @ApiResponse(responseCode = "200", description = "Task found"),
            @ApiResponse(responseCode = "404", description = "Task not found")
        }
    )
    public ResponseEntity<TaskResponse> getTask(
            @PathVariable UUID id) {

        return ResponseEntity.ok(taskService.getTask(id));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/tasks — List tasks (cursor pagination)
    // -------------------------------------------------------------------------

    @GetMapping
    @Operation(
        summary = "List tasks with cursor pagination",
        description = "Returns a page of tasks ordered by createdAt DESC. " +
                      "Pass the returned nextCursor as the cursor parameter to fetch the next page.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Page of tasks"),
            @ApiResponse(responseCode = "400", description = "Invalid limit or cursor")
        }
    )
    public ResponseEntity<PagedTaskResponse> listTasks(
            @Parameter(description = "Filter by task status")
            @RequestParam(required = false) TaskStatus status,

            @Parameter(description = "Maximum number of tasks to return (1–200)", example = "20")
            @RequestParam(defaultValue = "20") int limit,

            @Parameter(description = "Opaque cursor from previous page's nextCursor field")
            @RequestParam(required = false) String cursor) {

        return ResponseEntity.ok(taskService.listTasks(status, limit, cursor));
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/tasks/{id}/cancel — Cancel task
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/cancel")
    @Operation(
        summary = "Cancel a task",
        description = "Cancels a task in SCHEDULED or QUEUED status. " +
                      "RUNNING and terminal tasks cannot be cancelled.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Task cancelled"),
            @ApiResponse(responseCode = "404", description = "Task not found"),
            @ApiResponse(responseCode = "409", description = "Task is not cancellable")
        }
    )
    public ResponseEntity<TaskResponse> cancelTask(
            @PathVariable UUID id) {

        return ResponseEntity.ok(taskService.cancelTask(id));
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/tasks/{id}/retry — Retry dead-lettered task
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/retry")
    @Operation(
        summary = "Retry a dead-lettered task",
        description = "Resets a task in DEAD_LETTER status back to SCHEDULED with attempt_count reset to 0.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Task reset to SCHEDULED"),
            @ApiResponse(responseCode = "404", description = "Task not found"),
            @ApiResponse(responseCode = "409", description = "Task is not in DEAD_LETTER status")
        }
    )
    public ResponseEntity<TaskResponse> retryTask(
            @PathVariable UUID id) {

        return ResponseEntity.ok(taskService.retryTask(id));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/v1/tasks/{id} — Soft delete task
    // -------------------------------------------------------------------------

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Soft-delete a task",
        description = "Sets deleted_at on the task, hiding it from list/get queries.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Task soft-deleted"),
            @ApiResponse(responseCode = "404", description = "Task not found")
        }
    )
    public ResponseEntity<Void> deleteTask(
            @PathVariable UUID id) {

        taskService.deleteTask(id);
        return ResponseEntity.noContent().build();
    }
}
