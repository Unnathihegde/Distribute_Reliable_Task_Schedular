package com.scheduler.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.shared.domain.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handlers for task_type = {@code EMAIL}.
 *
 * <p>Simulates sending emails by parsing payload recipient/subject/body and logging the action.
 * Forwards the idempotency key (per Section 14 Layer 2) so downstream email API calls can remain
 * idempotent if a real provider is integrated.</p>
 */
@Component
public class EmailTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailTaskHandler.class);
    private final ObjectMapper objectMapper;

    public EmailTaskHandler() {
        this.objectMapper = new ObjectMapper();
    }

    public EmailTaskHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getTaskType() {
        return "EMAIL";
    }

    @Override
    public void execute(Task task) throws Exception {
        String payloadJson = task.getPayload();
        JsonNode jsonNode = objectMapper.readTree(payloadJson != null ? payloadJson : "{}");

        String recipient = jsonNode.has("to") ? jsonNode.get("to").asText() :
                (jsonNode.has("recipient") ? jsonNode.get("recipient").asText() : "unknown@example.com");
        String subject = jsonNode.has("subject") ? jsonNode.get("subject").asText() : "No Subject";
        String body = jsonNode.has("body") ? jsonNode.get("body").asText() : "";

        String taskIdStr = task.getId() != null ? task.getId().toString() : "test-task-id";
        String idempotencyKey = task.getIdempotencyKey() != null ? task.getIdempotencyKey() : taskIdStr;

        log.info("[SIMULATED EMAIL SENDER] TaskId={} IdempotencyKey={} Sending email to='{}' subject='{}' bodyLength={}",
                taskIdStr, idempotencyKey, recipient, subject, body.length());
    }
}
