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
import com.zenith.nothingisland.data.IslandSettings
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
            "120 Hz morphs",
            "CMF Phone 2 Pro target: request the panel's high rate for expand/retract, then drop to 0 at rest.",
            settings.highQualityMotion,
        ) { onSettingsChange { s -> s.copy(highQualityMotion = it) } }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider("Ambient loop rate", settings.ambientFrameRateHz, IslandSettings.AMBIENT_FRAME_RATE_RANGE, unit = "Hz") {
                onSettingsChange { s -> s.copy(ambientFrameRateHz = it) }
            }
            CalibrationSlider("Motion speed", settings.motionScalePercent, IslandSettings.MOTION_SCALE_RANGE, unit = "%") {
                onSettingsChange { s -> s.copy(motionScalePercent = it) }
            }
            Text(
                "Ambient loops (playing bars, indeterminate progress) can run below the morph rate to save GPU time. Morphs stay interruptible and velocity-preserving.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
internal fun ExpandedAppearancePanel(settings: IslandSettings, onSettingsChange: SettingsChange) {
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
            CalibrationSlider("Island text scale", settings.islandTextScalePercent, IslandSettings.ISLAND_TEXT_SCALE_RANGE, unit = "%") {
                onSettingsChange { s -> s.copy(islandTextScalePercent = it) }
            }
        }
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
private fun AccentColorRow(label: String, argb: Int, defaultArgb: Int, onChange: (Int) -> Unit) {
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
