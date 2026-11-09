package com.zenith.nothingisland.ui

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenith.nothingisland.data.IslandHoleMode
import com.zenith.nothingisland.data.IslandHoleSide
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.data.IslandSettingsCategory
import com.zenith.nothingisland.data.IslandShape
import com.zenith.nothingisland.data.buildIslandPath
import com.zenith.nothingisland.data.pillLeftDp
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.ui.theme.NothingBlack
import com.zenith.nothingisland.ui.theme.NothingHairline
import com.zenith.nothingisland.ui.theme.NothingMuted
import com.zenith.nothingisland.ui.theme.NothingRed
import com.zenith.nothingisland.ui.theme.NothingSurface
import com.zenith.nothingisland.ui.theme.NothingWhite
import kotlin.math.roundToInt

/**
 * Stateless home screen: the activity owns service status polling, DataStore writes and the timer
 * preset/selection. Timer state itself comes from
 * [com.zenith.nothingisland.service.timer.TimerModule.state].
 *
 * @param onSettingsChange receives a copy-transform that is persisted atomically. The accessibility
 * service collects the same DataStore flow, so every slider move reaches the overlay live.
 */
@Composable
fun HomeScreen(
    settings: IslandSettings,
    serviceEnabled: Boolean,
    islandRunning: Boolean,
    notificationListenerEnabled: Boolean,
    notificationListenerConnected: Boolean,
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
) {
    // The page is stored by name so the open category survives configuration/process death without
    // pulling in a navigation library. One back stack level is all this settings tree needs.
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val category = pageName?.let { name ->
        IslandSettingsCategory.values().firstOrNull { it.name == name }
    }
    BackHandler(enabled = category != null) { pageName = null }

    val scrollState = rememberScrollState()
    LaunchedEffect(category) { scrollState.scrollTo(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NothingBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        if (category == null) {
            SettingsHub(
                serviceEnabled = serviceEnabled,
                islandRunning = islandRunning,
                notificationListenerEnabled = notificationListenerEnabled,
                notificationListenerConnected = notificationListenerConnected,
                timer = timer,
                timerPresetMinutes = timerPresetMinutes,
                onTimerPresetChange = onTimerPresetChange,
                onTimerStart = onTimerStart,
                onTimerToggle = onTimerToggle,
                onTimerStop = onTimerStop,
                onOpenPage = { pageName = it.name },
            )
        } else {
            SettingsPage(
                category = category,
                settings = settings,
                serviceEnabled = serviceEnabled,
                islandRunning = islandRunning,
                notificationListenerEnabled = notificationListenerEnabled,
                notificationListenerConnected = notificationListenerConnected,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
                overlayGranted = overlayGranted,
                timer = timer,
                timerPresetMinutes = timerPresetMinutes,
                onTimerPresetChange = onTimerPresetChange,
                onTimerStart = onTimerStart,
                onTimerToggle = onTimerToggle,
                onTimerStop = onTimerStop,
                onDetectHole = onDetectHole,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                onRequestIgnoreBatteryOptimizations = onRequestIgnoreBatteryOptimizations,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onRefreshStatus = onRefreshStatus,
                onShowTestAlert = onShowTestAlert,
                onSettingsChange = onSettingsChange,
                onBack = { pageName = null },
            )
        }
    }
}

@Composable
internal fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(NothingRed),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "NOTHING OS · OVERLAY",
            style = MaterialTheme.typography.labelSmall,
            color = NothingMuted,
        )
    }
    Spacer(Modifier.height(14.dp))
    Text(
        text = "Nothing\nIsland",
        style = MaterialTheme.typography.displaySmall,
        color = NothingWhite,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = "A black pill at the top of your screen. No root, no ads, no cloud.",
        style = MaterialTheme.typography.bodyMedium,
        color = NothingMuted,
    )
}

