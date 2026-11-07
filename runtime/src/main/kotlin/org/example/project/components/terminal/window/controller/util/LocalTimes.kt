package org.example.project.components.terminal.window.controller.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val MINUTES: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val MINUTES_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")

/**
 * A stored time as this machine's clock shows it: "2026-10-21 10:03:20", or to the minute. module.json, metrics.json,
 * groups and reports keep instants in UTC (2026-10-21T08:03:20.123Z), while the console and session logs show local
 * time, so the two agree only once both are local. Anything that isn't an instant is shown as it was before: its
 * first characters, the T made a space.
 */
fun localTime(stamp: String, seconds: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { (if (seconds) SECONDS else MINUTES).withZone(zone).format(Instant.parse(stamp)) }
        .getOrElse { stamp.take(if (seconds) 19 else 16).replace('T', ' ') }

/**
 * A stored time written into text that is kept, such as a note in metrics.json: this machine's time to the minute
 * with its offset from UTC, "2026-10-21 10:03 +02:00", so it reads right on any machine and in any season. Anything
 * that isn't an instant is shown as [localTime] shows it.
 */
fun localTimeWithOffset(stamp: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { MINUTES_OFFSET.withZone(zone).format(Instant.parse(stamp)) }.getOrElse { localTime(stamp, seconds = false, zone = zone) }
