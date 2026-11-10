package com.zenith.nothingisland.service.notification

import com.zenith.nothingisland.service.ClockKind

/**
 * Decodes a running clock timer/stopwatch from the ongoing notification its Clock app posts.
 *
 * Two public shapes exist and both are handled here:
 * - The platform chronometer (`Notification.EXTRA_SHOW_CHRONOMETER` + `EXTRA_CHRONOMETER_COUNT_DOWN`),
 *   where `Notification.when` is the countdown deadline or the stopwatch start.
 * - Google/OnePlus Clock publish no chronometer at all: they render a custom RemoteViews and pack the
 *   state into the notification's sort key (`…|RUNNING|▶20:07:02.672|Σ0:03:00|Δ0:00:04|⏳0:02:56`).
 *   That string is read best-effort; when it stops parsing the island falls back to the generic
 *   activity card instead of showing a wrong time.
 *
 * Pure Kotlin on purpose: the rules are unit tested without Android.
 */
object ClockParser {

    /** Everything the decoder may look at, already read from the `Notification` record. */
    data class Input(
        val sortKey: String?,
        val title: String?,
        val text: String?,
        val showChronometer: Boolean,
        val chronometerCountDown: Boolean,
        /** `Notification.when`, in wall-clock milliseconds. */
        val whenWallMs: Long,
        /** `StatusBarNotification.getPostTime()`, in wall-clock milliseconds. */
        val postTimeWallMs: Long,
        /** Wall clock and elapsed realtime sampled together when the notification arrived. */
        val nowWallMs: Long,
        val nowElapsedMs: Long,
    )

    /** A decoded clock card, anchored to elapsed realtime like every other island source. */
    data class Decoded(
        val kind: ClockKind,
        val running: Boolean,
        /** Countdown: deadline. Stopwatch: start. Only meaningful while [running]. */
        val anchorElapsedMs: Long,
        /** Countdown remaining / stopwatch elapsed while paused; 0 otherwise. */
        val frozenMs: Long,
    )

    private val STATE = Regex("\\b(RUNNING|PAUSED|STOPPED|RESET)\\b")
    /** `Σ0:03:00`, `Δ0:00:04`, `⏳0:02:56` (also `⌛`), the symbols Google Clock sorts by. */
    private const val COUNTDOWN_GLYPH = '↺'
    private val DURATION = Regex("([ΣΔ⏳⌛])\\s*(\\d{1,2}(?::\\d{1,2}){1,2})(?:[.,]\\d+)?")

    fun decode(input: Input): Decoded? {
        val elapsedAtPost = input.nowElapsedMs - (input.nowWallMs - input.postTimeWallMs)
        val sortKey = input.sortKey.orEmpty()
        val durations = durations(sortKey)
        val state = STATE.find(sortKey)?.groupValues?.getOrNull(1)
        // A finished timer is the Clock app's "time's up" alarm, not a live card: drop it here and
        // let the normal alert path show the ringing notification instead of a stuck 00:00.
        if (state == "EXPIRED" || state == "STOPPED" || state == "RESET") return null
        val running = when (state) {
            null -> true
            "RUNNING" -> true
            else -> false
        }
        val remaining = durations['⏳'] ?: durations['⌛']
        val total = durations['Σ']
        val elapsed = durations['Δ']

        // 1. Google/OnePlus Clock: the sort key carries the live remaining time.
        if (remaining != null) {
            if (remaining == 0L) return null
            return Decoded(
                kind = ClockKind.Timer,
                running = running,
                anchorElapsedMs = if (running) elapsedAtPost + remaining else 0L,
                frozenMs = if (running) 0L else remaining,
            )
        }
        // 2. Same string, but only the total and how much of it had run. The timer's own glyph
        // (↺) marks a countdown; without it the totals are a stopwatch's elapsed time.
        if (COUNTDOWN_GLYPH in sortKey && total != null && elapsed != null) {
            val left = (total - elapsed).coerceAtLeast(0L)
            if (left == 0L) return null
            return Decoded(
                kind = ClockKind.Timer,
                running = running,
                anchorElapsedMs = if (running) elapsedAtPost + left else 0L,
                frozenMs = if (running) 0L else left,
            )
        }
        // 3. Platform chronometer: `when` is a deadline when counting down.
        if (input.showChronometer) {
            val anchor = input.nowElapsedMs - (input.nowWallMs - input.whenWallMs)
            return if (input.chronometerCountDown) {
                Decoded(ClockKind.Timer, running = true, anchorElapsedMs = anchor, frozenMs = 0L)
            } else {
                Decoded(ClockKind.Stopwatch, running = true, anchorElapsedMs = anchor, frozenMs = 0L)
            }
        }
        // 4. A stopwatch reports how long it has been running; `when` is its start.
        val runningFor = listOfNotNull(total, elapsed).maxOrNull()
        if (runningFor != null) {
            return Decoded(
                kind = ClockKind.Stopwatch,
                running = running,
                anchorElapsedMs = if (running) elapsedAtPost - runningFor else 0L,
                frozenMs = if (running) 0L else runningFor,
            )
        }
        return null
    }

    private fun durations(sortKey: String): Map<Char, Long> =
        DURATION.findAll(sortKey).associate { match ->
            match.groupValues[1][0] to parseDuration(match.groupValues[2])
        }

    /** Accepts `m:ss` / `h:mm:ss` (the shapes Clock packs into its sort key). */
    private fun parseDuration(value: String): Long {
        val parts = value.split(':').map { it.toLongOrNull() ?: return 0L }
        return when (parts.size) {
            2 -> parts[0] * 60_000L + parts[1] * 1_000L
            3 -> parts[0] * 3_600_000L + parts[1] * 60_000L + parts[2] * 1_000L
            else -> 0L
        }
    }
}
