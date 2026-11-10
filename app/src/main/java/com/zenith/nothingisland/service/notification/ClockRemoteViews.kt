package com.zenith.nothingisland.service.notification

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import com.zenith.nothingisland.service.ClockKind

/**
 * Reads a stopwatch out of a Clock app that publishes nothing but a custom `RemoteViews`.
 *
 * Google Clock's stopwatch is such a notification: no sort key, no chronometer extras, and a null
 * title/text, because everything is drawn by the app. Its layout (`chronometer_notif_content_running`)
 * holds a real [Chronometer] whose base is the stopwatch start in elapsed realtime; once the
 * stopwatch is paused the app swaps in a layout that renders the frozen value into a plain
 * [TextView]. Inflating that view in our own process is the only public way to read the value, so
 * this is deliberately best-effort: anything unexpected yields null and the notification keeps
 * showing as a generic activity card instead of a wrong time.
 */
internal object ClockRemoteViews {

    /** Longest elapsed time accepted from a Clock view; anything older is a stale base, not a timer. */
    private const val MAX_ELAPSED_MS = 24L * 60L * 60L * 1_000L

    fun stopwatch(context: Context, notification: Notification): ClockParser.Decoded? {
        // Only a stopwatch is read this way. An alarm or an expired timer also renders a custom view
        // with a bare time in it, and reading "7:00" as seven elapsed minutes would turn a ringing
        // alarm into a clock card.
        if (!looksLikeStopwatch(notification)) return null
        val remote = notification.contentView ?: return null
        // The parent is only there because apply() requires one; the inflated tree is not attached.
        val inflated = runCatching { remote.apply(context, FrameLayout(context)) }.getOrNull() ?: return null
        val views = ArrayList<View>(8)
        collect(inflated, views)

        // Running: the Chronometer ticks on its own, so its base is the only state we need.
        val now = SystemClock.elapsedRealtime()
        val chronometer = views.filterIsInstance<Chronometer>().firstOrNull()
        val base = chronometer?.base
        if (base != null && base <= now + 1_000L && now - base < MAX_ELAPSED_MS) {
            return ClockParser.Decoded(
                kind = ClockKind.Stopwatch,
                running = true,
                anchorElapsedMs = base,
                frozenMs = 0L,
            )
        }

        // Paused: the frozen value is rendered as text ("00:12.34", "1:02:03.45").
        val frozen = views.filterIsInstance<TextView>()
            .firstNotNullOfOrNull { ClockParser.parseElapsedText(it.text?.toString().orEmpty()) }
            ?: return null
        if (frozen > MAX_ELAPSED_MS) return null
        return ClockParser.Decoded(
            kind = ClockKind.Stopwatch,
            running = false,
            anchorElapsedMs = 0L,
            frozenMs = frozen,
        )
    }

    /** Google Clock's channel is `Stopwatch v2`; other Clock apps label the actions instead. */
    private fun looksLikeStopwatch(notification: Notification): Boolean {
        if ("stopwatch" in notification.channelId.orEmpty().lowercase()) return true
        val labels = notification.actions.orEmpty()
            .mapNotNull { it.title?.toString()?.lowercase() }
        return "lap" in labels || ("pause" in labels && "reset" in labels)
    }

    private fun collect(view: View, out: MutableList<View>) {
        out += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index), out)
        }
    }
}
