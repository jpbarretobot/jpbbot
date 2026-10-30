package com.jpbbot.heures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

class HoursMathTest {

    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun manualSessionComputesDuration() {
        val session = HoursMath.manualSession(
            LocalDate.of(2026, 10, 5), LocalTime.of(8, 30), LocalTime.of(17, 0), zone,
        )
        assertEquals(510L, session?.minutes)
    }

    @Test
    fun manualSessionEndingBeforeStartCrossesMidnight() {
        val session = HoursMath.manualSession(
            LocalDate.of(2026, 10, 5), LocalTime.of(22, 0), LocalTime.of(6, 0), zone,
        )
        assertEquals(480L, session?.minutes)
    }

    @Test
    fun manualSessionWithSameStartAndEndIsRejected() {
        val session = HoursMath.manualSession(
            LocalDate.of(2026, 10, 5), LocalTime.of(8, 0), LocalTime.of(8, 0), zone,
        )
        assertNull(session)
    }

    @Test
    fun sessionsOfMonthOnlyKeepsThatMonthAndSortsThem() {
        val oct = HoursMath.manualSession(
            LocalDate.of(2026, 10, 20), LocalTime.of(9, 0), LocalTime.of(10, 0), zone,
        )!!
        val early = HoursMath.manualSession(
            LocalDate.of(2026, 10, 2), LocalTime.of(9, 0), LocalTime.of(11, 0), zone,
        )!!
        val nov = HoursMath.manualSession(
            LocalDate.of(2026, 11, 1), LocalTime.of(9, 0), LocalTime.of(12, 0), zone,
        )!!

        val result = HoursMath.sessionsOf(YearMonth.of(2026, 10), listOf(oct, nov, early), zone)

        assertEquals(listOf(early, oct), result)
        assertEquals(180L, HoursMath.totalMinutes(result))
    }

    @Test
    fun formatDurationShowsHoursAndMinutes() {
        assertEquals("8h 05min", formatDuration(485))
    }
}
