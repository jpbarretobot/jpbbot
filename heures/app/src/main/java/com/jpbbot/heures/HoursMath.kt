package com.jpbbot.heures

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

const val MILLIS_PER_MINUTE = 60_000L

data class WorkSession(val start: Long, val end: Long) {
    val minutes: Long get() = (end - start) / MILLIS_PER_MINUTE

    fun startDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
}

object HoursMath {

    fun totalMinutes(sessions: List<WorkSession>): Long = sessions.sumOf { it.minutes }

    fun sessionsOf(
        month: YearMonth,
        sessions: List<WorkSession>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<WorkSession> =
        sessions
            .filter { YearMonth.from(it.startDate(zone)) == month }
            .sortedBy { it.start }

    /**
     * Returns null when start and end are the same time.
     * An end earlier than the start is treated as a shift that goes past midnight.
     */
    fun manualSession(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WorkSession? {
        if (startTime == endTime) return null
        val start = LocalDateTime.of(date, startTime)
        var end = LocalDateTime.of(date, endTime)
        if (end.isBefore(start)) end = end.plusDays(1)
        return WorkSession(
            start = start.atZone(zone).toInstant().toEpochMilli(),
            end = end.atZone(zone).toInstant().toEpochMilli(),
        )
    }
}
