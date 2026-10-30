package com.jpbbot.heures

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE)

fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormat)

fun formatDay(date: LocalDate): String = date.format(dayFormat)

fun formatDuration(minutes: Long): String = "%dh %02dmin".format(minutes / 60, minutes % 60)

fun formatDecimal(minutes: Long): String = String.format(Locale.FRANCE, "%.2f h", minutes / 60.0)
