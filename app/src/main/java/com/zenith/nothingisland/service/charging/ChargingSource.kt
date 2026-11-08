package com.zenith.nothingisland.service.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.zenith.nothingisland.service.IslandStateHolder

/**
 * Service-owned receiver for the sticky `ACTION_BATTERY_CHANGED` broadcast: it supplies the level
 * and plug type on registration and on every change, which is how the percentage stays current
 * without polling. Plug/unplug itself is also delivered by the manifest [ChargingReceiver].
 */
class ChargingSource(private val context: Context) {

    private var receiver: BroadcastReceiver? = null
    private var started = false

    fun start() {
        if (started) return
        // Read the sticky value first so the immediate replay on registerReceiver is not mistaken
        // for a fresh plug event (no spurious "82%" card whenever the service reconnects).
        ChargingEvents.prime(
            runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
                .getOrNull(),
        )

        val next = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                runCatching { ChargingEvents.onBatteryChanged(context, intent) }
                    .onFailure { Log.w(TAG, "Battery update failed", it) }
            }
        }
        receiver = next
        runCatching {
            // Explicit non-exported registration: ACTION_BATTERY_CHANGED is a protected system
            // broadcast, so the flag is accepted on every API level (and required on 34+).
            ContextCompat.registerReceiver(
                context,
                next,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            started = true
        }.onFailure {
            Log.w(TAG, "Unable to register battery receiver", it)
            receiver = null
        }
    }

    fun stop() {
        val current = receiver ?: return
        receiver = null
        started = false
        runCatching { context.unregisterReceiver(current) }
            .onFailure { Log.w(TAG, "Battery receiver already unregistered", it) }
        IslandStateHolder.clearCharging()
    }

    private companion object {
        const val TAG = "IslandCharging"
    }
}
