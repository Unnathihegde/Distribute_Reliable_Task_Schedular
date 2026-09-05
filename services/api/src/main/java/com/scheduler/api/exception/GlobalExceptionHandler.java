package com.scheduler.api.exception;

import com.scheduler.shared.exception.TaskNotFoundException;
import com.scheduler.shared.statemachine.IllegalStateTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Centralises error handling for all REST controllers.
 *
 * <p>Every exception that escapes a controller is caught here and converted to the
 * canonical {@link ApiError} envelope. This ensures clients always receive a
 * consistent, machine-readable error structure regardless of which controller
 * or service threw the exception.</p>
 *
 * <h3>Mapping table</h3>
 * <pre>
 *   MethodArgumentNotValidException    → 400 VALIDATION_ERROR
 *   HttpMessageNotReadableException    → 400 INVALID_REQUEST_BODY
 *   IllegalArgumentException           → 400 INVALID_ARGUMENT
 *   TaskNotFoundException              → 404 TASK_NOT_FOUND
 *   IllegalStateTransitionException    → 409 TASK_NOT_CANCELLABLE
 *   Throwable (catch-all)              → 500 INTERNAL_ERROR
 * </pre>
 *
 * <p>Note: {@code DataIntegrityViolationException} from the idempotency race condition
 * is handled inline in {@link com.scheduler.api.service.TaskService} — it re-queries
 * and returns the existing task rather than propagating an error.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Bean Validation failures — field-level constraint violations from {@code @Valid}.
     * Collects all violations into a single comma-separated message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {

        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest().body(
            ApiError.of("VALIDATION_ERROR", message, 400, req.getRequestURI())
        );
    }

    /**
     * Malformed JSON body — includes invalid enum values (e.g., {@code "priority": "URGENT"}).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest req) {

        log.debug("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(
            ApiError.of("INVALID_REQUEST_BODY",
                "Request body is missing or malformed: " + rootCause(ex),
                400, req.getRequestURI())
        );
    }

    /**
     * Application-level argument errors (e.g., stale scheduledAt, oversized payload).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest req) {

        return ResponseEntity.badRequest().body(
            ApiError.of("INVALID_ARGUMENT", ex.getMessage(), 400, req.getRequestURI())
        );
    }

    /**
     * Invalid path variable type — e.g., a non-UUID string where a UUID is expected.
     * Spring's type conversion raises this before the controller method is invoked.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {

        String message = String.format(
            "Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());
        return ResponseEntity.badRequest().body(
            ApiError.of("INVALID_ARGUMENT", message, 400, req.getRequestURI())
        );
    }

    /**
     * Task not found — no task with the requested ID exists.
     */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleTaskNotFound(
            TaskNotFoundException ex, HttpServletRequest req) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError.of("TASK_NOT_FOUND", ex.getMessage(), 404, req.getRequestURI())
        );
    }

    /**
     * Illegal state transition — e.g., cancelling a RUNNING or SUCCESS task.
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalTransition(
            IllegalStateTransitionException ex, HttpServletRequest req) {

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError.of("TASK_NOT_CANCELLABLE", ex.getMessage(), 409, req.getRequestURI())
        );
    }

    /**
     * Catch-all for any unhandled exception.
     * Logs with full stack trace but returns a minimal error body to avoid
     * leaking internal implementation details to clients.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Throwable ex, HttpServletRequest req) {

        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.internalServerError().body(
            ApiError.of("INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.",
                500, req.getRequestURI())
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : t.getMessage();
    }
}
