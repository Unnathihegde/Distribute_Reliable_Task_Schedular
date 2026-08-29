package com.scheduler.api.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers the shared module's JPA repositories and entity classes with Spring Data JPA.
 *
 * <p>Although {@link com.scheduler.api.ApiApplication} declares
 * {@code scanBasePackages = "com.scheduler"}, Spring Boot's JPA auto-configuration
 * does NOT automatically discover repository interfaces in sub-packages of other
 * Maven modules unless {@code @EnableJpaRepositories} is explicitly declared.
 * Without this configuration, startup fails with:
 * <pre>
 * NoSuchBeanDefinitionException: No qualifying bean of type 'TaskRepository' available
 * </pre>
 *
 * <p>{@code @EntityScan} is declared here for the same reason — Hibernate needs to be
 * told explicitly where the {@code @Entity} classes live when they are in a separate JAR.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.scheduler.shared.repository")
@EntityScan(basePackages = "com.scheduler.shared.domain")
public class JpaConfig {
    // No beans needed — the annotations do all the work.
}
