package com.zenith.nothingisland.service.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandStateHolder

/**
 * Battery / power-save source. Uses only public sticky battery + power-save broadcasts; no Shizuku
 * or privileged shell is needed for these states. Plug transitions arrive through the same sticky
 * battery receiver via [ChargingEvents]; there is no manifest cold-wake receiver.
 */
class ChargingSource(private val context: Context) {

    private var batteryReceiver: BroadcastReceiver? = null
    private var powerSaveReceiver: BroadcastReceiver? = null
    private var started = false
    private var settings = IslandSettings()
    private var lastLevel = -1
    private var lowAlerted = false
    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)

    fun updateSettings(next: IslandSettings) {
        settings = next.coerced()
        ChargingEvents.chargingEnabled = settings.chargingEnabled
        if (!settings.chargingEnabled) IslandStateHolder.clearCharging()
        if (!settings.batteryLowEnabled) IslandStateHolder.clearBatteryLow()
        if (!settings.powerSaveEnabled) IslandStateHolder.clearPowerSave()
    }

    fun start() {
        if (started) return
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        ChargingEvents.prime(sticky)
        lastLevel = sticky?.levelPercent() ?: -1
        lowAlerted = lastLevel in 0..settings.batteryLowThresholdPercent

        val battery = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                runCatching {
                    ChargingEvents.onBatteryChanged(intent)
                    maybeBatteryLow(intent.levelPercent())
                }.onFailure { Log.w(TAG, "Battery update failed", it) }
            }
        }
        val powerSave = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
                runCatching {
                    if (settings.powerSaveEnabled) {
                        IslandStateHolder.postPowerSave(
                            IslandContent.PowerSave(powerManager?.isPowerSaveMode == true),
                        )
                    }
                }.onFailure { Log.w(TAG, "Power-save update failed", it) }
            }
        }
        batteryReceiver = battery
        powerSaveReceiver = powerSave
        runCatching {
            ContextCompat.registerReceiver(
                context, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            ContextCompat.registerReceiver(
                context, powerSave, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            started = true
        }.onFailure {
            Log.w(TAG, "Unable to register battery/power-save receivers", it)
            stop()
        }
    }

    fun stop() {
        started = false
        batteryReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        powerSaveReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        batteryReceiver = null
        powerSaveReceiver = null
        IslandStateHolder.clearCharging()
        IslandStateHolder.clearBatteryLow()
        IslandStateHolder.clearPowerSave()
    }

    private fun maybeBatteryLow(level: Int) {
        if (level < 0) return
        val threshold = settings.batteryLowThresholdPercent
        if (level > threshold) {
            lowAlerted = false
        } else if (!lowAlerted && settings.batteryLowEnabled) {
            lowAlerted = true
            IslandStateHolder.postBatteryLow(IslandContent.BatteryLow(level))
        }
        lastLevel = level
    }

    private fun Intent.levelPercent(): Int {
        val raw = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return -1
        return (raw * 100f / scale).toInt().coerceIn(0, 100)
    }

    private companion object {
        const val TAG = "IslandCharging"
    }
}
