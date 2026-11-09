package com.zenith.nothingisland.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zenith.nothingisland.data.IslandIdleBehaviour
import com.zenith.nothingisland.data.IslandLongPressAction
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.data.IslandTapAction
import com.zenith.nothingisland.service.notification.PopularIntegrations
import com.zenith.nothingisland.ui.theme.NothingHairline
import com.zenith.nothingisland.ui.theme.NothingMuted
import com.zenith.nothingisland.ui.theme.NothingRed
import com.zenith.nothingisland.ui.theme.NothingSurface
import com.zenith.nothingisland.ui.theme.NothingWhite

private typealias SettingsChange = ((IslandSettings) -> IslandSettings) -> Unit

@Composable
internal fun SourcesPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        ToggleRow("Media", "Now Playing from active media sessions.", settings.mediaEnabled) {
            onSettingsChange { s -> s.copy(mediaEnabled = it) }
        }
        ToggleRow("Notifications", "Messages and alerts from allowed apps.", settings.notificationsEnabled) {
            onSettingsChange { s -> s.copy(notificationsEnabled = it) }
        }
        ToggleRow("Incoming calls", "CallStyle answer/decline controls.", settings.callsEnabled) {
            onSettingsChange { s -> s.copy(callsEnabled = it) }
        }
        ToggleRow("Charging", "Plug-in and full-charge confirmations.", settings.chargingEnabled) {
            onSettingsChange { s -> s.copy(chargingEnabled = it) }
        }
        ToggleRow("Battery low", "Alert when the battery crosses the low threshold.", settings.batteryLowEnabled) {
            onSettingsChange { s -> s.copy(batteryLowEnabled = it) }
        }
        ToggleRow("Power saving mode", "Alert when system power saver changes.", settings.powerSaveEnabled) {
            onSettingsChange { s -> s.copy(powerSaveEnabled = it) }
        }
        ToggleRow("Downloads & transfers", "Progress notifications, installs and Live Update-style progress.", settings.downloadsEnabled) {
            onSettingsChange { s -> s.copy(downloadsEnabled = it) }
        }
        ToggleRow("Maps & navigation", "Turn-by-turn status from maps notifications.", settings.navigationEnabled) {
            onSettingsChange { s -> s.copy(navigationEnabled = it) }
        }
    }
}

