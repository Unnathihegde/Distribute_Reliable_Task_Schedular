package com.scheduler.api.exception;

import java.time.Instant;

/**
 * Canonical error response envelope.
 *
 * <p>Every 4xx/5xx response from the API has this exact shape:</p>
 * <pre>
 * {
 *   "error": {
 *     "code":      "TASK_NOT_CANCELLABLE",
 *     "message":   "Task f47ac... is in status RUNNING and cannot be cancelled.",
 *     "status":    409,
 *     "timestamp": "2026-08-29T14:05:00Z",
 *     "path":      "/api/v1/tasks/f47ac.../cancel"
 *   }
 * }
 * </pre>
 *
 * <p>The outer wrapper with key {@code "error"} makes it easy for clients to
 * distinguish error responses from success responses without inspecting the HTTP
 * status code alone — useful in logging pipelines and SDK code.</p>
 */
public class ApiError {

    private final ErrorDetail error;

    public ApiError(ErrorDetail error) {
        this.error = error;
    }

    public ErrorDetail getError() { return error; }

    // -------------------------------------------------------------------------
    // Inner detail record
    // -------------------------------------------------------------------------

    public static class ErrorDetail {
        private final String code;
        private final String message;
        private final int status;
        private final Instant timestamp;
        private final String path;

        public ErrorDetail(String code, String message, int status, String path) {
            this.code      = code;
            this.message   = message;
            this.status    = status;
            this.timestamp = Instant.now();
            this.path      = path;
        }

        public String getCode() { return code; }
        public String getMessage() { return message; }
        public int getStatus() { return status; }
        public Instant getTimestamp() { return timestamp; }
        public String getPath() { return path; }
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    public static ApiError of(String code, String message, int status, String path) {
        return new ApiError(new ErrorDetail(code, message, status, path));
    }
}
