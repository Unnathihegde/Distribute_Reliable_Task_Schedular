package com.scheduler.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that a string is a valid Quartz-compatible cron expression.
 *
 * <p>Null and blank values pass validation - cronExpression is an optional field.
 * A non-null, non-blank value must be parseable by the Quartz cron parser.
 *
 * <p>Quartz format uses 6-7 fields (seconds minutes hours day-of-month month day-of-week [year]),
 * unlike Unix cron which uses 5 fields. Example: {@code "0 0 9 * * ?"} runs every day at 9am.
 */
@Documented
@Constraint(validatedBy = CronExpressionValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCronExpression {
    String message() default "cronExpression must be a valid Quartz cron expression (e.g. '0 0 9 * * ?')";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
