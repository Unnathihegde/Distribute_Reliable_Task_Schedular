package com.scheduler.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that the annotated {@code String} is a syntactically valid
 * <a href="https://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/crontrigger.html">
 * Quartz cron expression</a>.
 *
 * <p>Null values pass validation — use {@code @NotBlank} separately if the field
 * is mandatory.</p>
 */
@Documented
@Constraint(validatedBy = CronExpressionValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCronExpression {

    String message() default "cronExpression is not a valid Quartz cron expression";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
