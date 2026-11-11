package com.zenith.nothingisland.service.timer

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * The timer's durable half: a four-field `SharedPreferences` file.
 *
 * DataStore is deliberately not used here — this is written from a `BroadcastReceiver` and read
 * before the first frame, so a synchronous, tiny write is the right tool. Writes use `commit()`
 * because the process may be killed right after an alarm is armed.
 */
class TimerStore(context: Context) : TimerStorePort {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // commit() is deliberate: the process may be killed right after an alarm is armed.
    @SuppressLint("ApplySharedPref")
    override fun save(record: TimerRecord) {
        prefs.edit()
            .putString(KEY_LABEL, record.label)
            .putLong(KEY_END_WALL, record.endWallMs)
            .putLong(KEY_REMAINING, record.remainingMs)
            .putBoolean(KEY_PAUSED, record.paused)
            .commit()
    }

    override fun load(): TimerRecord? {
        if (!prefs.contains(KEY_REMAINING)) return null
        val record = TimerRecord(
            label = prefs.getString(KEY_LABEL, null) ?: TimerCore.DEFAULT_LABEL,
            endWallMs = prefs.getLong(KEY_END_WALL, 0L),
            remainingMs = prefs.getLong(KEY_REMAINING, 0L),
            paused = prefs.getBoolean(KEY_PAUSED, false),
        )
        // A half-written record is worse than none: drop it rather than restore nonsense.
        if (!record.paused && record.endWallMs <= 0L) {
            Log.w(TAG, "Discarding corrupt timer record")
            clear()
            return null
        }
        return record
    }

    @SuppressLint("ApplySharedPref")
    override fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val TAG = "TimerStore"
        private const val FILE_NAME = "island_timer"
        private const val KEY_LABEL = "label"
        private const val KEY_END_WALL = "end_wall_ms"
        private const val KEY_REMAINING = "remaining_ms"
        private const val KEY_PAUSED = "paused"
    }
}
