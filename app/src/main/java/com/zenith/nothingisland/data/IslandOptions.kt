package com.zenith.nothingisland.data

/**
 * What the island does when it has nothing to show.
 *
 * [HIDDEN] is the battery-first default: the shell curls into the camera hole and the window is
 * detached, so no surface, buffer or frame callback survives an idle island. [PILL] keeps the empty
 * compact shell on screen for users who want the island always visible; it is static and requests
 * no frames until something changes.
 */
enum class IslandIdleBehaviour { HIDDEN, PILL }

/** Primary tap on the island shell. */
enum class IslandTapAction { EXPAND, OPEN_APP, NONE }

/** Long press on the island shell (Apple's "open the owning app" affordance). */
enum class IslandLongPressAction { OPEN_APP, EXPAND, NONE }

/**
 * Settings categories: the single source of truth for both the app's page navigation and the
 * per-category "restore defaults" action, so a new knob can never be forgotten by one of them.
 */
enum class IslandSettingsCategory(val title: String, val summary: String) {
    SETUP("Setup", "Permissions, service status and diagnostics"),
    APPEARANCE("Appearance", "Shell colour, opacity, stroke, shape, text and accents"),
    LAYOUT("Layout", "Compact pill, expanded card and detached bubble geometry"),
    CAMERA_HOLE("Camera hole", "Lens position, radius, split mode and on-screen guide"),
    NOTIFICATIONS("Notifications & apps", "Alert holds, privacy, bubble icon and per-app opt-outs"),
    SOURCES("Sources & timer", "What the island is allowed to show, plus the countdown"),
    BEHAVIOUR("Behaviour", "Idle behaviour, taps, haptics and display hiding"),
    MOTION("Motion & battery", "Animation quality, ambient loops and power spend"),
    ADVANCED("Advanced", "Category resets, logging and about"),
}

/**
 * Restores one category without touching the rest of the user's calibration. Pure so the mapping
 * is unit-testable and cannot drift from the persisted keys.
 */
fun IslandSettings.resetCategory(category: IslandSettingsCategory): IslandSettings {
    val defaults = IslandSettings()
    return when (category) {
        IslandSettingsCategory.SETUP -> this
        IslandSettingsCategory.APPEARANCE -> copy(
            islandShape = defaults.islandShape,
            strokeEnabled = defaults.strokeEnabled,
            strokeWidthDp = defaults.strokeWidthDp,
            strokeColorArgb = defaults.strokeColorArgb,
            shellColorArgb = defaults.shellColorArgb,
            shellOpacityPercent = defaults.shellOpacityPercent,
            textColorArgb = defaults.textColorArgb,
            secondaryTextColorArgb = defaults.secondaryTextColorArgb,
            monochromeIcons = defaults.monochromeIcons,
            islandTextScalePercent = defaults.islandTextScalePercent,
            timerColorArgb = defaults.timerColorArgb,
            chargingColorArgb = defaults.chargingColorArgb,
            batteryLowColorArgb = defaults.batteryLowColorArgb,
            powerSaveColorArgb = defaults.powerSaveColorArgb,
            callAnswerColorArgb = defaults.callAnswerColorArgb,
            callDeclineColorArgb = defaults.callDeclineColorArgb,
            downloadColorArgb = defaults.downloadColorArgb,
            navigationColorArgb = defaults.navigationColorArgb,
        )
        IslandSettingsCategory.LAYOUT -> copy(
            offsetXDp = defaults.offsetXDp,
            offsetYDp = defaults.offsetYDp,
            widthDp = defaults.widthDp,
            heightDp = defaults.heightDp,
            cornerRadiusDp = defaults.cornerRadiusDp,
            compactIconSizeDp = defaults.compactIconSizeDp,
            compactEdgePaddingDp = defaults.compactEdgePaddingDp,
            expandedMaxWidthDp = defaults.expandedMaxWidthDp,
            expandedCornerRadiusDp = defaults.expandedCornerRadiusDp,
            contentMarginDp = defaults.contentMarginDp,
            sensorClearanceDp = defaults.sensorClearanceDp,
            detachedAppIcon = defaults.detachedAppIcon,
            satelliteGapDp = defaults.satelliteGapDp,
            satelliteSizeDp = defaults.satelliteSizeDp,
            satelliteIconPaddingDp = defaults.satelliteIconPaddingDp,
        )
        IslandSettingsCategory.CAMERA_HOLE -> copy(
            holeEnabled = defaults.holeEnabled,
            holeCenterXDp = defaults.holeCenterXDp,
            holeCenterYDp = defaults.holeCenterYDp,
            holeRadiusDp = defaults.holeRadiusDp,
            holeMode = defaults.holeMode,
            holeRatioPercent = defaults.holeRatioPercent,
            holeSide = defaults.holeSide,
            showHoleGuide = defaults.showHoleGuide,
        )
        IslandSettingsCategory.NOTIFICATIONS -> copy(
            notificationDurationSec = defaults.notificationDurationSec,
            chargingDurationSec = defaults.chargingDurationSec,
            batteryAlertDurationSec = defaults.batteryAlertDurationSec,
            batteryLowThresholdPercent = defaults.batteryLowThresholdPercent,
            redactOnLockscreen = defaults.redactOnLockscreen,
            autoExpandCalls = defaults.autoExpandCalls,
            alertIconInBubble = defaults.alertIconInBubble,
            notificationShowTitle = defaults.notificationShowTitle,
            showSilentNotifications = defaults.showSilentNotifications,
            disabledPackages = defaults.disabledPackages,
        )
        IslandSettingsCategory.SOURCES -> copy(
            mediaEnabled = defaults.mediaEnabled,
            notificationsEnabled = defaults.notificationsEnabled,
            callsEnabled = defaults.callsEnabled,
            chargingEnabled = defaults.chargingEnabled,
            batteryLowEnabled = defaults.batteryLowEnabled,
            powerSaveEnabled = defaults.powerSaveEnabled,
            downloadsEnabled = defaults.downloadsEnabled,
            navigationEnabled = defaults.navigationEnabled,
        )
        IslandSettingsCategory.BEHAVIOUR -> copy(
            idleBehaviour = defaults.idleBehaviour,
            idleContractDelaySec = defaults.idleContractDelaySec,
            tapAction = defaults.tapAction,
            longPressAction = defaults.longPressAction,
            hapticsEnabled = defaults.hapticsEnabled,
            hideInFullscreen = defaults.hideInFullscreen,
            hideInLandscape = defaults.hideInLandscape,
            lockPortrait = defaults.lockPortrait,
        )
        IslandSettingsCategory.MOTION -> copy(
            motionEnabled = defaults.motionEnabled,
            highQualityMotion = defaults.highQualityMotion,
            ambientAnimationsEnabled = defaults.ambientAnimationsEnabled,
            ambientFrameRateHz = defaults.ambientFrameRateHz,
            motionScalePercent = defaults.motionScalePercent,
        )
        IslandSettingsCategory.ADVANCED -> copy(verboseLogging = defaults.verboseLogging)
    }
}
