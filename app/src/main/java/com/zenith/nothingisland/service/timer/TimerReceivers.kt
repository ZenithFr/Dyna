package com.zenith.nothingisland.service.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Delivers the timer deadline. Declared `exported=false` and only ever armed by
 * [AlarmTimerScheduler]; it exists so completion does not depend on the app process being alive
 * (Doze, process death, reboot).
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TIMER_ALARM) return
        TimerModule.onAlarmFired(context)
    }

    companion object {
        const val ACTION_TIMER_ALARM = "com.zenith.nothingisland.action.TIMER_ALARM"
    }
}

/**
 * Re-arms a stored timer after a reboot.
 *
 * The manifest declares it `android:enabled="false"`; [TimerModule] enables it only while a timer
 * exists, so a phone that never used the timer never cold-starts this process on boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        TimerModule.onBootCompleted(context)
    }
}
