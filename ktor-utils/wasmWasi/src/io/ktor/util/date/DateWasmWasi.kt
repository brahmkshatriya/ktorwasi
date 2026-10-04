/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.ktor.util.date

import kotlin.time.Clock

public actual fun GMTDate(timestamp: Long?): GMTDate = timestampToGMTDate(
    timestamp ?: getTimeMillis()
)

public actual fun GMTDate(
    seconds: Int,
    minutes: Int,
    hours: Int,
    dayOfMonth: Int,
    month: Month,
    year: Int,
): GMTDate {
    val days = daysFromCivil(year, month.ordinal + 1, dayOfMonth)
    val timestamp = (((days * 24L + hours) * 60L + minutes) * 60L + seconds) * 1000L
    return timestampToGMTDate(timestamp)
}

public actual fun getTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun timestampToGMTDate(timestamp: Long): GMTDate {
    val totalSeconds = floorDiv(timestamp, 1000L)
    val secondOfDay = floorMod(totalSeconds, 86_400L)
    val seconds = (secondOfDay % 60).toInt()
    val minutes = ((secondOfDay / 60) % 60).toInt()
    val hours = (secondOfDay / 3600).toInt()
    val days = floorDiv(totalSeconds, 86_400L)

    val civil = civilFromDays(days)
    val month = Month.from(civil.month - 1)
    val dayOfYear = dayOfYear(civil.year, civil.month, civil.day)
    val weekDay = WeekDay.from(floorMod(days + 3L, 7L).toInt())

    return GMTDate(
        seconds = seconds,
        minutes = minutes,
        hours = hours,
        dayOfWeek = weekDay,
        dayOfMonth = civil.day,
        dayOfYear = dayOfYear,
        month = month,
        year = civil.year,
        timestamp = timestamp,
    )
}

private data class CivilDate(val year: Int, val month: Int, val day: Int)

private fun civilFromDays(epochDays: Long): CivilDate {
    val z = epochDays + 719468L
    val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    var year = (yoe + era * 400L).toInt()
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val day = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
    val month = if (mp < 10L) (mp + 3L).toInt() else (mp - 9L).toInt()
    if (month <= 2) year++
    return CivilDate(year, month, day)
}

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    var y = year
    if (month <= 2) y--
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = y - era * 400
    val mp = month + if (month > 2) -3 else 9
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097L + doe.toLong() - 719468L
}

private fun dayOfYear(year: Int, month: Int, day: Int): Int {
    val cumulative = if (isLeapYear(year)) {
        intArrayOf(0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335)
    } else {
        intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    }
    return cumulative[month - 1] + day
}

private fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private fun floorDiv(a: Long, b: Long): Long {
    var q = a / b
    val r = a % b
    if (r != 0L && (a xor b) < 0L) q--
    return q
}

private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
