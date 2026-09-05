package com.scheduler.shared.repository;

import com.scheduler.shared.domain.TaskAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link TaskAttempt} entities.
 */
public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, UUID> {

    /**
     * Retrieves all attempt history records for a task ordered by attempt_number ASC.
     */
    List<TaskAttempt> findByTaskIdOrderByAttemptNumberAsc(UUID taskId);
}
