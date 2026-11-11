package com.zenith.nothingisland.data

/**
 * Committed settings plus at most one in-memory draft. Pure: no Android, no coroutines.
 *
 * A slider drag publishes dozens of values but only the last one is worth persisting, so every
 * tick lands in [draft] and [takeForCommit] hands the single surviving snapshot to the writer.
 * Both the UI thread (preview) and the DataStore collector ([onCommitted]) touch this state, so
 * the fields sit behind a lock instead of relying on one dispatcher.
 */
class SettingsDraft(committed: IslandSettings) {

    private val lock = Any()
    private var latestCommitted = committed
    private var pendingDraft: IslandSettings? = null

    /** The last value DataStore emitted. */
    val committed: IslandSettings
        get() = synchronized(lock) { latestCommitted }

    /** The uncommitted value, or null when nothing is pending. */
    val draft: IslandSettings?
        get() = synchronized(lock) { pendingDraft }

    val isDirty: Boolean
        get() = synchronized(lock) { pendingDraft != null }

    /** Applies [transform] on top of the newest value, so consecutive ticks chain into one draft. */
    fun preview(transform: (IslandSettings) -> IslandSettings) {
        synchronized(lock) {
            pendingDraft = transform(pendingDraft ?: latestCommitted)
        }
    }

    /**
     * A new value reached disk. An uncommitted draft survives: it is newer than the write that just
     * landed, and dropping it would roll a still-moving thumb back to the old value.
     */
    fun onCommitted(settings: IslandSettings) {
        synchronized(lock) {
            latestCommitted = settings
        }
    }

    /** Hands the draft to the caller exactly once and clears it; null means there is nothing to write. */
    fun takeForCommit(): IslandSettings? = synchronized(lock) {
        pendingDraft.also { pendingDraft = null }
    }
}
