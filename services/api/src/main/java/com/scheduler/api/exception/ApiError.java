package com.scheduler.api.exception;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Error response wrapper matching the exact shape defined in blueprint Section 9:
 *
 * <pre>{@code
 * {
 *   "error": {
 *     "code": "TASK_NOT_CANCELLABLE",
 *     "message": "Task f47ac... is in status RUNNING and cannot be cancelled.",
 *     "status": 409,
 *     "timestamp": "2026-08-28T14:05:00Z",
 *     "path": "/api/v1/tasks/f47ac.../cancel"
 *   }
 * }
 * }</pre>
 */
public class ApiError {

    @JsonProperty("error")
    private final ErrorDetail error;

    public ApiError(String code, String message, int status, String path) {
        this.error = new ErrorDetail(code, message, status, path);
    }

    public ErrorDetail getError() { return error; }

    public static class ErrorDetail {
        private final String code;
        private final String message;
        private final int status;
        private final String timestamp;
        private final String path;

        ErrorDetail(String code, String message, int status, String path) {
            this.code      = code;
            this.message   = message;
            this.status    = status;
            this.timestamp = Instant.now().toString();
            this.path      = path;
        }

        public String getCode()      { return code; }
        public String getMessage()   { return message; }
        public int    getStatus()    { return status; }
        public String getTimestamp() { return timestamp; }
        public String getPath()      { return path; }
    }
}
