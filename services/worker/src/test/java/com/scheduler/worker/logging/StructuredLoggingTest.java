package com.scheduler.worker.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskAttemptRepository;
import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.worker.execution.TaskExecutionService;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StructuredLoggingTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskAttemptRepository taskAttemptRepository;

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Test
    @DisplayName("Structured JSON log line is emitted containing event and task_id fields upon task execution completed")
    void testStructuredJsonLogLineEmittedOnTaskCompletion() throws Exception {
        Task task = Task.builder()
                .taskType("DEMO")
                .payload("{\"test\": true}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.QUEUED)
                .scheduledAt(Instant.now())
                .maxAttempts(3)
                .build();

        Task saved = taskRepository.save(task);

        // Attach a LogstashEncoder OutputStream to capture formatted JSON
        Logger rootLogger = (Logger) LoggerFactory.getLogger(TaskExecutionService.class);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(rootLogger.getLoggerContext());
        encoder.start();

        ch.qos.logback.core.OutputStreamAppender<ILoggingEvent> appender = new ch.qos.logback.core.OutputStreamAppender<>();
        appender.setContext(rootLogger.getLoggerContext());
        appender.setEncoder(encoder);
        appender.setOutputStream(outputStream);
        appender.start();

        rootLogger.addAppender(appender);

        try {
            taskExecutionService.processTask(saved.getId());

            String logOutput = outputStream.toString();
            assertThat(logOutput).isNotEmpty();

            ObjectMapper mapper = new ObjectMapper();
            String[] lines = logOutput.split("\n");

            boolean foundEvent = false;
            for (String line : lines) {
                if (line.isBlank()) continue;
                JsonNode jsonNode = mapper.readTree(line);

                if (jsonNode.has("event") && "task_execution_completed".equals(jsonNode.get("event").asText())) {
                    foundEvent = true;
                    assertThat(jsonNode.has("task_id")).isTrue();
                    assertThat(jsonNode.get("task_id").asText()).isEqualTo(saved.getId().toString());
                    assertThat(jsonNode.has("status")).isTrue();
                    assertThat(jsonNode.get("status").asText()).isEqualTo("SUCCESS");
                    break;
                }
            }

            assertThat(foundEvent)
                    .as("Expected JSON log line with event 'task_execution_completed' and matching task_id")
                    .isTrue();
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
            encoder.stop();
        }
    }
}
