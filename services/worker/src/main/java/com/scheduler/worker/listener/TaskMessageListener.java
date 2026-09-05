package com.scheduler.worker.listener;

import com.rabbitmq.client.Channel;
import com.scheduler.shared.dto.TaskDispatchMessage;
import com.scheduler.worker.execution.TaskExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * RabbitMQ consumer handling message delivery for task priority queues.
 *
 * <p>Uses manual ACK mode (commit-after-DB-write strategy).</p>
 */
@Component
public class TaskMessageListener {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageListener.class);

    private final TaskExecutionService taskExecutionService;
    private final io.opentelemetry.api.OpenTelemetry openTelemetry;
    private final io.opentelemetry.api.trace.Tracer tracer;

    public TaskMessageListener(TaskExecutionService taskExecutionService,
                               io.opentelemetry.api.OpenTelemetry openTelemetry) {
        this.taskExecutionService = taskExecutionService;
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("com.scheduler.worker");
    }

    private static final io.opentelemetry.context.propagation.TextMapGetter<org.springframework.amqp.core.MessageProperties> GETTER =
            new io.opentelemetry.context.propagation.TextMapGetter<>() {
                @Override
                public Iterable<String> keys(org.springframework.amqp.core.MessageProperties carrier) {
                    return carrier != null && carrier.getHeaders() != null ? carrier.getHeaders().keySet() : java.util.Collections.emptyList();
                }

                @Override
                public String get(org.springframework.amqp.core.MessageProperties carrier, String key) {
                    if (carrier == null || carrier.getHeaders() == null) {
                        return null;
                    }
                    Object val = carrier.getHeader(key);
                    return val != null ? val.toString() : null;
                }
            };

    @RabbitListener(queues = {"task.high", "task.medium", "task.low"})
    public void onMessage(TaskDispatchMessage message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          org.springframework.amqp.core.Message amqpMessage) throws IOException {

        log.debug("Received dispatch message for task {} with delivery tag {}", message.getTaskId(), deliveryTag);

        Object incomingHeader = amqpMessage.getMessageProperties().getHeader("traceparent");
        log.info("[TRACE DEBUG] Incoming traceparent header for task {}: {}", message.getTaskId(), incomingHeader);

        io.opentelemetry.context.Context extractedContext = openTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(
                        io.opentelemetry.context.Context.current(),
                        amqpMessage.getMessageProperties(),
                        GETTER
                );

        try (io.opentelemetry.context.Scope scope = extractedContext.makeCurrent()) {
            io.opentelemetry.api.trace.Span consumeSpan = tracer.spanBuilder("worker.consumeMessage")
                    .setAttribute("task_id", message.getTaskId().toString())
                    .setAttribute("task_type", message.getTaskType() != null ? message.getTaskType() : "UNKNOWN")
                    .startSpan();
            try (io.opentelemetry.context.Scope spanScope = consumeSpan.makeCurrent()) {
                taskExecutionService.processTask(message.getTaskId(), io.opentelemetry.context.Context.current());
            } finally {
                consumeSpan.end();
            }
        } catch (Exception e) {
            log.error("Unexpected error processing task {}: {}", message.getTaskId(), e.getMessage(), e);
        } finally {
            // Manual ACK: ACK after DB outcome write succeeds or idempotency check completes
            channel.basicAck(deliveryTag, false);
            log.debug("Acknowledged delivery tag {} for task {}", deliveryTag, message.getTaskId());
        }
    }
}
