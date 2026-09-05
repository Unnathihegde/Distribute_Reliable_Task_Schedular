package com.scheduler.worker.shutdown;

import com.scheduler.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles graceful shutdown of the Worker service on SIGTERM / Spring context destruction.
 *
 * <p>Stops RabbitMQ listener containers from consuming new messages, then waits up to
 * {@link WorkerProperties#getShutdownTimeoutSeconds()} for active in-flight tasks to finish.</p>
 */
@Component
public class WorkerGracefulShutdownHandler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerGracefulShutdownHandler.class);

    private final RabbitListenerEndpointRegistry registry;
    private final WorkerProperties properties;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WorkerGracefulShutdownHandler(RabbitListenerEndpointRegistry registry, WorkerProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public void taskStarted() {
        activeTasks.incrementAndGet();
    }

    public void taskCompleted() {
        activeTasks.decrementAndGet();
    }

    public int getActiveTaskCount() {
        return activeTasks.get();
    }

    @Override
    public void start() {
        running.set(true);
        log.info("Worker Graceful Shutdown Handler active.");
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }

        log.info("SIGTERM/Shutdown signal received. Stopping RabbitMQ listeners to drain in-flight tasks...");
        try {
            registry.stop();
        } catch (Exception e) {
            log.warn("Error stopping RabbitMQ listener registry: {}", e.getMessage());
        }

        int timeout = properties.getShutdownTimeoutSeconds();
        long deadline = System.currentTimeMillis() + (timeout * 1000L);

        while (activeTasks.get() > 0 && System.currentTimeMillis() < deadline) {
            log.info("Waiting for {} in-flight tasks to complete before shutdown...", activeTasks.get());
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (activeTasks.get() > 0) {
            log.warn("Shutdown timeout reached with {} tasks still in-flight.", activeTasks.get());
        } else {
            log.info("All in-flight tasks drained successfully. Worker shutdown complete.");
        }

        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
