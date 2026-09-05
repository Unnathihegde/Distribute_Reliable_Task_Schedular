package com.scheduler.api.validation;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates Quartz cron expressions using {@code cron-utils}.
 *
 * <h3>Why cron-utils instead of Quartz?</h3>
 * <p>Pulling in {@code quartz} just for {@code CronExpression.validateExpression()} would
 * bring ~5 MB of scheduler infrastructure that we don't use. {@code cron-utils} is a
 * lightweight library (~300 KB) dedicated to cron parsing and validation, with explicit
 * support for Quartz syntax (6 or 7 fields including seconds and optional year).</p>
 *
 * <h3>Quartz cron format</h3>
 * <pre>
 *   seconds minutes hours day-of-month month day-of-week [year]
 *   Example: 0 0 12 * * ?   (every day at noon)
 * </pre>
 */
public class CronExpressionValidator implements ConstraintValidator<ValidCronExpression, String> {

    private final CronParser parser = new CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // null/blank is valid — use @NotBlank if required
        }
        try {
            parser.parse(value).validate();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
