package com.zenith.nothingisland.util

import android.app.NotificationManager
import android.content.Context

/** True when any Do Not Disturb mode is active (Priority, Alarms only, or Total silence). */
fun Context.isDndActive(): Boolean = runCatching {
    val nm = getSystemService(NotificationManager::class.java) ?: return false
    val filter = nm.currentInterruptionFilter
    filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
}.getOrDefault(false)

/** True only when Do Not Disturb is set to Total Silence (`INTERRUPTION_FILTER_NONE`). */
fun Context.isDndSilent(): Boolean = runCatching {
    val nm = getSystemService(NotificationManager::class.java) ?: return false
    nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
}.getOrDefault(false)
