package com.scheduler.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * Validates that a task type string is in the whitelist of registered task types.
 *
 * <p>Null values pass validation (nullability is enforced by a separate {@code @NotBlank}).
 */
public class TaskTypeValidator implements ConstraintValidator<ValidTaskType, String> {

    /**
     * Registered task types. Must be kept in sync with the TaskHandlerRegistry
     * in the Worker module. Adding a new type here without a handler will result
     * in a runtime error when the worker tries to execute it.
     */
    private static final Set<String> VALID_TYPES = Set.of("EMAIL", "HTTP", "DEMO");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // nullability handled by @NotBlank
        }
        return VALID_TYPES.contains(value.toUpperCase());
    }
}
