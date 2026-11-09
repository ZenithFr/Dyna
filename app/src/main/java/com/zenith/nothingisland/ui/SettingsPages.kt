package com.zenith.nothingisland.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zenith.nothingisland.BuildConfig
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.data.IslandSettingsCategory
import com.zenith.nothingisland.data.resetCategory
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.ui.theme.NothingBlack
import com.zenith.nothingisland.ui.theme.NothingHairline
import com.zenith.nothingisland.ui.theme.NothingMuted
import com.zenith.nothingisland.ui.theme.NothingRed
import com.zenith.nothingisland.ui.theme.NothingSurface
import com.zenith.nothingisland.ui.theme.NothingWhite

/**
 * The app is a hub plus one page per [IslandSettingsCategory]. Categories are the same enum the
 * data layer uses for per-category resets, so a page can never drift from its reset scope.
 *
 * Everything is plain Compose: no navigation dependency, one back level, and the system back
 * button returns to the hub (handled by the host).
 */
@Composable
internal fun SettingsHub(
    serviceEnabled: Boolean,
    islandRunning: Boolean,
    notificationListenerEnabled: Boolean,
    notificationListenerConnected: Boolean,
    timer: IslandContent.Timer?,
    timerPresetMinutes: Int,
    onTimerPresetChange: (Int) -> Unit,
    onTimerStart: () -> Unit,
    onTimerToggle: () -> Unit,
    onTimerStop: () -> Unit,
    onOpenPage: (IslandSettingsCategory) -> Unit,
) {
    Header()

    Spacer(Modifier.height(24.dp))
    ServiceStatusCard(enabled = serviceEnabled, running = islandRunning)

    if (!serviceEnabled || !notificationListenerEnabled || !notificationListenerConnected) {
        Spacer(Modifier.height(12.dp))
        NavigationRow(
            title = "Finish setup",
            summary = if (!serviceEnabled) {
                "Accessibility access is required before the island can draw."
            } else if (!notificationListenerEnabled) {
                "Notification access unlocks messages, calls and media."
            } else {
                "Notification access is granted; waiting for the listener to connect."
            },
            highlighted = true,
        ) { onOpenPage(IslandSettingsCategory.SETUP) }
    }

    Spacer(Modifier.height(32.dp))
    SectionLabel("Timer")
    Spacer(Modifier.height(10.dp))
    TimerPanel(
        timer = timer,
        selectedMinutes = timerPresetMinutes,
        onSelectMinutes = onTimerPresetChange,
        onStart = onTimerStart,
        onToggle = onTimerToggle,
        onStop = onTimerStop,
    )

    Spacer(Modifier.height(32.dp))
    SectionLabel("Settings")
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, NothingHairline, RoundedCornerShape(28.dp))
            .background(NothingSurface),
    ) {
        IslandSettingsCategory.values().forEachIndexed { index, category ->
            if (index > 0) HorizontalDivider(color = NothingHairline, thickness = 1.dp)
            CategoryRow(category) { onOpenPage(category) }
        }
    }

    Spacer(Modifier.height(28.dp))
    Text(
        text = "v${BuildConfig.VERSION_NAME}  ·  MIT  ·  runs entirely on device",
        style = MaterialTheme.typography.labelSmall,
        color = NothingMuted,
    )
    Spacer(Modifier.height(24.dp))
}

