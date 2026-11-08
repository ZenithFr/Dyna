package com.zenith.nothingisland.overlay

/** Transport commands the island can send to the media module. */
enum class TransportCommand { Previous, PlayPause, Next }

/** Callbacks the pill fires on tap. Implemented by the accessibility service. */
interface IslandInteraction {
    fun onBodyTap()
    fun onTransport(command: TransportCommand)
    fun onNotificationAction(index: Int)

    /** Expanded timer: pause when running, resume when paused. */
    fun onTimerToggle()

    /** Expanded timer: cancel the countdown and clear the pill. */
    fun onTimerStop()
}
