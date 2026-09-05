package com.scheduler.worker.handler;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping task_type strings to registered {@link TaskHandler} beans.
 */
@Component
public class TaskHandlerRegistry {

    private final Map<String, TaskHandler> handlerMap = new ConcurrentHashMap<>();

    public TaskHandlerRegistry(List<TaskHandler> handlers) {
        for (TaskHandler handler : handlers) {
            handlerMap.put(handler.getTaskType().toUpperCase(), handler);
        }
    }

    public Optional<TaskHandler> getHandler(String taskType) {
        if (taskType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlerMap.get(taskType.trim().toUpperCase()));
    }

    public void registerHandler(TaskHandler handler) {
        if (handler != null && handler.getTaskType() != null) {
            handlerMap.put(handler.getTaskType().trim().toUpperCase(), handler);
        }
    }

    public void registerHandler(String taskType, TaskHandler handler) {
        if (taskType != null && handler != null) {
            handlerMap.put(taskType.trim().toUpperCase(), handler);
        }
    }
}
