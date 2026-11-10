package com.zenith.nothingisland.service.notification

/**
 * Popular second-party surfaces. These are not private SDK integrations: the island reads the
 * public notification/MediaSession contracts those apps already publish. The registry drives the
 * in-app opt-out list and a few package hints where Android categories are inconsistent.
 */
data class IslandIntegration(
    val packageName: String,
    val label: String,
    val kind: Kind,
) {
    enum class Kind { Messaging, Calls, Media, Maps, Camera, Downloads, Store, Clock }
}

object PopularIntegrations {
    val all: List<IslandIntegration> = listOf(
        IslandIntegration("com.whatsapp", "WhatsApp", IslandIntegration.Kind.Messaging),
        IslandIntegration("com.instagram.android", "Instagram", IslandIntegration.Kind.Messaging),
        IslandIntegration("com.snapchat.android", "Snapchat", IslandIntegration.Kind.Messaging),
        IslandIntegration("org.telegram.messenger", "Telegram", IslandIntegration.Kind.Messaging),
        IslandIntegration("com.facebook.orca", "Messenger", IslandIntegration.Kind.Messaging),
        IslandIntegration("com.discord", "Discord", IslandIntegration.Kind.Messaging),
        IslandIntegration("com.spotify.music", "Spotify", IslandIntegration.Kind.Media),
        IslandIntegration("com.google.android.apps.youtube.music", "YouTube Music", IslandIntegration.Kind.Media),
        IslandIntegration("com.google.android.apps.maps", "Google Maps", IslandIntegration.Kind.Maps),
        IslandIntegration("com.waze", "Waze", IslandIntegration.Kind.Maps),
        IslandIntegration("com.nothing.camera", "Nothing Camera", IslandIntegration.Kind.Camera),
        IslandIntegration("com.android.chrome", "Chrome downloads", IslandIntegration.Kind.Downloads),
        IslandIntegration("com.android.vending", "Play Store", IslandIntegration.Kind.Store),
        IslandIntegration("com.android.providers.downloads", "System downloads", IslandIntegration.Kind.Downloads),
        IslandIntegration("com.google.android.deskclock", "Google Clock", IslandIntegration.Kind.Clock),
        IslandIntegration("com.android.deskclock", "Clock", IslandIntegration.Kind.Clock),
    )

    val mapsPackages: Set<String> = all
        .filter { it.kind == IslandIntegration.Kind.Maps }
        .map { it.packageName }
        .toSet()

    /** Stock and Google Clock: timers and stopwatch run as ongoing activities with their controls. */
    val clockPackages: Set<String> = all
        .filter { it.kind == IslandIntegration.Kind.Clock }
        .map { it.packageName }
        .toSet()
}
