package com.zenith.nothingisland.service

/**
 * Ordering for queued notification alerts, kept free of Android and timers so it can be unit tested.
 * [IslandStateHolder] owns the clock and publishes; this class decides which alert is next and
 * whether the island is rotating through several of them.
 *
 * - [upsert] adds or refreshes an alert and moves it to the end of arrival order.
 * - [seed] records an alert that was already in the shade when the listener connected. It counts
 *   toward the badge but never starts a rotation or a popup.
 * - Rotation starts when a *new* post arrives while more than one alert is queued, and it continues
 *   until the queue is empty, so the island keeps cycling until the last message is dismissed.
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

    fun seed(key: String, value: T) {
        if (!items.containsKey(key)) items[key] = value
    }

    /** Returns true when the alert was queued. Emptying the queue ends any rotation. */
    fun remove(key: String): Boolean {
        val removed = items.remove(key) != null
        if (items.isEmpty()) rotating = false
        return removed
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
        if (items.isEmpty()) return null
        val keys = items.keys.toList()
        val index = if (current == null) -1 else keys.indexOf(current)
        return keys[(index + 1) % keys.size]
    }
}
