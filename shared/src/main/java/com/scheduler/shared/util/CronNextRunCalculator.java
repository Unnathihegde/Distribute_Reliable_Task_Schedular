package com.scheduler.shared.util;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Utility for parsing Quartz cron expressions and calculating next occurrence timestamps.
 */
public class CronNextRunCalculator {

    private static final CronParser QUARTZ_PARSER = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
    );

    private CronNextRunCalculator() {
        // utility class
    }

    /**
     * Calculates the next execution timestamp relative to {@code baseTime} for a Quartz cron expression.
     *
     * @param cronExpression Quartz cron expression string
     * @param baseTime       the reference instant from which to compute the next run time
     * @return {@link Optional} containing the next {@link Instant}, or empty if invalid/no next run
     */
    public static Optional<Instant> computeNextRunAt(String cronExpression, Instant baseTime) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return Optional.empty();
        }
        try {
            Cron cron = QUARTZ_PARSER.parse(cronExpression);
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime zdt = baseTime.atZone(ZoneId.of("UTC"));
            return executionTime.nextExecution(zdt).map(ZonedDateTime::toInstant);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
