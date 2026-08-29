package com.scheduler.api.exception;

import com.scheduler.shared.statemachine.IllegalStateTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Global exception handler that converts all exceptions into the consistent error
 * response format defined in blueprint Section 9.
 *
 * <p>Every response body has the same shape:
 * <pre>{@code
 * {
 *   "error": {
 *     "code": "...",
 *     "message": "...",
 *     "status": 4xx or 5xx,
 *     "timestamp": "...",
 *     "path": "..."
 *   }
 * }
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Bean Validation failures from {@code @Valid} on controller parameters.
     * Aggregates all field errors into a single human-readable message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_ERROR", message, 400, request.getRequestURI()));
    }

    /**
     * Malformed JSON or unreadable request body (e.g., invalid enum value,
     * wrong type for a field).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST_BODY",
                        "Request body is missing or malformed: " + ex.getMostSpecificCause().getMessage(),
                        400, request.getRequestURI()));
    }

    /**
     * Task not found — 404.
     */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            TaskNotFoundException ex, HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError("TASK_NOT_FOUND", ex.getMessage(), 404, request.getRequestURI()));
    }

    /**
     * Illegal state transition — the task is not in a cancellable state.
     * Maps to 409 Conflict per blueprint Section 9.
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalTransition(
            IllegalStateTransitionException ex, HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiError("TASK_NOT_CANCELLABLE", ex.getMessage(), 409, request.getRequestURI()));
    }

    /**
     * Generic payload validation failures raised by the service layer
     * (e.g., invalid JSON payload, payload too large, scheduledAt in the past).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_ERROR", ex.getMessage(), 400, request.getRequestURI()));
    }

    /**
     * Catch-all for unexpected exceptions. Logs the full stack trace and returns
     * a generic 500 response (no internal details exposed to the client).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR",
                        "An unexpected error occurred. Please try again later.",
                        500, request.getRequestURI()));
    }
}