@Composable
internal fun AlertsPrivacyPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider("Notification hold", settings.notificationDurationSec, IslandSettings.ALERT_DURATION_RANGE, unit = "s") {
                onSettingsChange { s -> s.copy(notificationDurationSec = it) }
            }
            CalibrationSlider("Charging hold", settings.chargingDurationSec, IslandSettings.ALERT_DURATION_RANGE, unit = "s") {
                onSettingsChange { s -> s.copy(chargingDurationSec = it) }
            }
            CalibrationSlider("Battery alert hold", settings.batteryAlertDurationSec, IslandSettings.ALERT_DURATION_RANGE, unit = "s") {
                onSettingsChange { s -> s.copy(batteryAlertDurationSec = it) }
            }
            CalibrationSlider("Battery low threshold", settings.batteryLowThresholdPercent, IslandSettings.BATTERY_LOW_THRESHOLD_RANGE, unit = "%") {
                onSettingsChange { s -> s.copy(batteryLowThresholdPercent = it) }
            }
        }
        ToggleRow("Redact on lockscreen", "Hide private message/caller content until unlocked.", settings.redactOnLockscreen) {
            onSettingsChange { s -> s.copy(redactOnLockscreen = it) }
        }
        ToggleRow("Auto-expand calls", "Incoming calls open directly to answer/decline.", settings.autoExpandCalls) {
            onSettingsChange { s -> s.copy(autoExpandCalls = it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IntegrationsPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        Text(
            "These apps surface through Android's public notification and media contracts. Turn one off to block its package without disabling the whole source.",
            style = MaterialTheme.typography.bodySmall,
            color = NothingMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PopularIntegrations.all.forEach { integration ->
                val enabled = integration.packageName !in settings.disabledPackages
                FilterChip(
                    selected = enabled,
                    onClick = {
                        onSettingsChange { s ->
                            val next = s.disabledPackages.toMutableSet()
                            if (enabled) next += integration.packageName else next -= integration.packageName
                            s.copy(disabledPackages = next)
                        }
                    },
                    label = { Text(integration.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun MotionPerformancePanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        ToggleRow(
            "Animations",
            "Master switch. Off snaps every morph instantly: the cheapest possible island and the right choice if the phone still runs warm.",
            settings.motionEnabled,
        ) { onSettingsChange { s -> s.copy(motionEnabled = it) } }
        ToggleRow(
            "120 Hz morphs",
            "CMF Phone 2 Pro target: request the panel's high rate for expand/retract, then drop to 0 at rest.",
            settings.highQualityMotion,
        ) { onSettingsChange { s -> s.copy(highQualityMotion = it) } }
        ToggleRow(
            "Ambient animations",
            "Playing bars and indeterminate progress loop only while the island is visible. Off by default to save battery; morphs stay smooth.",
            settings.ambientAnimationsEnabled,
        ) { onSettingsChange { s -> s.copy(ambientAnimationsEnabled = it) } }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider(
                "Ambient loop rate",
                settings.ambientFrameRateHz,
                IslandSettings.AMBIENT_FRAME_RATE_RANGE,
                enabled = settings.ambientAnimationsEnabled,
                unit = "Hz",
            ) {
                onSettingsChange { s -> s.copy(ambientFrameRateHz = it) }
            }
            CalibrationSlider("Motion speed", settings.motionScalePercent, IslandSettings.MOTION_SCALE_RANGE, unit = "%") {
                onSettingsChange { s -> s.copy(motionScalePercent = it) }
            }
            Text(
                "When enabled, ambient loops run at this rate instead of the morph rate. The island still detaches when idle, hidden, or the screen is off.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
internal fun ExpandedLayoutPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider("Expanded max width", settings.expandedMaxWidthDp, IslandSettings.EXPANDED_MAX_WIDTH_RANGE) {
                onSettingsChange { s -> s.copy(expandedMaxWidthDp = it) }
            }
            CalibrationSlider("Expanded corner", settings.expandedCornerRadiusDp, IslandSettings.EXPANDED_CORNER_RADIUS_RANGE) {
                onSettingsChange { s -> s.copy(expandedCornerRadiusDp = it) }
            }
            CalibrationSlider("Content margin", settings.contentMarginDp, IslandSettings.CONTENT_MARGIN_RANGE) {
                onSettingsChange { s -> s.copy(contentMarginDp = it) }
            }
            CalibrationSlider("Lens clearance", settings.sensorClearanceDp, IslandSettings.SENSOR_CLEARANCE_RANGE) {
                onSettingsChange { s -> s.copy(sensorClearanceDp = it) }
            }
        }
    }
}

@Composable
internal fun AccentColorsPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        AccentColorRow("Timer", settings.timerColorArgb, IslandSettings.DEFAULT_TIMER_COLOR_ARGB) { onSettingsChange { s -> s.copy(timerColorArgb = it) } }
        AccentColorRow("Charging", settings.chargingColorArgb, IslandSettings.DEFAULT_CHARGING_COLOR_ARGB) { onSettingsChange { s -> s.copy(chargingColorArgb = it) } }
        AccentColorRow("Battery low", settings.batteryLowColorArgb, IslandSettings.DEFAULT_BATTERY_LOW_COLOR_ARGB) { onSettingsChange { s -> s.copy(batteryLowColorArgb = it) } }
        AccentColorRow("Power saver", settings.powerSaveColorArgb, IslandSettings.DEFAULT_POWER_SAVE_COLOR_ARGB) { onSettingsChange { s -> s.copy(powerSaveColorArgb = it) } }
        AccentColorRow("Call answer", settings.callAnswerColorArgb, IslandSettings.DEFAULT_CALL_ANSWER_COLOR_ARGB) { onSettingsChange { s -> s.copy(callAnswerColorArgb = it) } }
        AccentColorRow("Call decline", settings.callDeclineColorArgb, IslandSettings.DEFAULT_CALL_DECLINE_COLOR_ARGB) { onSettingsChange { s -> s.copy(callDeclineColorArgb = it) } }
        AccentColorRow("Downloads", settings.downloadColorArgb, IslandSettings.DEFAULT_DOWNLOAD_COLOR_ARGB) { onSettingsChange { s -> s.copy(downloadColorArgb = it) } }
        AccentColorRow("Navigation", settings.navigationColorArgb, IslandSettings.DEFAULT_NAVIGATION_COLOR_ARGB) { onSettingsChange { s -> s.copy(navigationColorArgb = it) } }
    }
}

@Composable
private fun PanelColumn(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, NothingHairline, shape).background(NothingSurface),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccentColorRow(label: String, argb: Int, defaultArgb: Int, onChange: (Int) -> Unit) {
    var draft by remember(argb) { mutableStateOf("%06X".format(argb and 0xFFFFFF)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = NothingMuted, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = draft,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(6).uppercase()
                    draft = filtered
                    if (filtered.length == 6) {
                        runCatching { android.graphics.Color.parseColor("#$filtered") }
                            .onSuccess { if (it != argb) onChange(it) }
                    }
                },
                modifier = Modifier.width(112.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NothingRed, unfocusedBorderColor = NothingHairline,
                    focusedTextColor = NothingWhite, unfocusedTextColor = NothingWhite,
                    cursorColor = NothingRed, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(defaultArgb, 0xFFD71921.toInt(), 0xFF30D158.toInt(), 0xFFFF9F0A.toInt(), 0xFF64D2FF.toInt(), 0xFFFFFFFF.toInt()).forEach { color ->
                val selected = (argb and 0xFFFFFF) == (color and 0xFFFFFF)
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable { onChange(color) }
                        .semantics { contentDescription = "$label colour ${"%06X".format(color and 0xFFFFFF)}"; this.selected = selected }
                        .border(if (selected) 2.dp else 1.dp, if (selected) NothingWhite else NothingHairline, CircleShape)
                        .padding(6.dp).clip(CircleShape).background(Color(color)),
                )
            }
        }
    }
}

@Composable
internal fun DisplayBehaviorPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        ToggleRow(
            "Hide in landscape",
            "Remove the island and its touch window whenever the display is wider than it is tall.",
            settings.hideInLandscape,
        ) { onSettingsChange { s -> s.copy(hideInLandscape = it) } }
        ToggleRow(
            "Hide in fullscreen",
            "Hide while the status bar is hidden (immersive video/games). Fails open until the inset probe has seen a visible status bar. Reads only inset visibility, not screen content.",
            settings.hideInFullscreen,
        ) { onSettingsChange { s -> s.copy(hideInFullscreen = it) } }
        ToggleRow(
            "Portrait lock",
            "Only used when landscape hiding is off: keep the portrait size and top-centre anchor instead of re-anchoring to the rotated cutout.",
            settings.lockPortrait,
        ) { onSettingsChange { s -> s.copy(lockPortrait = it) } }
    }
}

@Composable
internal fun CompactLayoutPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider("Compact icon size", settings.compactIconSizeDp, IslandSettings.COMPACT_ICON_SIZE_RANGE) {
                onSettingsChange { s -> s.copy(compactIconSizeDp = it) }
            }
            CalibrationSlider("Shell edge padding", settings.compactEdgePaddingDp, IslandSettings.COMPACT_EDGE_PADDING_RANGE) {
                onSettingsChange { s -> s.copy(compactEdgePaddingDp = it) }
            }
            Text(
                "Icons fit inside the safe area at small heights. The lens clearance below still applies, so content never crosses the camera hole.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
            CalibrationSlider("Lens clearance", settings.sensorClearanceDp, IslandSettings.SENSOR_CLEARANCE_RANGE) {
                onSettingsChange { s -> s.copy(sensorClearanceDp = it) }
            }
        }
    }
}

