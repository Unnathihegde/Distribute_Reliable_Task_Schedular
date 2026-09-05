package com.scheduler.worker.handler;

import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("EmailTaskHandler — Unit / Handler Tests")
class EmailTaskHandlerTest {

    @Test
    @DisplayName("EmailTaskHandler executes simulated email send successfully with payload")
    void executesSimulatedEmailSend() {
        EmailTaskHandler handler = new EmailTaskHandler();
        assertThat(handler.getTaskType()).isEqualTo("EMAIL");

        Task task = Task.builder()
                .taskType("EMAIL")
                .payload("{\"to\":\"user@example.com\",\"subject\":\"Welcome!\",\"body\":\"Hello World\"}")
                .priority(Priority.HIGH)
                .status(TaskStatus.RUNNING)
                .idempotencyKey("idemp-key-123")
                .build();

        assertThatCode(() -> handler.execute(task)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EmailTaskHandler handles missing optional payload fields gracefully")
    void handlesMissingPayloadFields() {
        EmailTaskHandler handler = new EmailTaskHandler();

        Task task = Task.builder()
                .taskType("EMAIL")
                .payload("{}")
                .priority(Priority.MEDIUM)
                .status(TaskStatus.RUNNING)
                .build();

        assertThatCode(() -> handler.execute(task)).doesNotThrowAnyException();
    }
}
