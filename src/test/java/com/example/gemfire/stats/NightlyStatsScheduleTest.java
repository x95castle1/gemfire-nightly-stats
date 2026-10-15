package com.example.gemfire.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class NightlyStatsScheduleTest {

    @Test
    void choosesTodayBeforeRunAndTomorrowAtOrAfterRun() {
        assertNext("2026-10-07T01:59:59-05:00[America/Chicago]", "02:00",
                "2026-10-07T02:00-05:00[America/Chicago]");
        assertNext("2026-10-07T02:00-05:00[America/Chicago]", "02:00",
                "2026-10-08T02:00-05:00[America/Chicago]");
    }

    @Test
    void springGapRunsAtThreeWithoutMovingTheFollowingDaysRun() {
        assertNext("2026-03-08T00:00-06:00[America/Chicago]", "02:00",
                "2026-03-08T03:00-05:00[America/Chicago]");
        assertNext("2026-03-08T03:00-05:00[America/Chicago]", "02:00",
                "2026-03-09T02:00-05:00[America/Chicago]");
    }

    @Test
    void dayBeforeSpringGapAlsoResolvesTheMissingTime() {
        assertNext("2026-03-07T04:00-06:00[America/Chicago]", "02:00",
                "2026-03-08T03:00-05:00[America/Chicago]");
    }

    @Test
    void fallTransitionKeepsTwoAmLocalTime() {
        assertNext("2026-10-31T03:00-05:00[America/Chicago]", "02:00",
                "2026-11-01T02:00-06:00[America/Chicago]");
    }

    @Test
    void repeatedHourDoesNotScheduleASecondRunThatDay() {
        assertNext("2026-11-01T01:30-05:00[America/Chicago]", "01:30",
                "2026-11-02T01:30-06:00[America/Chicago]");
    }

    @Test
    void usesTheRequestedZoneInsteadOfTheMachineZone() {
        assertNext("2026-10-07T01:00Z[UTC]", "02:00", "2026-10-07T02:00Z[UTC]");
    }

    private static void assertNext(String now, String time, String expected) {
        assertEquals(ZonedDateTime.parse(expected),
                NightlyStatsLocatorStart.nextRun(ZonedDateTime.parse(now), LocalTime.parse(time)));
    }
}
