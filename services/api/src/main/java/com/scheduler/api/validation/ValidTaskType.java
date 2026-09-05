package com.scheduler.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that the annotated {@code String} is a recognised task type.
 *
 * <p>Accepted values: {@code EMAIL}, {@code HTTP}, {@code DEMO}</p>
 *
 * <p>Why a whitelist instead of an open enum?</p>
 * <p>Task types are a controlled extension point: the worker pool must know how
 * to execute a type before it is accepted. An open string field would let clients
 * submit types with no handler, creating unprocessable tasks. A whitelist enforced
 * at the API boundary prevents that class of bug.</p>
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