@Composable
internal fun ServiceStatusCard(enabled: Boolean, running: Boolean) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NothingSurface)
            .border(1.dp, NothingHairline, shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (running) NothingRed else Color(0xFF2A2A2A))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    running -> "ISLAND ACTIVE"
                    enabled -> "ISLAND STARTING"
                    else -> "ISLAND OFFLINE"
                },
                style = MaterialTheme.typography.labelMedium,
                color = NothingWhite,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = when {
                    running -> "The accessibility overlay is running."
                    enabled -> "Accessibility is enabled; waiting for the overlay service."
                    else -> "Enable the accessibility service to start."
                },
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
            )
        }
        Text(
            text = if (running) "LIVE" else "OFF",
            style = MaterialTheme.typography.labelSmall,
            color = if (running) NothingRed else NothingMuted,
        )
    }
}

@Composable
internal fun SetupPanel(
    serviceEnabled: Boolean,
    notificationListenerEnabled: Boolean,
    notificationListenerConnected: Boolean,
    batteryOptimizationIgnored: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, NothingHairline, shape)
            .background(NothingSurface),
    ) {
        SetupItem(
            title = "Accessibility service",
            description = if (serviceEnabled) {
                "Enabled. The pill is drawn over your apps."
            } else {
                "Required. Draws the overlay; it does not read screen content."
            },
            status = if (serviceEnabled) SetupStatus.Done else SetupStatus.Todo,
            actionLabel = if (serviceEnabled) "SETTINGS" else "ENABLE",
            primary = !serviceEnabled,
            onClick = onOpenAccessibilitySettings,
        )
        SetupDivider()
        SetupItem(
            title = "Notification listener",
            description = when {
                notificationListenerConnected -> "Connected. Notification mirroring and media access are live."
                notificationListenerEnabled -> "Access granted, but Android has not connected the listener. Try toggling notification access off and on."
                else -> "Required for media controls and notification mirroring; also unlocks media access."
            },
            status = if (notificationListenerConnected) SetupStatus.Done else SetupStatus.Todo,
            actionLabel = if (notificationListenerEnabled) "MANAGE" else "ENABLE",
            onClick = onOpenNotificationListenerSettings,
        )
        SetupDivider()
        SetupItem(
            title = "Battery optimisation",
            description = if (batteryOptimizationIgnored) {
                "Exempt. Reliability override enabled; this can increase background battery use."
            } else {
                "Optional. Leave optimized for lowest battery use; exempt only if Nothing OS kills the service."
            },
            status = if (batteryOptimizationIgnored) SetupStatus.Done else SetupStatus.Optional,
            actionLabel = if (batteryOptimizationIgnored) "MANAGE" else "REVIEW",
            onClick = onRequestIgnoreBatteryOptimizations,
        )
        SetupDivider()
        SetupItem(
            title = "Overlay permission",
            description = "Optional. Accessibility overlays do not need it; shown for completeness. " +
                "Currently ${if (overlayGranted) "granted" else "not granted"}.",
            status = SetupStatus.Optional,
            actionLabel = "OPEN",
            onClick = onOpenOverlaySettings,
        )
    }
}

/** Presets offered by the timer card; minutes are converted at start time. */
private val TimerPresetMinutes = listOf(1, 5, 10, 25)

/**
 * Countdown card. All timer state is hoisted: [timer] is the live controller snapshot and the
 * preset selection lives in the activity.
 */