/** One settings page: header, category panels, then that category's reset. */
@Composable
internal fun SettingsPage(
    category: IslandSettingsCategory,
    settings: IslandSettings,
    serviceEnabled: Boolean,
    islandRunning: Boolean,
    notificationListenerEnabled: Boolean,
    batteryOptimizationIgnored: Boolean,
    overlayGranted: Boolean,
    timer: IslandContent.Timer?,
    timerPresetMinutes: Int,
    onTimerPresetChange: (Int) -> Unit,
    onTimerStart: () -> Unit,
    onTimerToggle: () -> Unit,
    onTimerStop: () -> Unit,
    onDetectHole: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRefreshStatus: () -> Unit,
    onShowTestAlert: () -> Unit,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(title = category.title, summary = category.summary, onBack = onBack)

    when (category) {
        IslandSettingsCategory.SETUP -> {
            PageSection("Permissions")
            SetupPanel(
                serviceEnabled = serviceEnabled,
                notificationListenerEnabled = notificationListenerEnabled,
                notificationListenerConnected = notificationListenerConnected,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
                overlayGranted = overlayGranted,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                onRequestIgnoreBatteryOptimizations = onRequestIgnoreBatteryOptimizations,
                onOpenOverlaySettings = onOpenOverlaySettings,
            )
            PageSection("Diagnostics")
            DiagnosticsPanel(
                serviceEnabled = serviceEnabled,
                islandRunning = islandRunning,
                notificationListenerEnabled = notificationListenerEnabled,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
                overlayGranted = overlayGranted,
                onRefreshStatus = onRefreshStatus,
                onShowTestAlert = onShowTestAlert,
            )
        }

        IslandSettingsCategory.APPEARANCE -> {
            PageSection("Shell")
            ShellAppearancePanel(settings, onSettingsChange)
            PageSection("Shape & outline")
            StrokePanel(settings, onSettingsChange)
            PageSection("Typography")
            TextAppearancePanel(settings, onSettingsChange)
            PageSection("Source accents")
            AccentColorsPanel(settings, onSettingsChange)
        }

        IslandSettingsCategory.LAYOUT -> {
            PageSection("Compact pill")
            CalibrationPanel(settings, onSettingsChange)
            CompactLayoutPanel(settings, onSettingsChange)
            PageSection("Expanded card")
            ExpandedLayoutPanel(settings, onSettingsChange)
            PageSection("Detached bubble")
            DetachedIconPanel(settings, onSettingsChange)
        }

        IslandSettingsCategory.CAMERA_HOLE -> {
            PageSection("Lens calibration")
            CameraHolePanel(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onDetectHole = onDetectHole,
            )
        }

        IslandSettingsCategory.NOTIFICATIONS -> {
            PageSection("Alert content")
            NotificationContentPanel(settings, onSettingsChange)
            PageSection("Holds & privacy")
            AlertsPrivacyPanel(settings, onSettingsChange)
            PageSection("App integrations")
            IntegrationsPanel(settings, onSettingsChange)
        }

        IslandSettingsCategory.SOURCES -> {
            PageSection("What the island can show")
            SourcesPanel(settings, onSettingsChange)
            PageSection("Countdown")
            TimerPanel(
                timer = timer,
                selectedMinutes = timerPresetMinutes,
                onSelectMinutes = onTimerPresetChange,
                onStart = onTimerStart,
                onToggle = onTimerToggle,
                onStop = onTimerStop,
            )
        }

        IslandSettingsCategory.BEHAVIOUR -> {
            PageSection("Idle")
            IdlePanel(settings, onSettingsChange)
            PageSection("Gestures")
            InteractionPanel(settings, onSettingsChange)
            PageSection("Display")
            DisplayBehaviorPanel(settings, onSettingsChange)
        }

        IslandSettingsCategory.MOTION -> {
            PageSection("Animation")
            MotionPerformancePanel(settings, onSettingsChange)
            PageSection("Battery budget")
            BatteryNotesPanel()
        }

        IslandSettingsCategory.ADVANCED -> {
            PageSection("Diagnostics")
            PanelCard {
                ToggleRow(
                    "Verbose logging",
                    "Log overlay transitions (IslandOverlay) and notification accept/drop reasons (IslandNotifications). Listener binds are always logged at INFO. No titles, bodies, custom category text or notification keys are logged. Verbose is off by default.",
                    settings.verboseLogging,
                ) { onSettingsChange { s -> s.copy(verboseLogging = it) } }
                HorizontalDivider(color = NothingHairline, thickness = 1.dp)
                DiagnosticRow(
                    "Notification listener",
                    if (notificationListenerConnected) "CONNECTED" else if (notificationListenerEnabled) "WAITING" else "OFF",
                )
                ActionRow("Re-check status", "Refresh the grant and live listener connection") {
                    onRefreshStatus()
                }
            }
            PageSection("Restore defaults")
            PanelCard {
                IslandSettingsCategory.values()
                    .filter { it != IslandSettingsCategory.SETUP && it != IslandSettingsCategory.ADVANCED }
                    .forEachIndexed { index, target ->
                        if (index > 0) HorizontalDivider(color = NothingHairline, thickness = 1.dp)
                        ActionRow(title = target.title, summary = "Reset only this category") {
                            onSettingsChange { it.resetCategory(target) }
                        }
                    }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { onSettingsChange { IslandSettings() } },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, NothingRed),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
            ) {
                Text("RESET EVERYTHING", style = MaterialTheme.typography.labelMedium)
            }
            PageSection("About")
            AboutPanel()
        }
    }

    if (category != IslandSettingsCategory.SETUP && category != IslandSettingsCategory.ADVANCED) {
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { onSettingsChange { it.resetCategory(category) } },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            shape = CircleShape,
            border = BorderStroke(1.dp, NothingHairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
        ) {
            Text("RESTORE ${category.title.uppercase()} DEFAULTS", style = MaterialTheme.typography.labelMedium)
        }
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun PageHeader(title: String, summary: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onBack)
            .semantics { contentDescription = "Back to settings"; role = Role.Button }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", style = MaterialTheme.typography.headlineSmall, color = NothingWhite)
        Spacer(Modifier.width(10.dp))
        Text("SETTINGS", style = MaterialTheme.typography.labelSmall, color = NothingMuted)
    }
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, color = NothingWhite)
    Spacer(Modifier.height(6.dp))
    Text(summary, style = MaterialTheme.typography.bodyMedium, color = NothingMuted)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PageSection(title: String) {
    Spacer(Modifier.height(24.dp))
    SectionLabel(title)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun CategoryRow(category: IslandSettingsCategory, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(category.title, style = MaterialTheme.typography.titleSmall, color = NothingWhite)
            Spacer(Modifier.height(3.dp))
            Text(category.summary, style = MaterialTheme.typography.bodySmall, color = NothingMuted)
        }
        Spacer(Modifier.width(12.dp))
        Text("›", style = MaterialTheme.typography.titleMedium, color = NothingMuted)
    }
}