@Composable
internal fun DetachedIconPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        ToggleRow(
            "Detached app icon",
            "Music, timers, maps and downloads split the owning app's icon into a circle beside the compact island. Expand/retract uses a mitosis-style detach and rejoin.",
            settings.detachedAppIcon,
        ) { onSettingsChange { s -> s.copy(detachedAppIcon = it) } }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider("Circle diameter", settings.satelliteSizeDp, IslandSettings.SATELLITE_SIZE_RANGE) {
                onSettingsChange { s -> s.copy(satelliteSizeDp = it) }
            }
            CalibrationSlider("Circle gap", settings.satelliteGapDp, IslandSettings.SATELLITE_GAP_RANGE) {
                onSettingsChange { s -> s.copy(satelliteGapDp = it) }
            }
            CalibrationSlider("Icon inset", settings.satelliteIconPaddingDp, IslandSettings.SATELLITE_ICON_PADDING_RANGE) {
                onSettingsChange { s -> s.copy(satelliteIconPaddingDp = it) }
            }
        }
    }
}


/** Shell colour, opacity and icon treatment. Shape/stroke live in [StrokePanel]. */
@Composable
internal fun ShellAppearancePanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        AccentColorRow("Shell", settings.shellColorArgb, IslandSettings.DEFAULT_SHELL_COLOR_ARGB) {
            onSettingsChange { s -> s.copy(shellColorArgb = it) }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider(
                "Shell opacity",
                settings.shellOpacityPercent,
                IslandSettings.SHELL_OPACITY_RANGE,
                unit = "%",
            ) { onSettingsChange { s -> s.copy(shellOpacityPercent = it) } }
            Text(
                "Pure black is free on this AMOLED panel: pixels stay off. Lighter or translucent shells cost a little more power.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
        ToggleRow(
            "Monochrome icons",
            "Desaturate app icons inside the island for the dot-matrix look.",
            settings.monochromeIcons,
        ) { onSettingsChange { s -> s.copy(monochromeIcons = it) } }
    }
}