@Composable
internal fun TimerPanel(
    timer: IslandContent.Timer?,
    selectedMinutes: Int,
    onSelectMinutes: (Int) -> Unit,
    onStart: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val active = timer != null && timer.remainingMs > 0L
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, NothingHairline, shape)
            .background(NothingSurface)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "COUNTDOWN",
                style = MaterialTheme.typography.labelSmall,
                color = NothingMuted,
                modifier = Modifier.weight(1f),
            )
            TimerStatus(timer)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = timer?.displayTime ?: "%02d:00".format(selectedMinutes),
            style = MaterialTheme.typography.displaySmall,
            color = if (timer == null) NothingMuted else NothingWhite,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                timer == null ->
                    "Pick a preset, then start. The island counts down while you work."
                timer.remainingMs <= 0L ->
                    "Time's up. The island clears itself in a moment."
                timer.isRunning ->
                    "Running on the island. Tap the pill to expand its controls."
                else ->
                    "Paused. The remaining time is held until you resume."
            },
            style = MaterialTheme.typography.bodySmall,
            color = NothingMuted,
        )

        Spacer(Modifier.height(16.dp))
        if (timer == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerPresetMinutes.forEach { minutes ->
                    TimerPresetChip(
                        minutes = minutes,
                        selected = minutes == selectedMinutes,
                        onClick = { onSelectMinutes(minutes) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                contentPadding = PaddingValues(vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NothingRed,
                    contentColor = NothingWhite,
                ),
            ) {
                ActionLabel("START · $selectedMinutes MIN")
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onToggle,
                    enabled = active,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    contentPadding = PaddingValues(vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NothingRed,
                        contentColor = NothingWhite,
                    ),
                ) {
                    ActionLabel(
                        when {
                            !active -> "DONE"
                            timer.isRunning -> "PAUSE"
                            else -> "RESUME"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, NothingHairline),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
                ) {
                    ActionLabel("STOP")
                }
            }
        }
    }
}

@Composable
private fun TimerStatus(timer: IslandContent.Timer?) {
    val (label, color) = when {
        timer == null -> "READY" to NothingMuted
        timer.remainingMs <= 0L -> "DONE" to NothingRed
        timer.isRunning -> "RUNNING" to NothingRed
        else -> "PAUSED" to NothingWhite
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, 6.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun TimerPresetChip(minutes: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .background(if (selected) NothingRed else Color.Transparent)
            .border(1.dp, if (selected) NothingRed else NothingHairline, CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$minutes minute timer"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${minutes}m",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) NothingWhite else NothingMuted,
        )
    }
}

