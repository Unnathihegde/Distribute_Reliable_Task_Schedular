package com.scheduler.shared.statemachine;

import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.exception.TaskNotFoundException;
import com.scheduler.shared.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Task State Machine — Transition Matrix & Cancel Tests")
class TaskStateMachineTest {

    @Mock
    private TaskRepository taskRepository;

    private TaskStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new TaskStateMachine(taskRepository);
    }

    @Test
    @DisplayName("RETRY_WAIT transitions strictly to SCHEDULED (and not QUEUED)")
    void retryWaitTransitionsToScheduled() {
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.RETRY_WAIT, TaskStatus.SCHEDULED)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.RETRY_WAIT, TaskStatus.QUEUED)).isFalse();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.RETRY_WAIT, TaskStatus.RUNNING)).isFalse();
    }

    @Test
    @DisplayName("SCHEDULED transitions to QUEUED or CANCELLED")
    void scheduledTransitions() {
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.SCHEDULED, TaskStatus.QUEUED)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.SCHEDULED, TaskStatus.CANCELLED)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.SCHEDULED, TaskStatus.RUNNING)).isFalse();
    }

    @Test
    @DisplayName("QUEUED transitions to RUNNING, CANCELLED, or SCHEDULED")
    void queuedTransitions() {
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.QUEUED, TaskStatus.RUNNING)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.QUEUED, TaskStatus.CANCELLED)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.QUEUED, TaskStatus.SCHEDULED)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.QUEUED, TaskStatus.SUCCESS)).isFalse();
    }

    @Test
    @DisplayName("RUNNING transitions to SUCCESS, FAILED, RETRY_WAIT, or QUEUED")
    void runningTransitions() {
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.RUNNING, TaskStatus.SUCCESS)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.RUNNING, TaskStatus.FAILED)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.RUNNING, TaskStatus.RETRY_WAIT)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.RUNNING, TaskStatus.QUEUED)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.RUNNING, TaskStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("FAILED transitions to DEAD_LETTER")
    void failedTransitions() {
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.FAILED, TaskStatus.DEAD_LETTER)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.FAILED, TaskStatus.QUEUED)).isFalse();
    }

    @Test
    @DisplayName("DEAD_LETTER permits transition to SCHEDULED for manual retry")
    void deadLetterTransitions() {
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.DEAD_LETTER, TaskStatus.SCHEDULED)).isTrue();
        assertThat(stateMachine.isTransitionAllowed(TaskStatus.DEAD_LETTER, TaskStatus.RUNNING)).isFalse();
    }

    @Test
    @DisplayName("Terminal states (SUCCESS, CANCELLED) have no outgoing transitions")
    void terminalStatesHaveNoOutgoingTransitions() {
        for (TaskStatus terminal : new TaskStatus[]{TaskStatus.SUCCESS, TaskStatus.CANCELLED}) {
            for (TaskStatus target : TaskStatus.values()) {
                assertThat(stateMachine.isTransitionAllowed(terminal, target))
                        .withFailMessage("Expected no transition from terminal state %s to %s", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("Cancel allowed for SCHEDULED task")
    void cancelScheduledTask() {
        UUID taskId = UUID.randomUUID();
        Task task = Task.builder().status(TaskStatus.SCHEDULED).build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Task cancelled = stateMachine.cancel(taskId);
        assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Cancel throws exception for RUNNING task")
    void cancelRunningTaskThrows() {
        UUID taskId = UUID.randomUUID();
        Task task = Task.builder().status(TaskStatus.RUNNING).build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> stateMachine.cancel(taskId))
                .isInstanceOf(IllegalStateTransitionException.class);
    }
}
