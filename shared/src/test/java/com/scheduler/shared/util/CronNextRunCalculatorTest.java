package com.scheduler.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CronNextRunCalculator — Unit Tests")
class CronNextRunCalculatorTest {

    @Test
    @DisplayName("Computes next execution for daily noon Quartz cron expression")
    void computesNextRunForDailyNoon() {
        // Quartz cron for every day at 12:00:00 UTC
        String cron = "0 0 12 * * ?";

        // Reference time: 2026-09-03 10:00:00 UTC
        ZonedDateTime baseZdt = ZonedDateTime.of(2026, 9, 3, 10, 0, 0, 0, ZoneId.of("UTC"));
        Instant baseTime = baseZdt.toInstant();

        Optional<Instant> nextOpt = CronNextRunCalculator.computeNextRunAt(cron, baseTime);

        assertThat(nextOpt).isPresent();
        ZonedDateTime nextZdt = nextOpt.get().atZone(ZoneId.of("UTC"));
        assertThat(nextZdt.getYear()).isEqualTo(2026);
        assertThat(nextZdt.getMonthValue()).isEqualTo(9);
        assertThat(nextZdt.getDayOfMonth()).isEqualTo(3);
        assertThat(nextZdt.getHour()).isEqualTo(12);
        assertThat(nextZdt.getMinute()).isEqualTo(0);
        assertThat(nextZdt.getSecond()).isEqualTo(0);
    }

    @Test
    @DisplayName("Computes next execution for every 5 minutes Quartz cron expression")
    void computesNextRunForEveryFiveMinutes() {
        // Quartz cron for every 5 minutes
        String cron = "0 */5 * * * ?";

        // Reference time: 2026-09-03 14:02:15 UTC
        ZonedDateTime baseZdt = ZonedDateTime.of(2026, 9, 3, 14, 2, 15, 0, ZoneId.of("UTC"));
        Instant baseTime = baseZdt.toInstant();

        Optional<Instant> nextOpt = CronNextRunCalculator.computeNextRunAt(cron, baseTime);

        assertThat(nextOpt).isPresent();
        ZonedDateTime nextZdt = nextOpt.get().atZone(ZoneId.of("UTC"));
        assertThat(nextZdt.getHour()).isEqualTo(14);
        assertThat(nextZdt.getMinute()).isEqualTo(5);
        assertThat(nextZdt.getSecond()).isEqualTo(0);
    }

    @Test
    @DisplayName("Returns empty optional for null or invalid cron expressions")
    void handlesInvalidCronExpressions() {
        Instant now = Instant.now();
        assertThat(CronNextRunCalculator.computeNextRunAt(null, now)).isEmpty();
        assertThat(CronNextRunCalculator.computeNextRunAt("", now)).isEmpty();
        assertThat(CronNextRunCalculator.computeNextRunAt("INVALID CRON STRING", now)).isEmpty();
    }
}
