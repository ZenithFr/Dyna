package com.zenith.nothingisland.service

/**
 * Ordering for queued notification alerts, kept free of Android and timers so it can be unit tested.
 * [IslandStateHolder] owns the clock and publishes; this class decides which alert is next and
 * whether the island is rotating through several of them.
 *
 * - [upsert] adds or refreshes an alert and moves it to the end of arrival order.
 * - Rotation starts when a *new* post arrives while more than one alert is queued, and each alert
 *   gets exactly one turn: [IslandStateHolder] drops it once its hold ends, so a message that was
 *   already seen never comes back behind the next post.
 */
class AlertStack<T> {
    private val items = LinkedHashMap<String, T>()

    /** True once a post arrived while several alerts were queued; cleared when the queue empties. */
    var rotating: Boolean = false
        private set

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun get(key: String): T? = items[key]

    fun upsert(key: String, value: T) {
        items.remove(key)
        items[key] = value
        if (items.size > 1) rotating = true
    }

    /** Returns true when the alert was queued. Emptying the queue ends any rotation. */
    fun remove(key: String): Boolean {
        val removed = items.remove(key) != null
        if (items.isEmpty()) rotating = false
        return removed
    }

    /**
     * Drops every alert the shade no longer has, keeping arrival order for the rest. Returns how
     * many were dropped; emptying the queue ends any rotation.
     */
    fun retain(liveKeys: Set<String>): Int {
        val before = items.size
        items.keys.retainAll(liveKeys)
        if (items.isEmpty()) rotating = false
        return before - items.size
    }

    fun clear() {
        items.clear()
        rotating = false
    }

    /**
     * The alert after [current] in arrival order, wrapping to the first. An unknown or null
     * [current] (for example one that was just dismissed) yields the first alert.
     */
    fun nextKey(current: String?): String? {
        val keys = items.keys.toList()
        if (keys.isEmpty()) return null
        val index = if (current == null) -1 else keys.indexOf(current)
        return keys[(index + 1) % keys.size]
    }
}
