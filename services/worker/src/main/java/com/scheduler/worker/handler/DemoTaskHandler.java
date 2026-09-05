package com.scheduler.worker.handler;

import com.scheduler.shared.domain.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Built-in handler for "DEMO" tasks.
 */
@Component
public class DemoTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoTaskHandler.class);

    @Override
    public String getTaskType() {
        return "DEMO";
    }

    @Override
    public void execute(Task task) throws Exception {
        log.info("Executing DEMO task {} with payload: {}", task.getId(), task.getPayload());
    }
}
