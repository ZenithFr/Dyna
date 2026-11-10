package com.zenith.nothingisland.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Immutable snapshot of every user-facing island knob. Geometry is in dp unless noted.
 *
 * The service and the notification listener collect the same DataStore flow, so a change in the
 * app reaches the overlay and the source filters without a restart. New keys are additive: older
 * preference files keep loading through the defaults below.
 */
data class IslandSettings(
    val offsetXDp: Int = DEFAULT_OFFSET_X_DP,
    val offsetYDp: Int = DEFAULT_OFFSET_Y_DP,
    val widthDp: Int = DEFAULT_WIDTH_DP,
    val heightDp: Int = DEFAULT_HEIGHT_DP,
    val cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
    val strokeEnabled: Boolean = DEFAULT_STROKE_ENABLED,
    val strokeWidthDp: Int = DEFAULT_STROKE_WIDTH_DP,
    val strokeColorArgb: Int = DEFAULT_STROKE_COLOR_ARGB,
    val hideInFullscreen: Boolean = DEFAULT_HIDE_IN_FULLSCREEN,
    val hideInLandscape: Boolean = DEFAULT_HIDE_IN_LANDSCAPE,
    val lockPortrait: Boolean = DEFAULT_LOCK_PORTRAIT,
    val holeEnabled: Boolean = DEFAULT_HOLE_ENABLED,
    val holeCenterXDp: Int = DEFAULT_HOLE_CENTER_X_DP,
    val holeCenterYDp: Int = DEFAULT_HOLE_CENTER_Y_DP,
    val holeRadiusDp: Int = DEFAULT_HOLE_RADIUS_DP,
    val holeMode: IslandHoleMode = DEFAULT_HOLE_MODE,
    val holeRatioPercent: Int = DEFAULT_HOLE_RATIO_PERCENT,
    val holeSide: IslandHoleSide = DEFAULT_HOLE_SIDE,
    val showHoleGuide: Boolean = DEFAULT_SHOW_HOLE_GUIDE,
    val islandShape: IslandShape = DEFAULT_ISLAND_SHAPE,
    val highQualityMotion: Boolean = DEFAULT_HIGH_QUALITY_MOTION,
    val idleContractDelaySec: Int = DEFAULT_IDLE_CONTRACT_DELAY_SEC,

    // Shell + text appearance. Opacity is separate from the colour so a hex entry stays RGB.
    val shellColorArgb: Int = DEFAULT_SHELL_COLOR_ARGB,
    val shellOpacityPercent: Int = DEFAULT_SHELL_OPACITY_PERCENT,
    val textColorArgb: Int = DEFAULT_TEXT_COLOR_ARGB,
    val secondaryTextColorArgb: Int = DEFAULT_SECONDARY_TEXT_COLOR_ARGB,
    val monochromeIcons: Boolean = DEFAULT_MONOCHROME_ICONS,

    // Interaction. The default keeps the documented tap-to-expand behaviour.
    val idleBehaviour: IslandIdleBehaviour = DEFAULT_IDLE_BEHAVIOUR,
    val tapAction: IslandTapAction = DEFAULT_TAP_ACTION,
    val longPressAction: IslandLongPressAction = DEFAULT_LONG_PRESS_ACTION,
    val hapticsEnabled: Boolean = DEFAULT_HAPTICS_ENABLED,

    // Source switches. Timer is user-owned and always available once started.
    val mediaEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val callsEnabled: Boolean = true,
    val chargingEnabled: Boolean = true,
    val batteryLowEnabled: Boolean = true,
    val powerSaveEnabled: Boolean = true,
    val downloadsEnabled: Boolean = true,
    val navigationEnabled: Boolean = true,
    /** Clock app (Google Clock, stock Clock): running timers and stopwatch as controllable activities. */
    val clockEnabled: Boolean = true,

    // Alert behaviour / privacy.
    val notificationDurationSec: Int = DEFAULT_NOTIFICATION_DURATION_SEC,
    /** How long an alert's app-icon bubble stays detached before retracting into the shell. */
    val bubbleDurationSec: Int = DEFAULT_BUBBLE_DURATION_SEC,
    val chargingDurationSec: Int = DEFAULT_CHARGING_DURATION_SEC,
    val batteryAlertDurationSec: Int = DEFAULT_BATTERY_ALERT_DURATION_SEC,
    val batteryLowThresholdPercent: Int = DEFAULT_BATTERY_LOW_THRESHOLD_PERCENT,
    val redactOnLockscreen: Boolean = true,
    val autoExpandCalls: Boolean = true,
    /** Alert app icons ride in the detached bubble; the shell keeps the text. */
    val alertIconInBubble: Boolean = DEFAULT_ALERT_ICON_IN_BUBBLE,
    val notificationShowTitle: Boolean = DEFAULT_NOTIFICATION_SHOW_TITLE,
    /** Silent/gentle notifications (importance LOW), including some muted chats, are shown by default. */
    val showSilentNotifications: Boolean = DEFAULT_SHOW_SILENT_NOTIFICATIONS,

    // Expanded geometry / typography.
    val expandedMaxWidthDp: Int = DEFAULT_EXPANDED_MAX_WIDTH_DP,
    val expandedCornerRadiusDp: Int = DEFAULT_EXPANDED_CORNER_RADIUS_DP,
    val contentMarginDp: Int = DEFAULT_CONTENT_MARGIN_DP,
    val sensorClearanceDp: Int = DEFAULT_SENSOR_CLEARANCE_DP,
    val islandTextScalePercent: Int = DEFAULT_ISLAND_TEXT_SCALE_PERCENT,
    /** Persistent live activities detach their owning app icon into a bubble beside the shell. */
    val detachedAppIcon: Boolean = true,
    val satelliteGapDp: Int = DEFAULT_SATELLITE_GAP_DP,
    val satelliteSizeDp: Int = DEFAULT_SATELLITE_SIZE_DP,
    val satelliteIconPaddingDp: Int = DEFAULT_SATELLITE_ICON_PADDING_DP,
    val compactIconSizeDp: Int = DEFAULT_COMPACT_ICON_SIZE_DP,
    val compactEdgePaddingDp: Int = DEFAULT_COMPACT_EDGE_PADDING_DP,

    // Motion / performance. Morphs use the panel's high-rate hint; decorative ambient loops are
    // opt-in so a settled island does not keep the GPU awake.
    val motionEnabled: Boolean = DEFAULT_MOTION_ENABLED,
    val ambientAnimationsEnabled: Boolean = DEFAULT_AMBIENT_ANIMATIONS_ENABLED,
    val ambientFrameRateHz: Int = DEFAULT_AMBIENT_FRAME_RATE_HZ,
    val motionScalePercent: Int = DEFAULT_MOTION_SCALE_PERCENT,

    // Per-source accents. Media keeps its artwork-sampled accent.
    val timerColorArgb: Int = DEFAULT_TIMER_COLOR_ARGB,
    val chargingColorArgb: Int = DEFAULT_CHARGING_COLOR_ARGB,
    val batteryLowColorArgb: Int = DEFAULT_BATTERY_LOW_COLOR_ARGB,
    val powerSaveColorArgb: Int = DEFAULT_POWER_SAVE_COLOR_ARGB,
    val callAnswerColorArgb: Int = DEFAULT_CALL_ANSWER_COLOR_ARGB,
    val callDeclineColorArgb: Int = DEFAULT_CALL_DECLINE_COLOR_ARGB,
    val downloadColorArgb: Int = DEFAULT_DOWNLOAD_COLOR_ARGB,
    val navigationColorArgb: Int = DEFAULT_NAVIGATION_COLOR_ARGB,

    // Second-party integration opt-outs. Empty means every allowed package can surface.
    val disabledPackages: Set<String> = emptySet(),

    // Diagnostics. Off by default: verbose logging must never be the reason the log buffer churns.
    val verboseLogging: Boolean = DEFAULT_VERBOSE_LOGGING,
) {
    fun coerced(): IslandSettings = copy(
        offsetXDp = offsetXDp.coerceIn(OFFSET_X_RANGE),
        offsetYDp = offsetYDp.coerceIn(OFFSET_Y_RANGE),
        widthDp = widthDp.coerceIn(WIDTH_RANGE),
        heightDp = heightDp.coerceIn(HEIGHT_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(CORNER_RADIUS_RANGE),
        strokeWidthDp = strokeWidthDp.coerceIn(STROKE_WIDTH_RANGE),
        holeCenterXDp = holeCenterXDp.coerceIn(HOLE_X_RANGE),
        holeCenterYDp = holeCenterYDp.coerceIn(HOLE_Y_RANGE),
        holeRadiusDp = holeRadiusDp.coerceIn(HOLE_RADIUS_RANGE),
        holeRatioPercent = holeRatioPercent.coerceIn(HOLE_RATIO_RANGE),
        idleContractDelaySec = idleContractDelaySec.coerceIn(IDLE_CONTRACT_DELAY_RANGE),
        notificationDurationSec = notificationDurationSec.coerceIn(ALERT_DURATION_RANGE),
        bubbleDurationSec = bubbleDurationSec.coerceIn(BUBBLE_DURATION_RANGE),
        chargingDurationSec = chargingDurationSec.coerceIn(ALERT_DURATION_RANGE),
        batteryAlertDurationSec = batteryAlertDurationSec.coerceIn(ALERT_DURATION_RANGE),
        batteryLowThresholdPercent = batteryLowThresholdPercent.coerceIn(BATTERY_LOW_THRESHOLD_RANGE),
        expandedMaxWidthDp = expandedMaxWidthDp.coerceIn(EXPANDED_MAX_WIDTH_RANGE),
        expandedCornerRadiusDp = expandedCornerRadiusDp.coerceIn(EXPANDED_CORNER_RADIUS_RANGE),
        contentMarginDp = contentMarginDp.coerceIn(CONTENT_MARGIN_RANGE),
        sensorClearanceDp = sensorClearanceDp.coerceIn(SENSOR_CLEARANCE_RANGE),
        islandTextScalePercent = islandTextScalePercent.coerceIn(ISLAND_TEXT_SCALE_RANGE),
        satelliteGapDp = satelliteGapDp.coerceIn(SATELLITE_GAP_RANGE),
        satelliteSizeDp = satelliteSizeDp.coerceIn(SATELLITE_SIZE_RANGE),
        satelliteIconPaddingDp = satelliteIconPaddingDp.coerceIn(SATELLITE_ICON_PADDING_RANGE),
        compactIconSizeDp = compactIconSizeDp.coerceIn(COMPACT_ICON_SIZE_RANGE),
        compactEdgePaddingDp = compactEdgePaddingDp.coerceIn(COMPACT_EDGE_PADDING_RANGE),
        ambientFrameRateHz = ambientFrameRateHz.coerceIn(AMBIENT_FRAME_RATE_RANGE),
        motionScalePercent = motionScalePercent.coerceIn(MOTION_SCALE_RANGE),
        shellOpacityPercent = shellOpacityPercent.coerceIn(SHELL_OPACITY_RANGE),
        disabledPackages = disabledPackages.toSet(),
    )

    companion object {
        val OFFSET_X_RANGE = -24..24
        val OFFSET_Y_RANGE = 0..24
        val WIDTH_RANGE = 28..300
        val HEIGHT_RANGE = 24..48
        val CORNER_RADIUS_RANGE = 8..24
        val STROKE_WIDTH_RANGE = 1..5
        val HOLE_X_RANGE = -80..80
        val HOLE_Y_RANGE = 0..60
        val HOLE_RADIUS_RANGE = 4..20
        val HOLE_RATIO_RANGE = 20..80
        val IDLE_CONTRACT_DELAY_RANGE = 1..10
        val HOLE_RATIO_UI_RANGE = 20..50
        val ALERT_DURATION_RANGE = 2..10
        val BUBBLE_DURATION_RANGE = 1..10
        val BATTERY_LOW_THRESHOLD_RANGE = 5..30
        val EXPANDED_MAX_WIDTH_RANGE = 280..408
        val EXPANDED_CORNER_RADIUS_RANGE = 24..44
        val CONTENT_MARGIN_RANGE = 8..20
        val SENSOR_CLEARANCE_RANGE = 2..8
        val ISLAND_TEXT_SCALE_RANGE = 90..130
        val SATELLITE_GAP_RANGE = 4..20
        val SATELLITE_SIZE_RANGE = 24..48
        val SATELLITE_ICON_PADDING_RANGE = 0..8
        val COMPACT_ICON_SIZE_RANGE = 12..28
        val COMPACT_EDGE_PADDING_RANGE = 4..16
        val AMBIENT_FRAME_RATE_RANGE = 30..120
        val MOTION_SCALE_RANGE = 50..150
        val SHELL_OPACITY_RANGE = 40..100

        const val DEFAULT_OFFSET_X_DP = 0
        const val DEFAULT_OFFSET_Y_DP = 6
        const val DEFAULT_WIDTH_DP = 110
        const val DEFAULT_HEIGHT_DP = 32
        const val DEFAULT_CORNER_RADIUS_DP = 16
        const val DEFAULT_STROKE_ENABLED = true
        const val DEFAULT_STROKE_WIDTH_DP = 1
        const val DEFAULT_STROKE_COLOR_ARGB = 0x26FFFFFF
        const val DEFAULT_HIDE_IN_FULLSCREEN = true
        const val DEFAULT_HIDE_IN_LANDSCAPE = true
        const val DEFAULT_LOCK_PORTRAIT = true
        const val DEFAULT_HOLE_ENABLED = true
        const val DEFAULT_HOLE_CENTER_X_DP = 0
        const val DEFAULT_HOLE_CENTER_Y_DP = 26
        const val DEFAULT_HOLE_RADIUS_DP = 8
        val DEFAULT_HOLE_MODE = IslandHoleMode.SYMMETRIC
        const val DEFAULT_HOLE_RATIO_PERCENT = 35
        val DEFAULT_HOLE_SIDE = IslandHoleSide.LEFT
        const val DEFAULT_SHOW_HOLE_GUIDE = false
        val DEFAULT_ISLAND_SHAPE = IslandShape.PILL
        const val DEFAULT_HIGH_QUALITY_MOTION = true
        const val DEFAULT_IDLE_CONTRACT_DELAY_SEC = 1
        const val DEFAULT_SHELL_COLOR_ARGB = 0xFF000000.toInt()
        const val DEFAULT_SHELL_OPACITY_PERCENT = 100
        const val DEFAULT_TEXT_COLOR_ARGB = 0xFFFFFFFF.toInt()
        const val DEFAULT_SECONDARY_TEXT_COLOR_ARGB = 0xAAFFFFFF.toInt()
        const val DEFAULT_MONOCHROME_ICONS = false
        val DEFAULT_IDLE_BEHAVIOUR = IslandIdleBehaviour.HIDDEN
        val DEFAULT_TAP_ACTION = IslandTapAction.EXPAND
        val DEFAULT_LONG_PRESS_ACTION = IslandLongPressAction.OPEN_APP
        const val DEFAULT_HAPTICS_ENABLED = true
        const val DEFAULT_ALERT_ICON_IN_BUBBLE = true
        const val DEFAULT_NOTIFICATION_SHOW_TITLE = true
        const val DEFAULT_SHOW_SILENT_NOTIFICATIONS = true
        const val DEFAULT_MOTION_ENABLED = true
        const val DEFAULT_VERBOSE_LOGGING = false
        const val DEFAULT_NOTIFICATION_DURATION_SEC = 4
        const val DEFAULT_BUBBLE_DURATION_SEC = 3
        const val DEFAULT_CHARGING_DURATION_SEC = 4
        const val DEFAULT_BATTERY_ALERT_DURATION_SEC = 4
        const val DEFAULT_BATTERY_LOW_THRESHOLD_PERCENT = 20
        const val DEFAULT_EXPANDED_MAX_WIDTH_DP = 408
        const val DEFAULT_EXPANDED_CORNER_RADIUS_DP = 44
        const val DEFAULT_CONTENT_MARGIN_DP = 12
        const val DEFAULT_SENSOR_CLEARANCE_DP = 4
        const val DEFAULT_ISLAND_TEXT_SCALE_PERCENT = 100
        const val DEFAULT_SATELLITE_GAP_DP = 8
        const val DEFAULT_SATELLITE_SIZE_DP = 32
        const val DEFAULT_SATELLITE_ICON_PADDING_DP = 3
        const val DEFAULT_COMPACT_ICON_SIZE_DP = 20
        const val DEFAULT_COMPACT_EDGE_PADDING_DP = 8
        const val DEFAULT_AMBIENT_ANIMATIONS_ENABLED = false
        const val DEFAULT_AMBIENT_FRAME_RATE_HZ = 30
        const val DEFAULT_MOTION_SCALE_PERCENT = 100
        const val DEFAULT_TIMER_COLOR_ARGB = 0xFFFF9F0A.toInt()
        const val DEFAULT_CHARGING_COLOR_ARGB = 0xFF30D158.toInt()
        const val DEFAULT_BATTERY_LOW_COLOR_ARGB = 0xFFFF453A.toInt()
        const val DEFAULT_POWER_SAVE_COLOR_ARGB = 0xFFFFD60A.toInt()
        const val DEFAULT_CALL_ANSWER_COLOR_ARGB = 0xFF30D158.toInt()
        const val DEFAULT_CALL_DECLINE_COLOR_ARGB = 0xFFFF453A.toInt()
        const val DEFAULT_DOWNLOAD_COLOR_ARGB = 0xFF64D2FF.toInt()
        const val DEFAULT_NAVIGATION_COLOR_ARGB = 0xFF30D158.toInt()
    }
}

private val Context.islandSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "island_settings",
)