/** Island typography: colours and scale, independent of the app's Material theme. */
@Composable
internal fun TextAppearancePanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider(
                "Island text scale",
                settings.islandTextScalePercent,
                IslandSettings.ISLAND_TEXT_SCALE_RANGE,
                unit = "%",
            ) { onSettingsChange { s -> s.copy(islandTextScalePercent = it) } }
        }
        AccentColorRow("Primary text", settings.textColorArgb, IslandSettings.DEFAULT_TEXT_COLOR_ARGB) {
            onSettingsChange { s -> s.copy(textColorArgb = it) }
        }
        AccentColorRow("Secondary text", settings.secondaryTextColorArgb, IslandSettings.DEFAULT_SECONDARY_TEXT_COLOR_ARGB) {
            onSettingsChange { s -> s.copy(secondaryTextColorArgb = it) }
        }
    }
}

/** What the island does with nothing to show — the knob behind "it keeps disappearing". */
@Composable
internal fun IdlePanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        ChoiceRow(
            title = "When idle",
            description = "Hide curls the shell into the lens and releases the window (lowest battery). " +
                "Keep pill leaves an empty shell on screen; it is static and still requests no frames.",
            options = IslandIdleBehaviour.values().toList(),
            selected = settings.idleBehaviour,
            label = { if (it == IslandIdleBehaviour.HIDDEN) "Hide in lens" else "Keep pill" },
        ) { value -> onSettingsChange { s -> s.copy(idleBehaviour = value) } }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider(
                "Linger before hiding",
                settings.idleContractDelaySec,
                IslandSettings.IDLE_CONTRACT_DELAY_RANGE,
                enabled = settings.idleBehaviour == IslandIdleBehaviour.HIDDEN,
                unit = "s",
            ) { onSettingsChange { s -> s.copy(idleContractDelaySec = it) } }
            Text(
                "The countdown starts once, when the last activity ends. Opening or closing apps no longer restarts it.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
    }
}

/** Tap, long press and haptics. */
@Composable
internal fun InteractionPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        ChoiceRow(
            title = "Tap shell",
            description = "Expand keeps the documented Android behaviour; Open app jumps straight to the owning app.",
            options = IslandTapAction.values().toList(),
            selected = settings.tapAction,
            label = {
                when (it) {
                    IslandTapAction.EXPAND -> "Expand"
                    IslandTapAction.OPEN_APP -> "Open app"
                    IslandTapAction.NONE -> "Nothing"
                }
            },
        ) { value -> onSettingsChange { s -> s.copy(tapAction = value) } }
        ChoiceRow(
            title = "Long press shell",
            description = "Apple's second affordance: hold the island to open the app behind the activity.",
            options = IslandLongPressAction.values().toList(),
            selected = settings.longPressAction,
            label = {
                when (it) {
                    IslandLongPressAction.OPEN_APP -> "Open app"
                    IslandLongPressAction.EXPAND -> "Expand"
                    IslandLongPressAction.NONE -> "Nothing"
                }
            },
        ) { value -> onSettingsChange { s -> s.copy(longPressAction = value) } }
        ToggleRow(
            "Haptic feedback",
            "Short tick when an island control is activated. The system haptic setting still wins.",
            settings.hapticsEnabled,
        ) { onSettingsChange { s -> s.copy(hapticsEnabled = it) } }
    }
}

/** Where an alert's app icon lives, and what the compact shell shows. */
@Composable
internal fun NotificationContentPanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
    PanelColumn {
        ToggleRow(
            "App icon in detached bubble",
            "Messages and calls move the app icon into the circle beside the island so the pill shows text only. The bubble retracts as soon as the alert hold expires.",
            settings.alertIconInBubble,
        ) { onSettingsChange { s -> s.copy(alertIconInBubble = it) } }
        ToggleRow(
            "Show title in pill",
            "Off leaves the compact shell empty while the bubble carries the app icon.",
            settings.notificationShowTitle,
        ) { onSettingsChange { s -> s.copy(notificationShowTitle = it) } }
    }
}

/**
 * Single-choice row backed by chips. Kept generic so every enum knob (idle behaviour, tap actions,
 * hole mode) reads and behaves the same way, with 48 dp targets and selected semantics.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = NothingMuted)
        Spacer(Modifier.height(6.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = NothingMuted)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).semantics { this.selected = isSelected },
                )
            }
        }
    }
}
