package com.scheduler.scheduler.config;

import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.metrics.TaskMetrics;
import com.scheduler.shared.repository.TaskRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Registers Prometheus gauges for RabbitMQ queue depth and PostgreSQL task counts per status.
 *
 * <p><strong>Queue Depth Gauge Mechanism:</strong> Uses {@link RabbitAdmin#getQueueProperties(String)}
 * which queries the RabbitMQ broker on-scrape (or on gauge evaluation) to retrieve the exact
 * {@code QUEUE_MESSAGE_COUNT} property. This provides dynamic real-time depth without background polling.</p>
 */
@Configuration
public class QueueDepthGaugeConfig {

    private final RabbitAdmin rabbitAdmin;
    private final TaskRepository taskRepository;
    private final TaskMetrics taskMetrics;

    public QueueDepthGaugeConfig(@org.springframework.lang.Nullable RabbitAdmin rabbitAdmin,
                                 TaskRepository taskRepository,
                                 TaskMetrics taskMetrics) {
        this.rabbitAdmin = rabbitAdmin;
        this.taskRepository = taskRepository;
        this.taskMetrics = taskMetrics;
    }

    @PostConstruct
    public void registerGauges() {
        String[] queues = {
                RabbitMqConfig.QUEUE_HIGH,
                RabbitMqConfig.QUEUE_MEDIUM,
                RabbitMqConfig.QUEUE_LOW,
                RabbitMqConfig.QUEUE_DLQ
        };

        if (rabbitAdmin != null) {
            for (String queue : queues) {
                taskMetrics.registerQueueDepthGauge(queue, rabbitAdmin, admin -> getQueueDepth(admin, queue));
            }
        }

        for (TaskStatus status : TaskStatus.values()) {
            taskMetrics.registerTasksInStatusGauge(status.name(), taskRepository, repo -> getTaskCountByStatus(repo, status));
        }
    }

    private double getQueueDepth(RabbitAdmin admin, String queueName) {
        try {
            Properties props = admin.getQueueProperties(queueName);
            if (props != null && props.containsKey(RabbitAdmin.QUEUE_MESSAGE_COUNT)) {
                Object countObj = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
                if (countObj instanceof Number number) {
                    return number.doubleValue();
                }
            }
        } catch (Exception ignored) {
            // Safe fallback during tests or prior to queue declaration
        }
        return 0.0;
    }

    private double getTaskCountByStatus(TaskRepository repo, TaskStatus status) {
        try {
            return repo.countByStatus(status);
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}
