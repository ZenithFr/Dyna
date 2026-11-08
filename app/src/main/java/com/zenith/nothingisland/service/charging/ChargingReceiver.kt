package com.zenith.nothingisland.service.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zenith.nothingisland.service.IslandService

/**
 * Manifest receiver for plug/unplug. Only acts while the accessibility service (and therefore the
 * overlay) is alive; otherwise waking the process to update an invisible island is wasted work.
 */
class ChargingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!IslandService.isRunning) return
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> ChargingEvents.onPowerConnected(context)
            Intent.ACTION_POWER_DISCONNECTED -> ChargingEvents.onPowerDisconnected()
        }
    }
}