/** Single source of truth for the island's look and source filters. */
class IslandSettingsRepository private constructor(private val context: Context) {

    val settings: Flow<IslandSettings> = context.islandSettingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toIslandSettings() }
        .distinctUntilChanged()

    /** Applies [transform] atomically and clamps the result before persisting. */
    suspend fun update(transform: (IslandSettings) -> IslandSettings) {
        context.islandSettingsDataStore.edit { preferences ->
            val next = transform(preferences.toIslandSettings()).coerced()
            preferences[Keys.offsetX] = next.offsetXDp
            preferences[Keys.offsetY] = next.offsetYDp
            preferences[Keys.width] = next.widthDp
            preferences[Keys.height] = next.heightDp
            preferences[Keys.cornerRadius] = next.cornerRadiusDp
            preferences[Keys.strokeEnabled] = next.strokeEnabled
            preferences[Keys.strokeWidth] = next.strokeWidthDp
            preferences[Keys.strokeColor] = next.strokeColorArgb
            preferences[Keys.hideInFullscreen] = next.hideInFullscreen
            preferences[Keys.hideInLandscape] = next.hideInLandscape
            preferences[Keys.lockPortrait] = next.lockPortrait
            preferences[Keys.holeEnabled] = next.holeEnabled
            preferences[Keys.holeCenterX] = next.holeCenterXDp
            preferences[Keys.holeCenterY] = next.holeCenterYDp
            preferences[Keys.holeRadius] = next.holeRadiusDp
            preferences[Keys.holeMode] = next.holeMode.name
            preferences[Keys.holeRatio] = next.holeRatioPercent
            preferences[Keys.holeSide] = next.holeSide.name
            preferences[Keys.showHoleGuide] = next.showHoleGuide
            preferences[Keys.islandShape] = next.islandShape.name
            preferences[Keys.highQualityMotion] = next.highQualityMotion
            preferences[Keys.idleContractDelay] = next.idleContractDelaySec
            preferences[Keys.shellColor] = next.shellColorArgb
            preferences[Keys.shellOpacity] = next.shellOpacityPercent
            preferences[Keys.textColor] = next.textColorArgb
            preferences[Keys.secondaryTextColor] = next.secondaryTextColorArgb
            preferences[Keys.monochromeIcons] = next.monochromeIcons
            preferences[Keys.idleBehaviour] = next.idleBehaviour.name
            preferences[Keys.tapAction] = next.tapAction.name
            preferences[Keys.longPressAction] = next.longPressAction.name
            preferences[Keys.hapticsEnabled] = next.hapticsEnabled
            preferences[Keys.alertIconInBubble] = next.alertIconInBubble
            preferences[Keys.notificationShowTitle] = next.notificationShowTitle
            preferences[Keys.showSilentNotifications] = next.showSilentNotifications
            preferences[Keys.motionEnabled] = next.motionEnabled
            preferences[Keys.verboseLogging] = next.verboseLogging
            preferences[Keys.mediaEnabled] = next.mediaEnabled
            preferences[Keys.notificationsEnabled] = next.notificationsEnabled
            preferences[Keys.callsEnabled] = next.callsEnabled
            preferences[Keys.chargingEnabled] = next.chargingEnabled
            preferences[Keys.batteryLowEnabled] = next.batteryLowEnabled
            preferences[Keys.powerSaveEnabled] = next.powerSaveEnabled
            preferences[Keys.downloadsEnabled] = next.downloadsEnabled
            preferences[Keys.navigationEnabled] = next.navigationEnabled
            preferences[Keys.clockEnabled] = next.clockEnabled
            preferences[Keys.notificationDuration] = next.notificationDurationSec
            preferences[Keys.bubbleDuration] = next.bubbleDurationSec
            preferences[Keys.chargingDuration] = next.chargingDurationSec
            preferences[Keys.batteryAlertDuration] = next.batteryAlertDurationSec
            preferences[Keys.batteryLowThreshold] = next.batteryLowThresholdPercent
            preferences[Keys.redactOnLockscreen] = next.redactOnLockscreen
            preferences[Keys.autoExpandCalls] = next.autoExpandCalls
            preferences[Keys.expandedMaxWidth] = next.expandedMaxWidthDp
            preferences[Keys.expandedCornerRadius] = next.expandedCornerRadiusDp
            preferences[Keys.contentMargin] = next.contentMarginDp
            preferences[Keys.sensorClearance] = next.sensorClearanceDp
            preferences[Keys.islandTextScale] = next.islandTextScalePercent
            preferences[Keys.detachedAppIcon] = next.detachedAppIcon
            preferences[Keys.satelliteGap] = next.satelliteGapDp
            preferences[Keys.satelliteSize] = next.satelliteSizeDp
            preferences[Keys.satelliteIconPadding] = next.satelliteIconPaddingDp
            preferences[Keys.compactIconSize] = next.compactIconSizeDp
            preferences[Keys.compactEdgePadding] = next.compactEdgePaddingDp
            preferences[Keys.ambientAnimationsEnabled] = next.ambientAnimationsEnabled
            preferences[Keys.ambientFrameRate] = next.ambientFrameRateHz
            preferences[Keys.motionScale] = next.motionScalePercent
            preferences[Keys.timerColor] = next.timerColorArgb
            preferences[Keys.chargingColor] = next.chargingColorArgb
            preferences[Keys.batteryLowColor] = next.batteryLowColorArgb
            preferences[Keys.powerSaveColor] = next.powerSaveColorArgb
            preferences[Keys.callAnswerColor] = next.callAnswerColorArgb
            preferences[Keys.callDeclineColor] = next.callDeclineColorArgb
            preferences[Keys.downloadColor] = next.downloadColorArgb
            preferences[Keys.navigationColor] = next.navigationColorArgb
            preferences[Keys.disabledPackages] = next.disabledPackages
        }
    }

    suspend fun setOffsetXDp(value: Int) = update { it.copy(offsetXDp = value) }
    suspend fun setOffsetYDp(value: Int) = update { it.copy(offsetYDp = value) }
    suspend fun setWidthDp(value: Int) = update { it.copy(widthDp = value) }
    suspend fun setHeightDp(value: Int) = update { it.copy(heightDp = value) }
    suspend fun setCornerRadiusDp(value: Int) = update { it.copy(cornerRadiusDp = value) }
    suspend fun setStrokeEnabled(enabled: Boolean) = update { it.copy(strokeEnabled = enabled) }
    suspend fun setStrokeWidthDp(value: Int) = update { it.copy(strokeWidthDp = value) }
    suspend fun setStrokeColorArgb(argb: Int) = update { it.copy(strokeColorArgb = argb) }
    suspend fun setHideInFullscreen(enabled: Boolean) = update { it.copy(hideInFullscreen = enabled) }
    suspend fun setHideInLandscape(enabled: Boolean) = update { it.copy(hideInLandscape = enabled) }
    suspend fun setLockPortrait(enabled: Boolean) = update { it.copy(lockPortrait = enabled) }
    suspend fun setHoleEnabled(enabled: Boolean) = update { it.copy(holeEnabled = enabled) }
    suspend fun setHoleCenterXDp(value: Int) = update { it.copy(holeCenterXDp = value) }
    suspend fun setHoleCenterYDp(value: Int) = update { it.copy(holeCenterYDp = value) }
    suspend fun setHoleRadiusDp(value: Int) = update { it.copy(holeRadiusDp = value) }
    suspend fun setHoleMode(mode: IslandHoleMode) = update { it.copy(holeMode = mode) }
    suspend fun setHoleRatioPercent(value: Int) = update { it.copy(holeRatioPercent = value) }
    suspend fun setHoleSide(side: IslandHoleSide) = update { it.copy(holeSide = side) }
    suspend fun setShowHoleGuide(enabled: Boolean) = update { it.copy(showHoleGuide = enabled) }
    suspend fun setIslandShape(shape: IslandShape) = update { it.copy(islandShape = shape) }
    suspend fun setHighQualityMotion(enabled: Boolean) = update { it.copy(highQualityMotion = enabled) }
    suspend fun setIdleContractDelaySec(value: Int) = update { it.copy(idleContractDelaySec = value) }
    suspend fun resetToDefaults() = update { IslandSettings() }

    companion object {
        @Volatile
        private var instance: IslandSettingsRepository? = null

        fun get(context: Context): IslandSettingsRepository =
            instance ?: synchronized(this) {
                instance ?: IslandSettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}

private object Keys {
    val offsetX = intPreferencesKey("offset_x_dp")
    val offsetY = intPreferencesKey("offset_y_dp")
    val width = intPreferencesKey("width_dp")
    val height = intPreferencesKey("height_dp")
    val cornerRadius = intPreferencesKey("corner_radius_dp")
    val strokeEnabled = booleanPreferencesKey("stroke_enabled")
    val strokeWidth = intPreferencesKey("stroke_width_dp")
    val strokeColor = intPreferencesKey("stroke_color_argb")
    val hideInFullscreen = booleanPreferencesKey("hide_in_fullscreen")
    val hideInLandscape = booleanPreferencesKey("hide_in_landscape")
    val lockPortrait = booleanPreferencesKey("lock_portrait")
    val holeEnabled = booleanPreferencesKey("hole_enabled")
    val holeCenterX = intPreferencesKey("hole_center_x_dp")
    val holeCenterY = intPreferencesKey("hole_center_y_dp")
    val holeRadius = intPreferencesKey("hole_radius_dp")
    val holeMode = stringPreferencesKey("hole_mode")
    val holeRatio = intPreferencesKey("hole_ratio_percent")
    val holeSide = stringPreferencesKey("hole_side")
    val showHoleGuide = booleanPreferencesKey("show_hole_guide")
    val islandShape = stringPreferencesKey("island_shape")
    val highQualityMotion = booleanPreferencesKey("motion_120hz")
    val idleContractDelay = intPreferencesKey("idle_contract_delay_sec")
    val shellColor = intPreferencesKey("shell_color_argb")
    val shellOpacity = intPreferencesKey("shell_opacity_percent")
    val textColor = intPreferencesKey("text_color_argb")
    val secondaryTextColor = intPreferencesKey("secondary_text_color_argb")
    val monochromeIcons = booleanPreferencesKey("monochrome_icons")
    val idleBehaviour = stringPreferencesKey("idle_behaviour")
    val tapAction = stringPreferencesKey("tap_action")
    val longPressAction = stringPreferencesKey("long_press_action")
    val hapticsEnabled = booleanPreferencesKey("haptics_enabled")
    val alertIconInBubble = booleanPreferencesKey("alert_icon_in_bubble")
    val notificationShowTitle = booleanPreferencesKey("notification_show_title")
    val showSilentNotifications = booleanPreferencesKey("show_silent_notifications")
    val motionEnabled = booleanPreferencesKey("motion_enabled")
    val verboseLogging = booleanPreferencesKey("verbose_logging")
    val mediaEnabled = booleanPreferencesKey("source_media_enabled")
    val notificationsEnabled = booleanPreferencesKey("source_notifications_enabled")
    val callsEnabled = booleanPreferencesKey("source_calls_enabled")
    val chargingEnabled = booleanPreferencesKey("source_charging_enabled")
    val batteryLowEnabled = booleanPreferencesKey("source_battery_low_enabled")
    val powerSaveEnabled = booleanPreferencesKey("source_power_save_enabled")
    val downloadsEnabled = booleanPreferencesKey("source_downloads_enabled")
    val navigationEnabled = booleanPreferencesKey("source_navigation_enabled")
    val clockEnabled = booleanPreferencesKey("source_clock_enabled")
    val notificationDuration = intPreferencesKey("notification_duration_sec")
    val bubbleDuration = intPreferencesKey("bubble_duration_sec")
    val chargingDuration = intPreferencesKey("charging_duration_sec")
    val batteryAlertDuration = intPreferencesKey("battery_alert_duration_sec")
    val batteryLowThreshold = intPreferencesKey("battery_low_threshold_percent")
    val redactOnLockscreen = booleanPreferencesKey("redact_on_lockscreen")
    val autoExpandCalls = booleanPreferencesKey("auto_expand_calls")
    val expandedMaxWidth = intPreferencesKey("expanded_max_width_dp")
    val expandedCornerRadius = intPreferencesKey("expanded_corner_radius_dp")
    val contentMargin = intPreferencesKey("content_margin_dp")
    val sensorClearance = intPreferencesKey("sensor_clearance_dp")
    val islandTextScale = intPreferencesKey("island_text_scale_percent")
    val detachedAppIcon = booleanPreferencesKey("detached_app_icon")
    val satelliteGap = intPreferencesKey("satellite_gap_dp")
    val satelliteSize = intPreferencesKey("satellite_size_dp")
    val satelliteIconPadding = intPreferencesKey("satellite_icon_padding_dp")
    val compactIconSize = intPreferencesKey("compact_icon_size_dp")
    val compactEdgePadding = intPreferencesKey("compact_edge_padding_dp")
    val ambientAnimationsEnabled = booleanPreferencesKey("ambient_animations_enabled")
    val ambientFrameRate = intPreferencesKey("ambient_frame_rate_hz")
    val motionScale = intPreferencesKey("motion_scale_percent")
    val timerColor = intPreferencesKey("timer_color_argb")
    val chargingColor = intPreferencesKey("charging_color_argb")
    val batteryLowColor = intPreferencesKey("battery_low_color_argb")
    val powerSaveColor = intPreferencesKey("power_save_color_argb")
    val callAnswerColor = intPreferencesKey("call_answer_color_argb")
    val callDeclineColor = intPreferencesKey("call_decline_color_argb")
    val downloadColor = intPreferencesKey("download_color_argb")
    val navigationColor = intPreferencesKey("navigation_color_argb")
    val disabledPackages = stringSetPreferencesKey("disabled_packages")
}

private fun Preferences.toIslandSettings(): IslandSettings = IslandSettings(
    offsetXDp = this[Keys.offsetX] ?: IslandSettings.DEFAULT_OFFSET_X_DP,
    offsetYDp = this[Keys.offsetY] ?: IslandSettings.DEFAULT_OFFSET_Y_DP,
    widthDp = this[Keys.width] ?: IslandSettings.DEFAULT_WIDTH_DP,
    heightDp = this[Keys.height] ?: IslandSettings.DEFAULT_HEIGHT_DP,
    cornerRadiusDp = this[Keys.cornerRadius] ?: IslandSettings.DEFAULT_CORNER_RADIUS_DP,
    strokeEnabled = this[Keys.strokeEnabled] ?: IslandSettings.DEFAULT_STROKE_ENABLED,
    strokeWidthDp = this[Keys.strokeWidth] ?: IslandSettings.DEFAULT_STROKE_WIDTH_DP,
    strokeColorArgb = this[Keys.strokeColor] ?: IslandSettings.DEFAULT_STROKE_COLOR_ARGB,
    hideInFullscreen = this[Keys.hideInFullscreen] ?: IslandSettings.DEFAULT_HIDE_IN_FULLSCREEN,
    hideInLandscape = this[Keys.hideInLandscape] ?: IslandSettings.DEFAULT_HIDE_IN_LANDSCAPE,
    lockPortrait = this[Keys.lockPortrait] ?: IslandSettings.DEFAULT_LOCK_PORTRAIT,
    holeEnabled = this[Keys.holeEnabled] ?: IslandSettings.DEFAULT_HOLE_ENABLED,
    holeCenterXDp = this[Keys.holeCenterX] ?: IslandSettings.DEFAULT_HOLE_CENTER_X_DP,
    holeCenterYDp = this[Keys.holeCenterY] ?: IslandSettings.DEFAULT_HOLE_CENTER_Y_DP,
    holeRadiusDp = this[Keys.holeRadius] ?: IslandSettings.DEFAULT_HOLE_RADIUS_DP,
    holeMode = this[Keys.holeMode].enumOrDefault(IslandSettings.DEFAULT_HOLE_MODE),
    holeRatioPercent = this[Keys.holeRatio] ?: IslandSettings.DEFAULT_HOLE_RATIO_PERCENT,
    holeSide = this[Keys.holeSide].enumOrDefault(IslandSettings.DEFAULT_HOLE_SIDE),
    showHoleGuide = this[Keys.showHoleGuide] ?: IslandSettings.DEFAULT_SHOW_HOLE_GUIDE,
    islandShape = this[Keys.islandShape].enumOrDefault(IslandSettings.DEFAULT_ISLAND_SHAPE),
    highQualityMotion = this[Keys.highQualityMotion] ?: IslandSettings.DEFAULT_HIGH_QUALITY_MOTION,
    idleContractDelaySec = this[Keys.idleContractDelay] ?: IslandSettings.DEFAULT_IDLE_CONTRACT_DELAY_SEC,
    shellColorArgb = this[Keys.shellColor] ?: IslandSettings.DEFAULT_SHELL_COLOR_ARGB,
    shellOpacityPercent = this[Keys.shellOpacity] ?: IslandSettings.DEFAULT_SHELL_OPACITY_PERCENT,
    textColorArgb = this[Keys.textColor] ?: IslandSettings.DEFAULT_TEXT_COLOR_ARGB,
    secondaryTextColorArgb = this[Keys.secondaryTextColor] ?: IslandSettings.DEFAULT_SECONDARY_TEXT_COLOR_ARGB,
    monochromeIcons = this[Keys.monochromeIcons] ?: IslandSettings.DEFAULT_MONOCHROME_ICONS,
    idleBehaviour = this[Keys.idleBehaviour].enumOrDefault(IslandSettings.DEFAULT_IDLE_BEHAVIOUR),
    tapAction = this[Keys.tapAction].enumOrDefault(IslandSettings.DEFAULT_TAP_ACTION),
    longPressAction = this[Keys.longPressAction].enumOrDefault(IslandSettings.DEFAULT_LONG_PRESS_ACTION),
    hapticsEnabled = this[Keys.hapticsEnabled] ?: IslandSettings.DEFAULT_HAPTICS_ENABLED,
    alertIconInBubble = this[Keys.alertIconInBubble] ?: IslandSettings.DEFAULT_ALERT_ICON_IN_BUBBLE,
    notificationShowTitle = this[Keys.notificationShowTitle] ?: IslandSettings.DEFAULT_NOTIFICATION_SHOW_TITLE,
    showSilentNotifications = this[Keys.showSilentNotifications] ?: IslandSettings.DEFAULT_SHOW_SILENT_NOTIFICATIONS,
    motionEnabled = this[Keys.motionEnabled] ?: IslandSettings.DEFAULT_MOTION_ENABLED,
    verboseLogging = this[Keys.verboseLogging] ?: IslandSettings.DEFAULT_VERBOSE_LOGGING,
    mediaEnabled = this[Keys.mediaEnabled] ?: true,
    notificationsEnabled = this[Keys.notificationsEnabled] ?: true,
    callsEnabled = this[Keys.callsEnabled] ?: true,
    chargingEnabled = this[Keys.chargingEnabled] ?: true,
    batteryLowEnabled = this[Keys.batteryLowEnabled] ?: true,
    powerSaveEnabled = this[Keys.powerSaveEnabled] ?: true,
    downloadsEnabled = this[Keys.downloadsEnabled] ?: true,
    navigationEnabled = this[Keys.navigationEnabled] ?: true,
    clockEnabled = this[Keys.clockEnabled] ?: true,
    notificationDurationSec = this[Keys.notificationDuration] ?: IslandSettings.DEFAULT_NOTIFICATION_DURATION_SEC,
    bubbleDurationSec = this[Keys.bubbleDuration] ?: IslandSettings.DEFAULT_BUBBLE_DURATION_SEC,
    chargingDurationSec = this[Keys.chargingDuration] ?: IslandSettings.DEFAULT_CHARGING_DURATION_SEC,
    batteryAlertDurationSec = this[Keys.batteryAlertDuration] ?: IslandSettings.DEFAULT_BATTERY_ALERT_DURATION_SEC,
    batteryLowThresholdPercent = this[Keys.batteryLowThreshold] ?: IslandSettings.DEFAULT_BATTERY_LOW_THRESHOLD_PERCENT,
    redactOnLockscreen = this[Keys.redactOnLockscreen] ?: true,
    autoExpandCalls = this[Keys.autoExpandCalls] ?: true,
    expandedMaxWidthDp = this[Keys.expandedMaxWidth] ?: IslandSettings.DEFAULT_EXPANDED_MAX_WIDTH_DP,
    expandedCornerRadiusDp = this[Keys.expandedCornerRadius] ?: IslandSettings.DEFAULT_EXPANDED_CORNER_RADIUS_DP,
    contentMarginDp = this[Keys.contentMargin] ?: IslandSettings.DEFAULT_CONTENT_MARGIN_DP,
    sensorClearanceDp = this[Keys.sensorClearance] ?: IslandSettings.DEFAULT_SENSOR_CLEARANCE_DP,
    islandTextScalePercent = this[Keys.islandTextScale] ?: IslandSettings.DEFAULT_ISLAND_TEXT_SCALE_PERCENT,
    detachedAppIcon = this[Keys.detachedAppIcon] ?: true,
    satelliteGapDp = this[Keys.satelliteGap] ?: IslandSettings.DEFAULT_SATELLITE_GAP_DP,
    satelliteSizeDp = this[Keys.satelliteSize] ?: IslandSettings.DEFAULT_SATELLITE_SIZE_DP,
    satelliteIconPaddingDp = this[Keys.satelliteIconPadding] ?: IslandSettings.DEFAULT_SATELLITE_ICON_PADDING_DP,
    compactIconSizeDp = this[Keys.compactIconSize] ?: IslandSettings.DEFAULT_COMPACT_ICON_SIZE_DP,
    compactEdgePaddingDp = this[Keys.compactEdgePadding] ?: IslandSettings.DEFAULT_COMPACT_EDGE_PADDING_DP,
    ambientAnimationsEnabled = this[Keys.ambientAnimationsEnabled] ?: IslandSettings.DEFAULT_AMBIENT_ANIMATIONS_ENABLED,
    ambientFrameRateHz = this[Keys.ambientFrameRate] ?: IslandSettings.DEFAULT_AMBIENT_FRAME_RATE_HZ,
    motionScalePercent = this[Keys.motionScale] ?: IslandSettings.DEFAULT_MOTION_SCALE_PERCENT,
    timerColorArgb = this[Keys.timerColor] ?: IslandSettings.DEFAULT_TIMER_COLOR_ARGB,
    chargingColorArgb = this[Keys.chargingColor] ?: IslandSettings.DEFAULT_CHARGING_COLOR_ARGB,
    batteryLowColorArgb = this[Keys.batteryLowColor] ?: IslandSettings.DEFAULT_BATTERY_LOW_COLOR_ARGB,
    powerSaveColorArgb = this[Keys.powerSaveColor] ?: IslandSettings.DEFAULT_POWER_SAVE_COLOR_ARGB,
    callAnswerColorArgb = this[Keys.callAnswerColor] ?: IslandSettings.DEFAULT_CALL_ANSWER_COLOR_ARGB,
    callDeclineColorArgb = this[Keys.callDeclineColor] ?: IslandSettings.DEFAULT_CALL_DECLINE_COLOR_ARGB,
    downloadColorArgb = this[Keys.downloadColor] ?: IslandSettings.DEFAULT_DOWNLOAD_COLOR_ARGB,
    navigationColorArgb = this[Keys.navigationColor] ?: IslandSettings.DEFAULT_NAVIGATION_COLOR_ARGB,
    disabledPackages = this[Keys.disabledPackages] ?: emptySet(),
).coerced()

private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
    if (this == null) default else runCatching { enumValueOf<T>(this) }.getOrDefault(default)
