package com.scheduler.shared.statemachine;

import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static com.scheduler.shared.domain.TaskStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TaskStateMachine}.
 *
 * <p><strong>No Spring context, no database.</strong> The repository is mocked
 * with Mockito so tests run in milliseconds and require no infrastructure.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>All 12 legal transitions from blueprint Section 7 via {@code canTransition}</li>
 *   <li>Terminal-state rejections: {@code SUCCESS → *} and {@code CANCELLED → *}</li>
 *   <li>Specific illegal transitions: {@code RUNNING → SCHEDULED}, {@code QUEUED → SUCCESS}</li>
 *   <li>Additional illegal transitions across non-adjacent states</li>
 *   <li>{@code transition()} happy path: repo returns 1 → no exception</li>
 *   <li>{@code transition()} invalid pair: throws before touching repo</li>
 *   <li>{@code transition()} CAS miss (repo returns 0) → throws {@link IllegalStateTransitionException}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskStateMachine")
class TaskStateMachineTest {

    @Mock
    private TaskRepository taskRepository;

    private TaskStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new TaskStateMachine(taskRepository);
    }

    // =========================================================================
    // 1. Legal transitions — all 12 from blueprint Section 7
    // =========================================================================

    @Nested
    @DisplayName("canTransition — all 12 legal transitions return true")
    class LegalTransitions {

        /**
         * Parameterized test covering every legal (from, to) pair.
         * Count: 12 rows = 12 legal transitions.
         *
         * SCHEDULED   → QUEUED          (1)  scheduler claims and dispatches
         * SCHEDULED   → CANCELLED       (2)  user cancellation
         * QUEUED      → RUNNING         (3)  worker acquires lease
         * QUEUED      → CANCELLED       (4)  user cancellation
         * QUEUED      → SCHEDULED       (5)  lease-expiry recovery
         * RUNNING     → SUCCESS         (6)  execution succeeded
         * RUNNING     → RETRY_WAIT      (7)  execution failed, retries remain
         * RUNNING     → FAILED          (8)  execution failed, no retries remain
         * RUNNING     → QUEUED          (9)  lease expired — reset for re-dispatch
         * RETRY_WAIT  → SCHEDULED       (10) retry delay elapsed
         * FAILED      → DEAD_LETTER     (11) automatic escalation
         * DEAD_LETTER → SCHEDULED       (12) manual retry by operator
         */
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "SCHEDULED,   QUEUED",       // (1)
            "SCHEDULED,   CANCELLED",    // (2)
            "QUEUED,      RUNNING",      // (3)
            "QUEUED,      CANCELLED",    // (4)
            "QUEUED,      SCHEDULED",    // (5)
            "RUNNING,     SUCCESS",      // (6)
            "RUNNING,     RETRY_WAIT",   // (7)
            "RUNNING,     FAILED",       // (8)
            "RUNNING,     QUEUED",       // (9)
            "RETRY_WAIT,  SCHEDULED",    // (10)
            "FAILED,      DEAD_LETTER",  // (11)
            "DEAD_LETTER, SCHEDULED",    // (12)
        })
        void allLegalTransitionsAreAccepted(TaskStatus from, TaskStatus to) {
            assertThat(stateMachine.canTransition(from, to))
                .as("Expected legal transition %s → %s to return true", from, to)
                .isTrue();
        }
    }

    // =========================================================================
    // 2. Illegal transitions — terminal states (SUCCESS and CANCELLED)
    // =========================================================================

    @Nested
    @DisplayName("canTransition — SUCCESS is terminal (no outgoing transitions)")
    class SuccessIsTerminal {

        @Test
        @DisplayName("SUCCESS → SCHEDULED is rejected")
        void successToScheduled() {
            assertThat(stateMachine.canTransition(SUCCESS, SCHEDULED)).isFalse();
        }

        @Test
        @DisplayName("SUCCESS → QUEUED is rejected")
        void successToQueued() {
            assertThat(stateMachine.canTransition(SUCCESS, QUEUED)).isFalse();
        }

        @Test
        @DisplayName("SUCCESS → RUNNING is rejected")
        void successToRunning() {
            assertThat(stateMachine.canTransition(SUCCESS, RUNNING)).isFalse();
        }

        @Test
        @DisplayName("SUCCESS → RETRY_WAIT is rejected")
        void successToRetryWait() {
            assertThat(stateMachine.canTransition(SUCCESS, RETRY_WAIT)).isFalse();
        }

        @Test
        @DisplayName("SUCCESS → FAILED is rejected")
        void successToFailed() {
            assertThat(stateMachine.canTransition(SUCCESS, FAILED)).isFalse();
        }

        @Test
        @DisplayName("SUCCESS → DEAD_LETTER is rejected")
        void successToDeadLetter() {
            assertThat(stateMachine.canTransition(SUCCESS, DEAD_LETTER)).isFalse();
        }

        @Test
        @DisplayName("SUCCESS → CANCELLED is rejected")
        void successToCancelled() {
            assertThat(stateMachine.canTransition(SUCCESS, CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("SUCCESS → SUCCESS (self-loop) is rejected")
        void successToSuccess() {
            assertThat(stateMachine.canTransition(SUCCESS, SUCCESS)).isFalse();
        }
    }

    @Nested
    @DisplayName("canTransition — CANCELLED is terminal (no outgoing transitions)")
    class CancelledIsTerminal {

        @Test
        @DisplayName("CANCELLED → SCHEDULED is rejected")
        void cancelledToScheduled() {
            assertThat(stateMachine.canTransition(CANCELLED, SCHEDULED)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED → QUEUED is rejected")
        void cancelledToQueued() {
            assertThat(stateMachine.canTransition(CANCELLED, QUEUED)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED → RUNNING is rejected")
        void cancelledToRunning() {
            assertThat(stateMachine.canTransition(CANCELLED, RUNNING)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED → SUCCESS is rejected")
        void cancelledToSuccess() {
            assertThat(stateMachine.canTransition(CANCELLED, SUCCESS)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED → DEAD_LETTER is rejected")
        void cancelledToDeadLetter() {
            assertThat(stateMachine.canTransition(CANCELLED, DEAD_LETTER)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED → CANCELLED (self-loop) is rejected")
        void cancelledToCancelled() {
            assertThat(stateMachine.canTransition(CANCELLED, CANCELLED)).isFalse();
        }
    }

    // =========================================================================
    // 3. Explicitly called-out illegal transitions from blueprint Section 7
    // =========================================================================

    @Nested
    @DisplayName("canTransition — specific illegal transitions from blueprint Section 7")
    class SpecificIllegalTransitions {

        /**
         * Blueprint Section 7 explicitly states:
         * "RUNNING → SCHEDULED (must go through RETRY_WAIT or lease-expiry recovery)"
         */
        @Test
        @DisplayName("RUNNING → SCHEDULED is rejected (must go via RETRY_WAIT or lease recovery)")
        void runningToScheduledIsIllegal() {
            assertThat(stateMachine.canTransition(RUNNING, SCHEDULED))
                .as("RUNNING → SCHEDULED must be rejected; path is RUNNING → RETRY_WAIT → SCHEDULED")
                .isFalse();
        }

        /**
         * Blueprint Section 7 explicitly states:
         * "QUEUED → SUCCESS (must go through RUNNING)"
         */
        @Test
        @DisplayName("QUEUED → SUCCESS is rejected (must go via RUNNING)")
        void queuedToSuccessIsIllegal() {
            assertThat(stateMachine.canTransition(QUEUED, SUCCESS))
                .as("QUEUED → SUCCESS must be rejected; worker must claim lease first")
                .isFalse();
        }

        /**
         * Blueprint Section 7 also makes this implicitly illegal —
         * RUNNING can only go to SUCCESS, RETRY_WAIT, FAILED, or QUEUED (lease expiry).
         */
        @Test
        @DisplayName("RUNNING → CANCELLED is rejected (cannot cancel a running task)")
        void runningToCancelledIsIllegal() {
            assertThat(stateMachine.canTransition(RUNNING, CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("RUNNING → DEAD_LETTER is rejected (must go via FAILED)")
        void runningToDeadLetterIsIllegal() {
            assertThat(stateMachine.canTransition(RUNNING, DEAD_LETTER))
                .as("RUNNING → DEAD_LETTER must be rejected; path is RUNNING → FAILED → DEAD_LETTER")
                .isFalse();
        }

        @Test
        @DisplayName("RETRY_WAIT → RUNNING is rejected (must re-enter via SCHEDULED)")
        void retryWaitToRunningIsIllegal() {
            assertThat(stateMachine.canTransition(RETRY_WAIT, RUNNING)).isFalse();
        }

        @Test
        @DisplayName("RETRY_WAIT → SUCCESS is rejected")
        void retryWaitToSuccessIsIllegal() {
            assertThat(stateMachine.canTransition(RETRY_WAIT, SUCCESS)).isFalse();
        }

        @Test
        @DisplayName("DEAD_LETTER → RUNNING is rejected (must re-enter via SCHEDULED)")
        void deadLetterToRunningIsIllegal() {
            assertThat(stateMachine.canTransition(DEAD_LETTER, RUNNING)).isFalse();
        }

        @Test
        @DisplayName("FAILED → SCHEDULED is rejected (must escalate to DEAD_LETTER first)")
        void failedToScheduledIsIllegal() {
            assertThat(stateMachine.canTransition(FAILED, SCHEDULED)).isFalse();
        }

        @Test
        @DisplayName("SCHEDULED → RUNNING is rejected (must pass through QUEUED)")
        void scheduledToRunningIsIllegal() {
            assertThat(stateMachine.canTransition(SCHEDULED, RUNNING)).isFalse();
        }

        @Test
        @DisplayName("SCHEDULED → SUCCESS is rejected (cannot skip the entire lifecycle)")
        void scheduledToSuccessIsIllegal() {
            assertThat(stateMachine.canTransition(SCHEDULED, SUCCESS)).isFalse();
        }
    }

    // =========================================================================
    // 4. Null safety
    // =========================================================================

    @Nested
    @DisplayName("canTransition — null arguments")
    class NullHandling {

        @Test
        @DisplayName("null from-state returns false")
        void nullFromReturnsFalse() {
            assertThat(stateMachine.canTransition(null, QUEUED)).isFalse();
        }

        @Test
        @DisplayName("null to-state returns false")
        void nullToReturnsFalse() {
            assertThat(stateMachine.canTransition(SCHEDULED, null)).isFalse();
        }

        @Test
        @DisplayName("both null returns false")
        void bothNullReturnsFalse() {
            assertThat(stateMachine.canTransition(null, null)).isFalse();
        }
    }

    // =========================================================================
    // 5. transition() — the CAS enforcement method
    // =========================================================================

    @Nested
    @DisplayName("transition() — database CAS enforcement")
    class TransitionEnforcement {

        private final UUID taskId = UUID.randomUUID();

        @Test
        @DisplayName("succeeds when repository CAS UPDATE returns 1 (happy path)")
        void transitionSucceedsWhenRepoReturnsOne() {
            // Arrange: repo signals 1 row updated (CAS succeeded)
            when(taskRepository.transitionStatus(taskId, SCHEDULED, QUEUED)).thenReturn(1);

            // Act + Assert: no exception thrown
            assertThatCode(() -> stateMachine.transition(taskId, SCHEDULED, QUEUED))
                .doesNotThrowAnyException();

            // Verify: the CAS query was actually called (not a read-then-write)
            verify(taskRepository, times(1)).transitionStatus(taskId, SCHEDULED, QUEUED);
            verifyNoMoreInteractions(taskRepository);
        }

        @Test
        @DisplayName("throws IllegalStateTransitionException when CAS UPDATE returns 0 (concurrent transition)")
        void transitionThrowsWhenRepoReturnsZero() {
            // Arrange: repo signals 0 rows updated (another process already moved the task)
            when(taskRepository.transitionStatus(taskId, SCHEDULED, QUEUED)).thenReturn(0);

            // Act + Assert
            assertThatThrownBy(() -> stateMachine.transition(taskId, SCHEDULED, QUEUED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("CAS_MISS")
                .hasMessageContaining(taskId.toString())
                .hasMessageContaining("SCHEDULED")
                .hasMessageContaining("QUEUED");
        }

        @Test
        @DisplayName("throws IllegalStateTransitionException for invalid transition — repo is NOT called")
        void invalidTransitionThrowsWithoutCallingRepo() {
            // RUNNING → SCHEDULED is illegal (must go via RETRY_WAIT)
            assertThatThrownBy(() -> stateMachine.transition(taskId, RUNNING, SCHEDULED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("INVALID_TRANSITION");

            // Critically: the repository must NOT have been called — we didn't touch the DB
            verifyNoInteractions(taskRepository);
        }

        @Test
        @DisplayName("throws IllegalStateTransitionException for SUCCESS → QUEUED — repo is NOT called")
        void terminalSuccessTransitionDoesNotCallRepo() {
            assertThatThrownBy(() -> stateMachine.transition(taskId, SUCCESS, QUEUED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("INVALID_TRANSITION");

            verifyNoInteractions(taskRepository);
        }

        @Test
        @DisplayName("throws IllegalStateTransitionException for CANCELLED → SCHEDULED — repo is NOT called")
        void terminalCancelledTransitionDoesNotCallRepo() {
            assertThatThrownBy(() -> stateMachine.transition(taskId, CANCELLED, SCHEDULED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("INVALID_TRANSITION");

            verifyNoInteractions(taskRepository);
        }

        @Test
        @DisplayName("CAS miss exception carries correct from/to/taskId metadata")
        void casMissExceptionCarriesMetadata() {
            when(taskRepository.transitionStatus(taskId, QUEUED, RUNNING)).thenReturn(0);

            IllegalStateTransitionException ex = catchThrowableOfType(
                () -> stateMachine.transition(taskId, QUEUED, RUNNING),
                IllegalStateTransitionException.class
            );

            assertThat(ex.getTaskId()).isEqualTo(taskId);
            assertThat(ex.getFrom()).isEqualTo(QUEUED);
            assertThat(ex.getTo()).isEqualTo(RUNNING);
        }

        @Test
        @DisplayName("RUNNING → SUCCESS CAS succeeds — worker marking task done")
        void runningToSuccessCasSucceeds() {
            when(taskRepository.transitionStatus(taskId, RUNNING, SUCCESS)).thenReturn(1);

            assertThatCode(() -> stateMachine.transition(taskId, RUNNING, SUCCESS))
                .doesNotThrowAnyException();

            verify(taskRepository).transitionStatus(taskId, RUNNING, SUCCESS);
        }

        @Test
        @DisplayName("RUNNING → RETRY_WAIT CAS succeeds — worker recording failure with retries remaining")
        void runningToRetryWaitCasSucceeds() {
            when(taskRepository.transitionStatus(taskId, RUNNING, RETRY_WAIT)).thenReturn(1);

            assertThatCode(() -> stateMachine.transition(taskId, RUNNING, RETRY_WAIT))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("DEAD_LETTER → SCHEDULED CAS succeeds — operator manual retry")
        void deadLetterToScheduledCasSucceeds() {
            when(taskRepository.transitionStatus(taskId, DEAD_LETTER, SCHEDULED)).thenReturn(1);

            assertThatCode(() -> stateMachine.transition(taskId, DEAD_LETTER, SCHEDULED))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FAILED → DEAD_LETTER CAS succeeds — automatic escalation")
        void failedToDeadLetterCasSucceeds() {
            when(taskRepository.transitionStatus(taskId, FAILED, DEAD_LETTER)).thenReturn(1);

            assertThatCode(() -> stateMachine.transition(taskId, FAILED, DEAD_LETTER))
                .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // 6. Legal transition table completeness check
    // =========================================================================

    @Nested
    @DisplayName("getLegalTransitions() — transition table completeness")
    class TransitionTableCompleteness {

        @Test
        @DisplayName("legal transition table contains exactly 12 total transitions")
        void transitionTableHas12Transitions() {
            long totalTransitions = stateMachine.getLegalTransitions()
                .values().stream()
                .mapToLong(set -> set.size())
                .sum();

            assertThat(totalTransitions)
                .as("Expected exactly 12 legal transitions as defined in blueprint Section 7")
                .isEqualTo(12L);
        }

        @Test
        @DisplayName("SUCCESS has no outgoing transitions in the table")
        void successHasNoOutgoingTransitions() {
            assertThat(stateMachine.getLegalTransitions()).doesNotContainKey(SUCCESS);
        }

        @Test
        @DisplayName("CANCELLED has no outgoing transitions in the table")
        void cancelledHasNoOutgoingTransitions() {
            assertThat(stateMachine.getLegalTransitions()).doesNotContainKey(CANCELLED);
        }
    }
}
