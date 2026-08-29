package com.scheduler.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that a task type string is one of the registered handler types:
 * {@code EMAIL}, {@code HTTP}, {@code DEMO}.
 *
 * <p>New task types must be added both here and in the {@code TaskHandlerRegistry}
 * (Worker module) — keeping both in sync is a known maintenance point.
 */
@Documented
@Constraint(validatedBy = TaskTypeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTaskType {
    String message() default "taskType must be one of: EMAIL, HTTP, DEMO";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
