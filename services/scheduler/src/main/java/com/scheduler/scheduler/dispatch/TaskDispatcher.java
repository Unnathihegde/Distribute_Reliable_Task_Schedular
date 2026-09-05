package com.scheduler.scheduler.dispatch;

import com.scheduler.scheduler.config.RabbitMqConfig;
import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Responsible for publishing claimed tasks to RabbitMQ.
 *
 * <p>Executes after DB commit (commit-then-publish strategy). Converts task
 * entity into {@link TaskDispatchMessage} and routes to the appropriate queue
 * based on task priority.</p>
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final RabbitTemplate rabbitTemplate;
    private final com.scheduler.shared.metrics.TaskMetrics taskMetrics;
    private final io.opentelemetry.api.OpenTelemetry openTelemetry;
    private final io.opentelemetry.api.trace.Tracer tracer;

    private static final io.opentelemetry.context.propagation.TextMapSetter<org.springframework.amqp.core.MessageProperties> SETTER =
            (props, key, value) -> props.setHeader(key, value);

    public TaskDispatcher(RabbitTemplate rabbitTemplate,
                          com.scheduler.shared.metrics.TaskMetrics taskMetrics,
                          io.opentelemetry.api.OpenTelemetry openTelemetry) {
        this.rabbitTemplate = rabbitTemplate;
        this.taskMetrics = taskMetrics;
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("com.scheduler.scheduler");
    }

    /**
     * Dispatches a single task to RabbitMQ.
     *
     * @param task the claimed task to dispatch
     */
    public void dispatch(Task task) {
        String routingKey = getRoutingKey(task.getPriority());
        TaskDispatchMessage message = new TaskDispatchMessage(
                task.getId(),
                task.getTaskType(),
                task.getPriority(),
                task.getAttemptCount(),
                Instant.now()
        );

        log.debug("Publishing task {} to exchange {} with routing key {}",
                task.getId(), RabbitMqConfig.TASK_EXCHANGE, routingKey);

        io.opentelemetry.context.Context parentContext = io.opentelemetry.context.Context.current();
        if (task.getTraceparent() != null && !task.getTraceparent().isBlank()) {
            java.util.Map<String, String> carrier = java.util.Map.of("traceparent", task.getTraceparent());
            parentContext = openTelemetry.getPropagators().getTextMapPropagator().extract(
                    io.opentelemetry.context.Context.current(),
                    carrier,
                    new io.opentelemetry.context.propagation.TextMapGetter<>() {
                        @Override
                        public Iterable<String> keys(java.util.Map<String, String> c) { return c.keySet(); }
                        @Override
                        public String get(java.util.Map<String, String> c, String key) { return c != null ? c.get(key) : null; }
                    }
            );
        }

        io.opentelemetry.api.trace.Span dispatchSpan = tracer.spanBuilder("scheduler.dispatchTask")
                .setParent(parentContext)
                .setAttribute("task_id", task.getId().toString())
                .setAttribute("task_type", task.getTaskType())
                .startSpan();

        try (io.opentelemetry.context.Scope scope = dispatchSpan.makeCurrent()) {
            rabbitTemplate.convertAndSend(RabbitMqConfig.TASK_EXCHANGE, routingKey, message, m -> {
                openTelemetry.getPropagators().getTextMapPropagator().inject(
                        io.opentelemetry.context.Context.current(),
                        m.getMessageProperties(),
                        SETTER
                );
                Object outgoingHeader = m.getMessageProperties().getHeader("traceparent");
                log.info("[TRACE DEBUG] Outgoing traceparent header for task {}: {}", task.getId(), outgoingHeader);
                return m;
            });
        } finally {
            dispatchSpan.end();
        }

        taskMetrics.incrementSchedulerDispatches();
        if (task.getScheduledAt() != null) {
            java.time.Duration latency = java.time.Duration.between(task.getScheduledAt(), Instant.now());
            if (!latency.isNegative()) {
                taskMetrics.recordTaskSchedulingLatency(latency);
            }
        }

        try (var idC = org.slf4j.MDC.putCloseable("task_id", task.getId().toString());
             var eventC = org.slf4j.MDC.putCloseable("event", "task_dispatched")) {
            log.info("Task dispatched to RabbitMQ queue via routing key {}", routingKey);
        }
    }

    private String getRoutingKey(Priority priority) {
        if (priority == null) {
            return RabbitMqConfig.ROUTING_KEY_MEDIUM;
        }
        return switch (priority) {
            case HIGH -> RabbitMqConfig.ROUTING_KEY_HIGH;
            case MEDIUM -> RabbitMqConfig.ROUTING_KEY_MEDIUM;
            case LOW -> RabbitMqConfig.ROUTING_KEY_LOW;
        };
    }
}
