package com.scheduler.scheduler.concurrency;

import com.scheduler.scheduler.polling.TaskClaimService;
import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskAttemptRepository;
import com.scheduler.shared.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Scheduler Service — FOR UPDATE SKIP LOCKED Concurrency Test")
class SchedulerConcurrencyTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskAttemptRepository taskAttemptRepository;

    @Autowired
    private TaskClaimService taskClaimService;

    @BeforeEach
    void setUp() {
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
    }

    @Test
    @DisplayName("Concurrent scheduler instances claim disjoint task sets without duplicates or lost updates")
    void concurrentPolling_claimsDisjointSets() throws Exception {
        int totalTasks = 20;
        Instant past = Instant.now().minusSeconds(60);

        List<Task> createdTasks = new ArrayList<>();
        for (int i = 0; i < totalTasks; i++) {
            createdTasks.add(Task.builder()
                    .taskType("CONCURRENCY_TEST")
                    .payload("{\"index\":" + i + "}")
                    .priority(Priority.MEDIUM)
                    .status(TaskStatus.SCHEDULED)
                    .scheduledAt(past.plusMillis(i * 10))
                    .build());
        }
        taskRepository.saveAll(createdTasks);

        Set<UUID> allTaskIds = createdTasks.stream().map(Task::getId).collect(Collectors.toSet());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<List<UUID>> claimTask = () -> {
            readyLatch.countDown();
            startLatch.await(); // Synchronize start
            List<Task> claimed = taskClaimService.claimBatch(10);
            return claimed.stream().map(Task::getId).toList();
        };

        Future<List<UUID>> future1 = executor.submit(claimTask);
        Future<List<UUID>> future2 = executor.submit(claimTask);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release both threads simultaneously

        List<UUID> claimed1 = future1.get(10, TimeUnit.SECONDS);
        List<UUID> claimed2 = future2.get(10, TimeUnit.SECONDS);

        executor.shutdown();

        Set<UUID> set1 = new HashSet<>(claimed1);
        Set<UUID> set2 = new HashSet<>(claimed2);

        // 1. Assert zero overlap (SKIP LOCKED ensures no row is locked by both)
        Set<UUID> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        assertThat(intersection)
                .withFailMessage("Concurrency bug! Tasks were claimed by both threads: %s", intersection)
                .isEmpty();

        // 2. Assert complete coverage (all 20 tasks claimed across both threads)
        Set<UUID> union = new HashSet<>(set1);
        union.addAll(set2);
        assertThat(union)
                .withFailMessage("Expected all 20 tasks to be claimed, but only claimed %d", union.size())
                .isEqualTo(allTaskIds);

        // 3. Verify in DB that all tasks are now QUEUED
        List<Task> dbTasks = taskRepository.findAll();
        assertThat(dbTasks).allMatch(t -> t.getStatus() == TaskStatus.QUEUED);
    }
}
