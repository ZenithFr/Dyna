package com.zenith.nothingisland.service.charging

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandStateHolder

/**
 * Shared battery logic for the two entry points: the manifest receiver (plug events) and the
 * service's sticky `ACTION_BATTERY_CHANGED` receiver (level + plug type). No polling.
 *
 * A short debounce collapses the duplicate signals Android sends around a plug event
 * (`POWER_CONNECTED` and `BATTERY_CHANGED` with a new `EXTRA_PLUGGED`).
 */
object ChargingEvents {

    private const val CONNECT_DEBOUNCE_MS = 1_500L

    @Volatile
    var chargingEnabled: Boolean = true

    private var lastConfirmationAt = -CONNECT_DEBOUNCE_MS
    private var lastLevel = -1
    private var lastPlugged = -1

    /** Prime the state from the sticky battery intent without showing anything. */
    @Synchronized
    fun prime(intent: Intent?) {
        if (intent == null) return
        lastLevel = intent.level()
        lastPlugged = intent.plugged()
    }

    /** Called from the manifest [ChargingReceiver] when the charger is plugged in. */
    @Synchronized
    fun onPowerConnected(context: Context) {
        val sticky = readStickyBattery(context)
        val level = sticky?.level()?.takeIf { it >= 0 } ?: lastLevel
        lastPlugged = sticky?.plugged() ?: lastPlugged
        confirm(level)
    }

    @Synchronized
    fun onPowerDisconnected() {
        lastPlugged = 0
        lastConfirmationAt = -CONNECT_DEBOUNCE_MS
        IslandStateHolder.clearCharging()
    }

    /** Sticky battery callback (fires on plug changes and level changes). */
    @Synchronized
    fun onBatteryChanged(context: Context, intent: Intent) {
        val level = intent.level()
        val plugged = intent.plugged()
        val pluggedNow = plugged != 0

        if (!pluggedNow) {
            if (lastPlugged > 0) IslandStateHolder.clearCharging()
            lastPlugged = 0
            lastLevel = level
            return
        }

        val justPluggedIn = lastPlugged == 0
        val justFull = level >= 100 && lastLevel in 0..99
        lastPlugged = plugged
        lastLevel = level

        when {
            // A charge confirmation (or the full-charge card) on the relevant transition.
            justPluggedIn || justFull -> confirm(level)
        }
    }

    private fun confirm(level: Int) {
        if (level < 0 || !chargingEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfirmationAt < CONNECT_DEBOUNCE_MS) return
        lastConfirmationAt = now
        IslandStateHolder.postCharging(
            IslandContent.Charging(
                level = level.coerceIn(0, 100),
                isFull = level >= 100,
            ),
        )
    }

    private fun readStickyBattery(context: Context): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    private fun Intent.level(): Int {
        val raw = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return -1
        return (raw * 100f / scale).toInt().coerceIn(0, 100)
    }

    private fun Intent.plugged(): Int =
        getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
}
