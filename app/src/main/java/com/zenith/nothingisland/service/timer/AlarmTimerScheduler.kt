package com.zenith.nothingisland.service.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * `AlarmManager` behind [TimerScheduler].
 *
 * `setExactAndAllowWhileIdle` needs `SCHEDULE_EXACT_ALARM` on Android 12+. When the user has not
 * granted it (or a policy revoked it) we fall back to `setAndAllowWhileIdle`, which Doze batches:
 * the timer still fires, but possibly minutes late, and [isExact] lets the UI say so.
 */
class AlarmTimerScheduler(context: Context) : TimerScheduler {

    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager? =
        appContext.getSystemService(AlarmManager::class.java)

    override fun isExact(): Boolean {
        val manager = alarmManager ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    override fun arm(atElapsedMs: Long) {
        val manager = alarmManager ?: return
        val pending = pendingIntent()
        val exact = isExact()
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
            }
        }.onFailure { error ->
            // SecurityException when the grant was revoked between the check and the call.
            Log.w(TAG, "Exact alarm refused; falling back to an inexact one", error)
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
            }.onFailure { Log.e(TAG, "Unable to arm the timer alarm", it) }
        }
    }

    override fun cancel() {
        alarmManager?.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(appContext, TimerAlarmReceiver::class.java)
            .setAction(TimerAlarmReceiver.ACTION_TIMER_ALARM)
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "TimerScheduler"
        private const val REQUEST_CODE = 0x7A11
    }
}