@Composable
private fun NavigationRow(
    title: String,
    summary: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, if (highlighted) NothingRed else NothingHairline, shape)
            .background(NothingSurface)
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = NothingWhite)
            Spacer(Modifier.height(3.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = NothingMuted)
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = NothingMuted)
    }
}

@Composable
internal fun ActionRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = NothingWhite)
            Spacer(Modifier.height(2.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = NothingMuted)
        }
    }
}

@Composable
internal fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, NothingHairline, shape).background(NothingSurface),
        content = content,
    )
}

@Composable
private fun DiagnosticsPanel(
    serviceEnabled: Boolean,
    islandRunning: Boolean,
    notificationListenerEnabled: Boolean,
    notificationListenerConnected: Boolean,
    batteryOptimizationIgnored: Boolean,
    overlayGranted: Boolean,
    onRefreshStatus: () -> Unit,
    onShowTestAlert: () -> Unit,
) {
    PanelCard {
        DiagnosticRow("Accessibility granted", if (serviceEnabled) "YES" else "NO")
        DiagnosticRow("Overlay service", if (islandRunning) "RUNNING" else "STOPPED")
        DiagnosticRow("Notification access", if (notificationListenerEnabled) "YES" else "NO")
        DiagnosticRow(
            "Notification listener",
            if (notificationListenerConnected) "CONNECTED" else if (notificationListenerEnabled) "WAITING" else "OFF",
        )
        DiagnosticRow("Battery optimisation", if (batteryOptimizationIgnored) "EXEMPT" else "OPTIMIZED")
        DiagnosticRow("Overlay permission", if (overlayGranted) "GRANTED" else "NOT NEEDED")
        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        ActionRow("Re-check now", "Status is read on resume; use this after changing a permission") {
            onRefreshStatus()
        }
        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        ActionRow(
            "Show test alert",
            "Preview the pill and app-icon bubble. This checks the overlay only, not notification access.",
        ) { onShowTestAlert() }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = NothingMuted,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.labelMedium, color = NothingWhite)
    }
}

/** Honest, source-level power notes: what is free, what costs, and how to measure on the device. */
@Composable
private fun BatteryNotesPanel() {
    PanelCard {
        Text(
            "• Idle: the shell curls into the lens and the window is released — no surface, no frames.\n" +
                "• Screen off / doze: every window detaches, media and battery observers stop, and a running timer keeps one wake at its end time.\n" +
                "• Morphs request 120 Hz only while they move, then drop the hint.\n" +
                "• Ambient loops are off by default; enabling them is the main optional GPU cost.\n" +
                "• Pure black shell on AMOLED keeps those pixels off.\n\n" +
                "If the phone still runs warm, turn Animations off and leave ambient loops disabled, then compare:\n" +
                "adb shell dumpsys batterystats com.zenith.nothingisland",
            style = MaterialTheme.typography.bodySmall,
            color = NothingMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun AboutPanel() {
    PanelCard {
        DiagnosticRow("Version", BuildConfig.VERSION_NAME)
        DiagnosticRow("Build", if (BuildConfig.DEBUG) "DEBUG" else "RELEASE")
        DiagnosticRow("Package", BuildConfig.APPLICATION_ID)
        Text(
            "Accessibility overlay only: no root, no Shizuku, no network, no telemetry. " +
                "The service never reads window content (canRetrieveWindowContent=false).",
            style = MaterialTheme.typography.bodySmall,
            color = NothingMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        )
    }
}
