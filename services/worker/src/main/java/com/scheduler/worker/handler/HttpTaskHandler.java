package com.scheduler.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.shared.domain.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * TaskHandler implementation for task_type = {@code HTTP}.
 *
 * <p>Sends an HTTP POST to the specified callback URL with configurable timeouts.
 * Includes {@code X-Task-Id} and {@code X-Idempotency-Key} headers per Section 14.
 * Treats 2xx responses as success and non-2xx status codes or network errors as failures,
 * triggering the standard retry path.</p>
 */
@Component
public class HttpTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpTaskHandler.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration readTimeout;

    public HttpTaskHandler() {
        this(5, 10);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HttpTaskHandler(
            @Value("${worker.http-handler.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${worker.http-handler.read-timeout-seconds:10}") int readTimeoutSeconds) {
        this.objectMapper = new ObjectMapper();
        this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    public HttpTaskHandler(HttpClient httpClient, Duration readTimeout) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.readTimeout = readTimeout;
    }

    @Override
    public String getTaskType() {
        return "HTTP";
    }

    @Override
    public void execute(Task task) throws Exception {
        String payloadJson = task.getPayload();
        JsonNode jsonNode = objectMapper.readTree(payloadJson != null ? payloadJson : "{}");

        String url = jsonNode.has("url") ? jsonNode.get("url").asText() :
                (jsonNode.has("callbackUrl") ? jsonNode.get("callbackUrl").asText() : null);

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("HTTP task payload missing required 'url' or 'callbackUrl' field");
        }

        String requestBody = jsonNode.has("body") ? jsonNode.get("body").toString() : payloadJson;
        String taskIdStr = task.getId() != null ? task.getId().toString() : "test-task-id";
        String idempotencyKey = task.getIdempotencyKey() != null ? task.getIdempotencyKey() : taskIdStr;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("X-Task-Id", taskIdStr)
                .header("X-Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody != null ? requestBody : "{}"))
                .build();

        log.debug("Sending HTTP POST to url={} taskId={} idempotencyKey={}", url, task.getId(), idempotencyKey);

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            log.info("HTTP task {} callback to {} succeeded with status {}", task.getId(), url, statusCode);
        } else {
            String errorMsg = "HTTP task " + task.getId() + " callback to " + url + " failed with status " + statusCode + ": " + response.body();
            log.warn(errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }
}
