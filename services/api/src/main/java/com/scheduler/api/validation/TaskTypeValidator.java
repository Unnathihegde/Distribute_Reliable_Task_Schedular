package com.scheduler.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * Implementation of {@link ValidTaskType}.
 *
 * <p>Maintains a static whitelist of supported task types. When a new worker
 * handler is added, this set is the single place that needs updating on the
 * API side — a deliberate coupling point that makes extension visible.</p>
 */
public class TaskTypeValidator implements ConstraintValidator<ValidTaskType, String> {

    private static final Set<String> ALLOWED_TYPES = Set.of("EMAIL", "HTTP", "DEMO");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null is handled by @NotBlank on the same field — don't double-report
        if (value == null) return true;
        return ALLOWED_TYPES.contains(value.toUpperCase());
    }
}
