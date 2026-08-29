package com.scheduler.api.validation;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates Quartz-format cron expressions using {@code cron-utils 9.2.1}.
 *
 * <p>The Quartz cron format supports 6–7 fields:
 * {@code seconds minutes hours day-of-month month day-of-week [year]}
 *
 * <p>cron-utils provides a clean API for parsing and validating cron expressions
 * without requiring the full Quartz scheduler on the classpath.
 */
public class CronExpressionValidator implements ConstraintValidator<ValidCronExpression, String> {

    private static final CronParser QUARTZ_PARSER = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // optional field — null/blank is valid
        }
        try {
            QUARTZ_PARSER.parse(value).validate();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