/**
 * Camera-hole calibration: a top-of-screen preview with a draggable lens circle, the three
 * placement modes and the numeric hole sliders. Everything is hoisted via [onSettingsChange],
 * so the running service follows the drag live.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CameraHolePanel(
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
    onDetectHole: () -> Unit,
) {
    val cardShape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(1.dp, NothingHairline, cardShape)
            .background(NothingSurface),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TOP OF THE PHONE",
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (settings.holeEnabled) "AVOIDING" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (settings.holeEnabled) NothingRed else NothingMuted,
                )
            }
            Spacer(Modifier.height(12.dp))
            HolePreview(settings = settings, onSettingsChange = onSettingsChange)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Drag anywhere to move the hole. Defaults: centre, 26 dp down, 8 dp radius.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
            )
        }

        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                text = "PLACEMENT",
                style = MaterialTheme.typography.labelSmall,
                color = NothingMuted,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HoleModeChip("Ratio", IslandHoleMode.RATIO, settings, onSettingsChange)
                HoleModeChip("One side", IslandHoleMode.ONE_SIDE, settings, onSettingsChange)
                HoleModeChip("Equal split", IslandHoleMode.SYMMETRIC, settings, onSettingsChange)
            }

            if (settings.holeMode == IslandHoleMode.ONE_SIDE) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "HOLE SIDE",
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingMuted,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoleSideChip("Left", IslandHoleSide.LEFT, settings, onSettingsChange)
                    HoleSideChip("Right", IslandHoleSide.RIGHT, settings, onSettingsChange)
                }
            }

            if (settings.holeMode == IslandHoleMode.RATIO) {
                Spacer(Modifier.height(4.dp))
                CalibrationSlider(
                    label = "Small side",
                    value = settings.holeRatioPercent,
                    range = IslandSettings.HOLE_RATIO_UI_RANGE,
                    unit = "%",
                    onValueChange = { value -> onSettingsChange { it.copy(holeRatioPercent = value) } },
                )
                Text(
                    text = "Big side ${100 - settings.holeRatioPercent}%  ·  50% splits equally",
                    style = MaterialTheme.typography.bodySmall,
                    color = NothingMuted,
                )
            }
        }

        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider(
                label = "Hole X",
                value = settings.holeCenterXDp,
                range = IslandSettings.HOLE_X_RANGE,
                signed = true,
                onValueChange = { value -> onSettingsChange { it.copy(holeCenterXDp = value) } },
            )
            CalibrationSlider(
                label = "Hole Y",
                value = settings.holeCenterYDp,
                range = IslandSettings.HOLE_Y_RANGE,
                onValueChange = { value -> onSettingsChange { it.copy(holeCenterYDp = value) } },
            )
            CalibrationSlider(
                label = "Hole radius",
                value = settings.holeRadiusDp,
                range = IslandSettings.HOLE_RADIUS_RANGE,
                onValueChange = { value -> onSettingsChange { it.copy(holeRadiusDp = value) } },
            )
        }

        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        ToggleRow(
            title = "Avoid the camera hole",
            description = "Splits island content around the lens. Off uses the whole width.",
            checked = settings.holeEnabled,
            onCheckedChange = { checked -> onSettingsChange { it.copy(holeEnabled = checked) } },
        )
        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        ToggleRow(
            title = "Show guide on screen",
            description = "Draws the red ring on the real island so you can align it with the lens.",
            checked = settings.showHoleGuide,
            onCheckedChange = { checked -> onSettingsChange { it.copy(showHoleGuide = checked) } },
        )
        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = onDetectHole,
                shape = CircleShape,
                border = BorderStroke(1.dp, NothingHairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
            ) {
                ActionLabel("DETECT CUTOUT")
            }
        }
    }
}

@Composable
private fun HoleModeChip(
    label: String,
    mode: IslandHoleMode,
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = settings.holeMode == mode
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .background(if (selected) NothingRed else Color.Transparent)
            .border(1.dp, if (selected) NothingRed else NothingHairline, CircleShape)
            .clickable { onSettingsChange { it.copy(holeMode = mode) } }
            .semantics {
                contentDescription = "$label hole mode"
                this.selected = selected
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) NothingWhite else NothingMuted,
        )
    }
}

@Composable
private fun HoleSideChip(
    label: String,
    side: IslandHoleSide,
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = settings.holeSide == side
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .background(if (selected) NothingRed else Color.Transparent)
            .border(1.dp, if (selected) NothingRed else NothingHairline, CircleShape)
            .clickable { onSettingsChange { it.copy(holeSide = side) } }
            .semantics {
                contentDescription = "$label hole side"
                this.selected = selected
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) NothingWhite else NothingMuted,
        )
    }
}

/**
 * Top-of-phone preview. Real screen dp are mapped to the card width, so the pill and the hole
 * keep their true proportions; dragging anywhere moves the hole in screen dp.
 */
