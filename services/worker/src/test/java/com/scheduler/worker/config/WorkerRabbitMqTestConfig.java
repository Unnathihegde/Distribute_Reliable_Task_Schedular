package com.scheduler.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-only RabbitMQ topology configuration for worker integration tests.
 *
 * <p>In production the exchange/queue topology is declared by
 * {@code services/scheduler}'s {@code RabbitMqConfig}.  Worker integration
 * tests run in isolation — no scheduler process is present — so the queues
 * never exist, which causes the {@code @RabbitListener} to fail with:
 * <pre>ShutdownSignalException: NOT_FOUND - no queue 'task.high' in vhost '/'</pre>
 *
 * <p>This {@link TestConfiguration} is auto-imported by Spring Boot's test
 * infrastructure (it lives inside {@code src/test/java} and is within the
 * component-scan base package {@code com.scheduler}), ensuring the topology
 * is declared before any listener container starts.
 */
@TestConfiguration
public class WorkerRabbitMqTestConfig {

    // ── Exchange names ────────────────────────────────────────────────────────
    public static final String TASK_EXCHANGE     = "task.exchange";
    public static final String TASK_DLX_EXCHANGE = "task.dlx.exchange";

    // ── Queue names ───────────────────────────────────────────────────────────
    public static final String QUEUE_HIGH   = "task.high";
    public static final String QUEUE_MEDIUM = "task.medium";
    public static final String QUEUE_LOW    = "task.low";
    public static final String QUEUE_DLQ    = "task.dlq";

    // ── Routing keys ──────────────────────────────────────────────────────────
    public static final String ROUTING_KEY_HIGH   = "high";
    public static final String ROUTING_KEY_MEDIUM = "medium";
    public static final String ROUTING_KEY_LOW    = "low";
    public static final String ROUTING_KEY_DLQ    = "task.dlq";

    // ── Exchanges ─────────────────────────────────────────────────────────────

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(TASK_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange taskDlxExchange() {
        return new DirectExchange(TASK_DLX_EXCHANGE, true, false);
    }

    // ── DLQ ───────────────────────────────────────────────────────────────────

    @Bean
    public Queue taskDlq() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    @Bean
    public Binding dlqBinding(Queue taskDlq, DirectExchange taskDlxExchange) {
        return BindingBuilder.bind(taskDlq).to(taskDlxExchange).with(ROUTING_KEY_DLQ);
    }

    // ── Priority queues ───────────────────────────────────────────────────────

    private Map<String, Object> priorityQueueArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", TASK_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", ROUTING_KEY_DLQ);
        return args;
    }

    @Bean
    public Queue highPriorityQueue() {
        return QueueBuilder.durable(QUEUE_HIGH)
                .withArguments(priorityQueueArgs())
                .build();
    }

    @Bean
    public Queue mediumPriorityQueue() {
        return QueueBuilder.durable(QUEUE_MEDIUM)
                .withArguments(priorityQueueArgs())
                .build();
    }

    @Bean
    public Queue lowPriorityQueue() {
        return QueueBuilder.durable(QUEUE_LOW)
                .withArguments(priorityQueueArgs())
                .build();
    }

    // ── Bindings ──────────────────────────────────────────────────────────────

    @Bean
    public Binding highBinding(Queue highPriorityQueue, DirectExchange taskExchange) {
        return BindingBuilder.bind(highPriorityQueue).to(taskExchange).with(ROUTING_KEY_HIGH);
    }

    @Bean
    public Binding mediumBinding(Queue mediumPriorityQueue, DirectExchange taskExchange) {
        return BindingBuilder.bind(mediumPriorityQueue).to(taskExchange).with(ROUTING_KEY_MEDIUM);
    }

    @Bean
    public Binding lowBinding(Queue lowPriorityQueue, DirectExchange taskExchange) {
        return BindingBuilder.bind(lowPriorityQueue).to(taskExchange).with(ROUTING_KEY_LOW);
    }

    // ── Admin (declares topology on connect) ──────────────────────────────────

    @Bean
    public RabbitAdmin testRabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    // ── Message converter (mirrors WorkerConfig to avoid duplicate-bean conflict) ─

    @Bean
    public MessageConverter testJsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
