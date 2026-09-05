package com.scheduler.scheduler.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Declares the RabbitMQ topology for task dispatch and dead-letter handling.
 *
 * <ul>
 *   <li>Exchange: {@code task.exchange} (DirectExchange)</li>
 *   <li>Priority Queues: {@code task.high}, {@code task.medium}, {@code task.low}</li>
 *   <li>DLX: {@code task.dlx.exchange} (DirectExchange)</li>
 *   <li>DLQ: {@code task.dlq}</li>
 * </ul>
 */
@Configuration
public class RabbitMqConfig {

    public static final String TASK_EXCHANGE = "task.exchange";
    public static final String TASK_DLX_EXCHANGE = "task.dlx.exchange";

    public static final String QUEUE_HIGH = "task.high";
    public static final String QUEUE_MEDIUM = "task.medium";
    public static final String QUEUE_LOW = "task.low";
    public static final String QUEUE_DLQ = "task.dlq";

    public static final String ROUTING_KEY_HIGH = "high";
    public static final String ROUTING_KEY_MEDIUM = "medium";
    public static final String ROUTING_KEY_LOW = "low";
    public static final String ROUTING_KEY_DLQ = "task.dlq";

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(TASK_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange taskDlxExchange() {
        return new DirectExchange(TASK_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue taskDlq() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    @Bean
    public Binding dlqBinding(Queue taskDlq, DirectExchange taskDlxExchange) {
        return BindingBuilder.bind(taskDlq).to(taskDlxExchange).with(ROUTING_KEY_DLQ);
    }

    private Map<String, Object> queueArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", TASK_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", ROUTING_KEY_DLQ);
        return args;
    }

    @Bean
    public Queue highPriorityQueue() {
        return QueueBuilder.durable(QUEUE_HIGH)
                .withArguments(queueArgs())
                .build();
    }

    @Bean
    public Queue mediumPriorityQueue() {
        return QueueBuilder.durable(QUEUE_MEDIUM)
                .withArguments(queueArgs())
                .build();
    }

    @Bean
    public Queue lowPriorityQueue() {
        return QueueBuilder.durable(QUEUE_LOW)
                .withArguments(queueArgs())
                .build();
    }

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

    @Bean
    public RabbitAdmin rabbitAdmin(org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