@Composable
private fun HolePreview(
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    var holeX by remember { mutableFloatStateOf(settings.holeCenterXDp.toFloat()) }
    var holeY by remember { mutableFloatStateOf(settings.holeCenterYDp.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(settings.holeCenterXDp, settings.holeCenterYDp, dragging) {
        if (!dragging) {
            holeX = settings.holeCenterXDp.toFloat()
            holeY = settings.holeCenterYDp.toFloat()
        }
    }

    val previewShape = RoundedCornerShape(20.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(previewShape)
            .background(Color(0xFF050505))
            .border(1.dp, NothingHairline, previewShape),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val scale = (widthPx / screenWidthDp.coerceAtLeast(1)).coerceAtLeast(0.05f)
        val densityValue = density.density
        val pillPath = remember { android.graphics.Path() }
        val labelPaint = remember(densityValue) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 10f * densityValue
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(scale) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        holeX = (holeX + dragAmount.x / scale).coerceIn(
                            IslandSettings.HOLE_X_RANGE.first.toFloat(),
                            IslandSettings.HOLE_X_RANGE.last.toFloat(),
                        )
                        holeY = (holeY + dragAmount.y / scale).coerceIn(
                            IslandSettings.HOLE_Y_RANGE.first.toFloat(),
                            IslandSettings.HOLE_Y_RANGE.last.toFloat(),
                        )
                        onSettingsChange {
                            it.copy(
                                holeCenterXDp = holeX.roundToInt(),
                                holeCenterYDp = holeY.roundToInt(),
                            )
                        }
                    }
                },
        ) {
            val pillW = settings.widthDp * scale
            val pillH = settings.heightDp * scale
            // Use the live drag position, not the persisted settings, so the pill tracks the hole
            // while dragging instead of snapping one frame behind.
            val liveSettings = settings.copy(
                holeCenterXDp = holeX.roundToInt(),
                holeCenterYDp = holeY.roundToInt(),
            )
            val pillX =
                liveSettings.pillLeftDp(screenWidthDp.toFloat(), settings.widthDp.toFloat()) * scale
            val pillY = (if (settings.islandShape == IslandShape.PILL) settings.offsetYDp else 0) * scale
            val corner = settings.cornerRadiusDp * scale

            buildIslandPath(pillPath, settings.islandShape, pillW, pillH, corner)
            // Place the shape where the real overlay sits; without this it renders at (0,0)
            // and looks stuck to the card's left edge.
            pillPath.offset(pillX, pillY)
            val composePill = pillPath.asComposePath()
            drawPath(composePill, NothingBlack)
            if (settings.strokeEnabled) {
                drawPath(
                    composePill,
                    Color(settings.strokeColorArgb),
                    style = Stroke((settings.strokeWidthDp * densityValue).coerceAtLeast(1f)),
                )
            } else {
                drawPath(composePill, Color.White.copy(alpha = 0.16f), style = Stroke(1f))
            }

            val holeCenter = Offset(
                x = (screenWidthDp / 2f + holeX) * scale,
                y = holeY * scale,
            )
            val radius = settings.holeRadiusDp * scale
            val ringColor = if (settings.holeEnabled) NothingRed else NothingMuted
            drawCircle(ringColor.copy(alpha = 0.14f), radius = radius, center = holeCenter)
            drawCircle(ringColor, radius = radius, center = holeCenter, style = Stroke(1.5f * densityValue))
            val arm = radius * 0.85f
            drawLine(
                ringColor,
                Offset(holeCenter.x - arm, holeCenter.y),
                Offset(holeCenter.x + arm, holeCenter.y),
                1f * densityValue,
            )
            drawLine(
                ringColor,
                Offset(holeCenter.x, holeCenter.y - arm),
                Offset(holeCenter.x, holeCenter.y + arm),
                1f * densityValue,
            )

            val labelText = "hole"
            // Keep the label inside the card when the hole sits near the right edge.
            val labelWidth = labelPaint.measureText(labelText)
            val labelX = (holeCenter.x + radius + 4f * densityValue)
                .coerceAtMost(size.width - labelWidth - 2f * densityValue)
                .coerceAtLeast(2f * densityValue)
            // Keep the baseline on the card too: near the bezel it would sit above the top edge.
            val labelY = maxOf(holeCenter.y - radius, 11f * densityValue)
            drawContext.canvas.nativeCanvas.drawText(
                labelText,
                labelX,
                labelY,
                labelPaint,
            )

            drawLine(
                Color.White.copy(alpha = 0.10f),
                Offset(0f, 0.5f),
                Offset(size.width, 0.5f),
                1f,
            )
        }
    }
}

