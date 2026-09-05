package com.scheduler.worker.handler;

import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HttpTaskHandler — Webhook Integration Tests (Local JDK HttpServer)")
class HttpTaskHandlerTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> receivedTaskId = new AtomicReference<>();
    private final AtomicReference<String> receivedIdempotencyKey = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/webhook/success", exchange -> {
            receivedTaskId.set(exchange.getRequestHeaders().getFirst("X-Task-Id"));
            receivedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));

            byte[] response = "{\"status\":\"ok\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        server.createContext("/webhook/fail", exchange -> {
            byte[] response = "{\"error\":\"server error\"}".getBytes();
            exchange.sendResponseHeaders(500, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("HttpTaskHandler succeeds on HTTP 200 OK and sends X-Task-Id and X-Idempotency-Key headers")
    void succeedsOn200Ok() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpTaskHandler handler = new HttpTaskHandler(httpClient, Duration.ofSeconds(3));

        Task task = Task.builder()
                .taskType("HTTP")
                .payload("{\"url\":\"http://localhost:" + port + "/webhook/success\",\"body\":\"{\\\"event\\\":\\\"ping\\\"}\"}")
                .priority(Priority.HIGH)
                .status(TaskStatus.RUNNING)
                .idempotencyKey("http-idemp-999")
                .build();

        handler.execute(task);

        assertThat(receivedTaskId.get()).isEqualTo("test-task-id");
        assertThat(receivedIdempotencyKey.get()).isEqualTo("http-idemp-999");
    }

    @Test
    @DisplayName("HttpTaskHandler throws exception on HTTP 500 error triggering worker retry")
    void throwsExceptionOn500Error() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpTaskHandler handler = new HttpTaskHandler(httpClient, Duration.ofSeconds(3));

        Task task = Task.builder()
                .taskType("HTTP")
                .payload("{\"url\":\"http://localhost:" + port + "/webhook/fail\"}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.RUNNING)
                .build();

        assertThatThrownBy(() -> handler.execute(task))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed with status 500");
    }
}
