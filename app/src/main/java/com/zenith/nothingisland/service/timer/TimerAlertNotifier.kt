package com.zenith.nothingisland.service.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zenith.nothingisland.MainActivity
import com.zenith.nothingisland.R
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.data.IslandSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The finished-timer alert.
 *
 * A timer that only redraws the island is a silent failure: the phone is usually in a pocket, the
 * screen is off, and the accessibility overlay is detached. So completion posts a high-importance
 * notification (the sanctioned way to vibrate or sound with the screen off), and vibrates directly
 * as a fallback when notifications are blocked.
 *
 * The channel is created once (Android treats sound/vibration on an existing channel as immutable)
 * and the two user switches are applied per notification: sound off means a silent notification
 * plus an app-driven vibration, so a finished timer can be felt without surprising a meeting.
 */
object TimerAlertNotifier {

    private const val TAG = "TimerAlert"
    private const val CHANNEL_ID = "timer_alerts"
    private const val NOTIFICATION_ID = 0x7A12
    private val VIBRATION_PATTERN = longArrayOf(0L, 350L, 180L, 350L)

    fun show(context: Context, label: String, late: Boolean) {
        val appContext = context.applicationContext
        val settings = readSettings(appContext)
        val manager = appContext.getSystemService(NotificationManager::class.java)

        runCatching { ensureChannel(manager) }
            .onFailure { Log.w(TAG, "Unable to create the timer channel", it) }

        val posted = runCatching {
            if (notificationsAllowed(appContext, manager)) {
                manager?.notify(NOTIFICATION_ID, buildNotification(appContext, settings, label, late))
                true
            } else {
                false
            }
        }.onFailure { Log.w(TAG, "Unable to post the timer notification", it) }
            .getOrDefault(false)

        // Two independent switches, one channel: the channel is created once (Android ignores
        // later sound/vibration changes anyway), and the notification is made silent when the user
        // asked for no sound. Vibration then comes from the app, so it works with sound off.
        if (!posted || !settings.timerAlertSound) {
            vibrate(appContext, settings.timerAlertVibrate)
        }
    }

    /** Removes the alert when the user dismisses or restarts the timer. */
    fun cancel(context: Context) {
        runCatching {
            context.applicationContext.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        }
    }

    /**
     * Created once, never recreated: Android treats sound/vibration on an existing channel as
     * immutable and silently keeps the old values, so "update the channel" would be a lie. The
     * per-alert switches are applied on the notification instead ([buildNotification]).
     */
    private fun ensureChannel(manager: NotificationManager?) {
        if (manager == null) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Timers",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires when a timer started from the island reaches zero."
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        context: Context,
        settings: IslandSettings,
        label: String,
        late: Boolean,
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_timer)
        .setContentTitle(if (late) "Timer finished while you were away" else "Timer finished")
        .setContentText(label.ifBlank { TimerCore.DEFAULT_LABEL })
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setOnlyAlertOnce(false)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(openAppIntent(context))
        // Sound off means silent: no channel sound, no channel vibration. The app vibrates instead.
        .setSilent(!settings.timerAlertSound)
        .build()

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationsAllowed(context: Context, manager: NotificationManager?): Boolean {
        if (manager == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun vibrate(context: Context, enabled: Boolean) {
        if (!enabled) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: return
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
        }.onFailure { Log.w(TAG, "Unable to vibrate for the timer", it) }
    }

    /**
     * The alert must not be delayed by a DataStore read, but it must also respect the user's
     * switches. Read with a short timeout and fall back to the defaults.
     */
    private fun readSettings(context: Context): IslandSettings = runCatching {
        runBlocking {
            withTimeoutOrNull(1_000L) {
                IslandSettingsRepository.get(context).settings.first()
            }
        }
    }.getOrNull() ?: IslandSettings()
}