@Composable
internal fun CalibrationPanel(
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, NothingHairline, shape)
            .background(NothingSurface),
    ) {
        IslandPreview(settings)
        HorizontalDivider(color = NothingHairline, thickness = 1.dp)

        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider(
                label = "Horizontal offset",
                value = settings.offsetXDp,
                range = IslandSettings.OFFSET_X_RANGE,
                signed = true,
                onValueChange = { value -> onSettingsChange { it.copy(offsetXDp = value) } },
            )
            CalibrationSlider(
                label = "Vertical offset",
                value = settings.offsetYDp,
                range = IslandSettings.OFFSET_Y_RANGE,
                enabled = settings.islandShape == IslandShape.PILL,
                onValueChange = { value -> onSettingsChange { it.copy(offsetYDp = value) } },
            )
            if (settings.islandShape != IslandShape.PILL) {
                Text(
                    text = "Pinned to the top edge for the notch shape.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NothingMuted,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                )
            }
            CalibrationSlider(
                label = "Width",
                value = settings.widthDp,
                range = IslandSettings.WIDTH_RANGE,
                onValueChange = { value -> onSettingsChange { it.copy(widthDp = value) } },
            )
            Text(
                text = "This is the pill's maximum width; tapping still opens the larger view.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
            CalibrationSlider(
                label = "Height",
                value = settings.heightDp,
                range = IslandSettings.HEIGHT_RANGE,
                onValueChange = { value -> onSettingsChange { it.copy(heightDp = value) } },
            )
            CalibrationSlider(
                label = "Corner radius",
                value = settings.cornerRadiusDp,
                range = IslandSettings.CORNER_RADIUS_RANGE,
                onValueChange = { value -> onSettingsChange { it.copy(cornerRadiusDp = value) } },
            )
        }

        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider(
                label = "Idle contraction delay",
                value = settings.idleContractDelaySec,
                range = IslandSettings.IDLE_CONTRACT_DELAY_RANGE,
                unit = "s",
                onValueChange = { value ->
                    onSettingsChange { it.copy(idleContractDelaySec = value) }
                },
            )
            Text(
                text = "With nothing to show (no notification, music, timer or charging), the " +
                    "island curls into the camera hole after this delay. New activity brings it back.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
internal fun StrokePanel(
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, NothingHairline, shape)
            .background(NothingSurface),
    ) {
        ShapeSelector(settings = settings, onSettingsChange = onSettingsChange)
        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        ToggleRow(
            title = "Outline",
            description = "Draws a stroke around the pill. Off removes it completely.",
            checked = settings.strokeEnabled,
            onCheckedChange = { checked -> onSettingsChange { it.copy(strokeEnabled = checked) } },
        )
        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            CalibrationSlider(
                label = "Stroke width",
                value = settings.strokeWidthDp,
                range = IslandSettings.STROKE_WIDTH_RANGE,
                enabled = settings.strokeEnabled,
                onValueChange = { value -> onSettingsChange { it.copy(strokeWidthDp = value) } },
            )
        }
        HorizontalDivider(color = NothingHairline, thickness = 1.dp)
        StrokeColorRow(settings = settings, onSettingsChange = onSettingsChange)
    }
}

@Composable
private fun ShapeSelector(
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            text = "SHAPE",
            style = MaterialTheme.typography.labelSmall,
            color = NothingMuted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShapeChip("Pill", IslandShape.PILL, settings, onSettingsChange, Modifier.weight(1f))
            ShapeChip("Notch", IslandShape.NOTCH, settings, onSettingsChange, Modifier.weight(1f))
        }
        if (settings.islandShape != IslandShape.PILL) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pinned to the top edge — the vertical offset is ignored.",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
            )
        }
    }
}

@Composable
private fun ShapeChip(
    label: String,
    shape: IslandShape,
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = settings.islandShape == shape
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .background(if (selected) NothingRed else Color.Transparent)
            .border(1.dp, if (selected) NothingRed else NothingHairline, CircleShape)
            .clickable { onSettingsChange { it.copy(islandShape = shape) } }
            .semantics {
                contentDescription = "$label island shape"
                this.selected = selected
            }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) NothingWhite else NothingMuted,
        )
    }
}

