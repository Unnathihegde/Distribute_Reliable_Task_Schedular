package com.scheduler.api.config;

import com.scheduler.shared.repository.TaskRepository;
import com.scheduler.shared.statemachine.TaskStateMachine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the shared module's {@link TaskStateMachine} as a Spring bean in the
 * API application context.
 *
 * <p>Although {@link TaskStateMachine} is annotated with {@code @Component}, it lives
 * in the {@code com.scheduler.shared} package which is covered by the component scan.
 * This explicit {@code @Bean} declaration is provided as a belt-and-suspenders safety net
 * and to make the dependency wiring visible in the API module.
 *
 * <p>The state machine is NOT re-implemented here — it is the exact same component
 * from the shared module, which already has full unit test coverage of every
 * legal and illegal transition from blueprint Section 7.
 */
@Configuration
public class StateMachineConfig {

    @Bean
    public TaskStateMachine taskStateMachine(TaskRepository taskRepository) {
        return new TaskStateMachine(taskRepository);
    }
}
