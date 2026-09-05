package com.scheduler.api.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Explicit JPA configuration for the API service.
 *
 * <p>Why is this needed?</p>
 * <p>{@code @SpringBootApplication(scanBasePackages = "com.scheduler")} covers
 * component scanning, but Spring Data JPA's repository factory must be explicitly
 * told where to look for repository interfaces via {@code @EnableJpaRepositories}.
 * Without this, startup fails with:
 * {@code No qualifying bean of type 'TaskRepository' available}</p>
 *
 * <p>Similarly, {@code @EntityScan} must be set to pick up {@code @Entity} classes
 * from the shared module's package — Hibernate won't find them otherwise because
 * the shared JAR's package differs from {@code com.scheduler.api}.</p>
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.scheduler.shared.repository")
@EntityScan(basePackages = "com.scheduler.shared.domain")
public class JpaConfig {
    // No bean definitions needed — the annotations do the wiring.
}