/** Presets offered as circular swatches; values are opaque ARGB (the hex field is the free path). */
private data class StrokeSwatchOption(val label: String, val argb: Int)

private val StrokeSwatchOptions = listOf(
    StrokeSwatchOption("Nothing Red", 0xFFD71921.toInt()),
    StrokeSwatchOption("White", 0xFFFFFFFF.toInt()),
    StrokeSwatchOption("Grey", 0xFF888888.toInt()),
    StrokeSwatchOption("Dim", 0xFF3A3A3A.toInt()),
    StrokeSwatchOption("Cyan", 0xFF00E5FF.toInt()),
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StrokeColorRow(
    settings: IslandSettings,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "COLOUR",
                style = MaterialTheme.typography.labelSmall,
                color = NothingMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "#${hexOf(settings.strokeColorArgb)}",
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite,
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StrokeSwatchOptions.forEach { option ->
                StrokeSwatch(
                    label = option.label,
                    color = Color(option.argb),
                    selected = (settings.strokeColorArgb and 0xFFFFFF) == (option.argb and 0xFFFFFF),
                    onClick = { onSettingsChange { it.copy(strokeColorArgb = option.argb) } },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        StrokeHexField(argb = settings.strokeColorArgb, onSettingsChange = onSettingsChange)
    }
}

@Composable
private fun StrokeSwatch(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) NothingWhite else NothingHairline,
                shape = CircleShape,
            )
            .padding(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * RRGGBB entry as an alternative to the swatches. Input is filtered to hex digits, so invalid
 * characters are dropped rather than persisted; the value is only written once six digits parse
 * through [android.graphics.Color.parseColor].
 */
@Composable
private fun StrokeHexField(
    argb: Int,
    onSettingsChange: ((IslandSettings) -> IslandSettings) -> Unit,
) {
    var draft by remember(argb) { mutableStateOf(hexOf(argb)) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "HEX",
                style = MaterialTheme.typography.labelSmall,
                color = NothingMuted,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "RRGGBB · invalid input is ignored",
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { raw ->
                val filtered = raw
                    .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                    .take(6)
                    .uppercase()
                draft = filtered
                if (filtered.length == 6) {
                    runCatching { android.graphics.Color.parseColor("#$filtered") }
                        .onSuccess { parsed ->
                            if (parsed != argb) {
                                onSettingsChange { it.copy(strokeColorArgb = parsed) }
                            }
                        }
                }
            },
            modifier = Modifier.width(120.dp),
            textStyle = MaterialTheme.typography.labelMedium,
            singleLine = true,
            label = {
                Text(text = "HEX", style = MaterialTheme.typography.labelSmall)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NothingRed,
                unfocusedBorderColor = NothingHairline,
                focusedTextColor = NothingWhite,
                unfocusedTextColor = NothingWhite,
                cursorColor = NothingRed,
                focusedLabelColor = NothingMuted,
                unfocusedLabelColor = NothingMuted,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )
    }
}

/** RRGGBB of an ARGB int (alpha is not exposed in the UI). */
private fun hexOf(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

@Composable
private fun IslandPreview(settings: IslandSettings) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .padding(12.dp)
            .clip(shape)
            .background(Color(0xFF050505))
            .border(1.dp, NothingHairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.size(width = settings.widthDp.dp, height = settings.heightDp.dp),
        ) {
            drawIslandShell(settings, size.width, size.height)
        }
        Text(
            text = "PREVIEW",
            style = MaterialTheme.typography.labelSmall,
            color = NothingMuted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        )
    }
}

/** Draws the selected shell outline (pill / notch) at the caller's canvas size. */
private fun DrawScope.drawIslandShell(
    settings: IslandSettings,
    widthPx: Float,
    heightPx: Float,
) {
    val strokeWidthPx = if (settings.strokeEnabled) settings.strokeWidthDp.dp.toPx() else 0f
    val inset = strokeWidthPx / 2f
    val innerWidth = widthPx - strokeWidthPx
    val innerHeight = heightPx - strokeWidthPx
    if (innerWidth <= 0f || innerHeight <= 0f) return

    val path = android.graphics.Path()
    buildIslandPath(
        path,
        settings.islandShape,
        innerWidth,
        innerHeight,
        settings.cornerRadiusDp.dp.toPx(),
    )
    path.offset(inset, inset)
    val composePath = path.asComposePath()
    drawPath(composePath, NothingBlack)
    if (strokeWidthPx > 0f) {
        drawPath(composePath, Color(settings.strokeColorArgb), style = Stroke(strokeWidthPx))
    } else {
        // The real overlay has no outline; the preview keeps a faint edge so the shape reads.
        drawPath(composePath, Color.White.copy(alpha = 0.16f), style = Stroke(1f))
    }
}

@Composable
internal fun CalibrationSlider(
    label: String,
    value: Int,
    range: IntRange,
    signed: Boolean = false,
    enabled: Boolean = true,
    unit: String = "dp",
    onValueChange: (Int) -> Unit,
) {
    // Draft state keeps the thumb under the finger while DataStore round-trips.
    var draft by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(value, dragging) {
        if (!dragging) draft = value.toFloat()
    }

    Column(
        Modifier
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NothingMuted,
                modifier = Modifier.weight(1f),
            )
            val rounded = draft.roundToInt()
            // A percentage reads as "50%", not "50 %"; dp values keep the space ("26 dp").
            val valueText = if (unit == "%") "$rounded%" else "$rounded $unit"
            Text(
                text = if (signed && rounded > 0) "+$valueText" else valueText,
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite,
            )
        }
        Slider(
            modifier = Modifier.semantics { contentDescription = label },
            value = draft,
            onValueChange = { newValue ->
                dragging = true
                draft = newValue
                onValueChange(newValue.roundToInt())
            },
            onValueChangeFinished = { dragging = false },
            enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = NothingRed,
                activeTrackColor = NothingRed,
                inactiveTrackColor = NothingHairline,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledThumbColor = NothingMuted,
                disabledActiveTrackColor = NothingHairline,
                disabledInactiveTrackColor = NothingHairline,
            ),
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NothingWhite,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = NothingMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NothingBlack,
                checkedTrackColor = NothingRed,
                checkedBorderColor = NothingRed,
                uncheckedThumbColor = NothingMuted,
                uncheckedTrackColor = NothingSurface,
                uncheckedBorderColor = NothingHairline,
            ),
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = NothingMuted,
    )
}

