package com.elect.riderange.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/** Wall-clock milliseconds since 1970 (System.currentTimeMillis on every platform). */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private val monoStart = TimeSource.Monotonic.markNow()

/** Monotonic nanoseconds (only differences are meaningful), like System.nanoTime. */
fun nanoTime(): Long = monoStart.elapsedNow().inWholeNanoseconds

/**
 * Formats [ms] with a SimpleDateFormat-style pattern (US English names): y, M/MM/MMM, d/dd, H/HH, h/hh, m/mm,
 * s/ss, a, EEE and 'quoted text'. Only what RideRange uses.
 */
object DateFmt {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun format(ms: Long, pattern: String, tz: TimeZone = TimeZone.currentSystemDefault()): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
                sb.append(pattern, i + 1, end)
                i = end + 1
                continue
            }
            if (!c.isLetter()) { sb.append(c); i++; continue }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            val month = t.month.ordinal + 1
            val hour12 = (t.hour % 12).let { if (it == 0) 12 else it }
            sb.append(when (c) {
                'y' -> if (n == 2) (t.year % 100).toString().padStart(2, '0') else t.year.toString().padStart(n, '0')
                'M' -> if (n >= 3) MONTHS[month - 1] else month.toString().padStart(n, '0')
                'd' -> t.day.toString().padStart(n, '0')
                'H' -> t.hour.toString().padStart(n, '0')
                'h' -> hour12.toString().padStart(n, '0')
                'm' -> t.minute.toString().padStart(n, '0')
                's' -> t.second.toString().padStart(n, '0')
                'a' -> if (t.hour < 12) "AM" else "PM"
                'E' -> DAYS[t.dayOfWeek.ordinal]
                else -> c.toString().repeat(n)
            })
            i += n
        }
        return sb.toString()
    }

    fun utc(ms: Long, pattern: String): String = format(ms, pattern, TimeZone.UTC)
}