@Composable
private fun StatusDot(color: Color, size: Dp = 9.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

private enum class SetupStatus {
    Done,
    Todo,
    Optional,
}

private val SetupStatus.dotColor: Color
    get() = when (this) {
        SetupStatus.Done -> NothingWhite
        SetupStatus.Todo -> NothingRed
        SetupStatus.Optional -> NothingMuted
    }

private val SetupStatus.badge: String
    get() = when (this) {
        SetupStatus.Done -> "DONE"
        SetupStatus.Todo -> "TO DO"
        SetupStatus.Optional -> "OPTIONAL"
    }

@Composable
private fun SetupItem(
    title: String,
    description: String,
    status: SetupStatus,
    actionLabel: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status.dotColor, 7.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NothingWhite,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status.badge,
                style = MaterialTheme.typography.labelSmall,
                color = status.dotColor,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = NothingMuted,
            modifier = Modifier.padding(start = 17.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            if (primary) {
                Button(
                    onClick = onClick,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = CircleShape,
                    contentPadding = contentPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NothingRed,
                        contentColor = NothingWhite,
                    ),
                ) {
                    ActionLabel(actionLabel)
                }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, NothingHairline),
                    contentPadding = contentPadding,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
                ) {
                    ActionLabel(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SetupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = NothingHairline,
        thickness = 1.dp,
    )
}

@Composable
private fun ActionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current,
    )
}
