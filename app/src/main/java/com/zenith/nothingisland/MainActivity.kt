package com.zenith.nothingisland

import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.data.IslandSettingsRepository
import com.zenith.nothingisland.service.IslandService
import com.zenith.nothingisland.service.timer.TimerModule
import com.zenith.nothingisland.ui.HomeScreen
import com.zenith.nothingisland.ui.theme.NothingIslandTheme
import com.zenith.nothingisland.util.ServiceStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val repository by lazy { IslandSettingsRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            NothingIslandTheme {
                val settings by repository.settings.collectAsStateWithLifecycle(
                    initialValue = IslandSettings(),
                )

                var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
                var notificationListenerEnabled by remember {
                    mutableStateOf(ServiceStatus.isNotificationListenerEnabled(this@MainActivity))
                }
                var batteryOptimizationIgnored by remember {
                    mutableStateOf(ServiceStatus.isIgnoringBatteryOptimizations(this@MainActivity))
                }
                var overlayGranted by remember {
                    mutableStateOf(ServiceStatus.canDrawOverlays(this@MainActivity))
                }
                var timerPresetMinutes by remember { mutableIntStateOf(DEFAULT_TIMER_MINUTES) }
                val timer by TimerModule.state.collectAsStateWithLifecycle()

                // Refresh grants on resume and keep the status dot live while visible.
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            serviceEnabled = isAccessibilityServiceEnabled()
                            notificationListenerEnabled =
                                ServiceStatus.isNotificationListenerEnabled(this@MainActivity)
                            batteryOptimizationIgnored =
                                ServiceStatus.isIgnoringBatteryOptimizations(this@MainActivity)
                            overlayGranted = ServiceStatus.canDrawOverlays(this@MainActivity)
                            delay(STATUS_POLL_INTERVAL_MS)
                        }
                    }
                }

                HomeScreen(
                    settings = settings,
                    serviceEnabled = serviceEnabled,
                    notificationListenerEnabled = notificationListenerEnabled,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    overlayGranted = overlayGranted,
                    timer = timer,
                    timerPresetMinutes = timerPresetMinutes,
                    onTimerPresetChange = { timerPresetMinutes = it },
                    onTimerStart = {
                        // A timer without the overlay would be invisible: never pretend it started.
                        if (!serviceEnabled || !IslandService.isRunning) {
                            Toast.makeText(
                                this@MainActivity,
                                "Enable the accessibility service first",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            TimerModule.start(timerPresetMinutes * 60_000L)
                        }
                    },
                    onTimerToggle = { TimerModule.togglePause() },
                    onTimerStop = { TimerModule.stop() },
                    onDetectHole = ::detectCameraHole,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                    onRequestIgnoreBatteryOptimizations = ::requestIgnoreBatteryOptimizations,
                    onOpenOverlaySettings = ::openOverlaySettings,
                    onSettingsChange = { transform ->
                        // The service collects the DataStore flow, so this is all that live updates need.
                        lifecycleScope.launch { repository.update(transform) }
                    },
                )
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean =
        ServiceStatus.isAccessibilityServiceEnabled(this, IslandService::class.java)

    /**
     * Seeds the camera-hole calibration from the platform cutout. Only small cutouts are accepted
     * (a status-bar-wide rect is not the lens); the red on-screen guide remains the authority.
     * Raw rects are logged under `IslandHole` so a device run can be inspected.
     */
    private fun detectCameraHole() {
        val metrics = getSystemService(WindowManager::class.java)?.currentWindowMetrics
        val cutout = metrics?.windowInsets?.displayCutout
        val rects = cutout?.boundingRects.orEmpty()
        Log.d(HOLE_TAG, "displayCutout boundingRects=${rects.joinToString { it.toShortString() }}")

        val density = resources.displayMetrics.density
        val screenWidthPx = metrics?.bounds?.width() ?: 0
        // Nothing OS reports the whole status-bar exclusion as the cutout rect (86x126 px here),
        // which is not the lens. The cutout path still describes the real punch hole, so add its
        // bounds as a candidate too and let the smallest plausible shape win.
        val pathBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cutout?.cutoutPath?.takeIf { !it.isEmpty }?.let { path ->
                RectF().also { path.computeBounds(it, true) }
            }
        } else {
            null
        }
        Log.d(HOLE_TAG, "displayCutout pathBounds=${pathBounds?.toShortString()}")

        // A punch-hole is small: reject anything taller than ~40 dp or wider than ~80 dp so a
        // padded status-bar exclusion can never place a huge ring off-centre.
        val plausible = (rects.map { RectF(it) } + listOfNotNull(pathBounds))
            .filter { it.width() > 0f && it.height() > 0f && density > 0f }
            .filter { it.width() / density <= 80f && it.height() / density <= 40f }
            .minByOrNull { it.width() * it.height() }

        if (plausible == null || density <= 0f || screenWidthPx <= 0) {
            Log.d(HOLE_TAG, "no small cutout rect; keeping current calibration")
            Toast.makeText(
                this,
                "No small cutout found — drag the ring onto the lens",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val xDp = ((plausible.centerX() - screenWidthPx / 2f) / density).roundToInt()
        val yDp = (plausible.centerY() / density).roundToInt()
        val radiusDp = (max(plausible.width(), plausible.height()) / 2f / density).roundToInt()
        lifecycleScope.launch {
            repository.update {
                it.copy(holeCenterXDp = xDp, holeCenterYDp = yDp, holeRadiusDp = radiusDp)
            }
        }
        Toast.makeText(
            this,
            "Cutout: x $xDp dp · y $yDp dp · r $radiusDp dp",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openAccessibilitySettings() {
        startActivitySafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openNotificationListenerSettings() {
        startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestIgnoreBatteryOptimizations() {
        startActivitySafely(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun openOverlaySettings() {
        startActivitySafely(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    /** Some OEMs remove individual settings screens; fall back to the app's detail page. */
    private fun startActivitySafely(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
    }

    private companion object {
        const val STATUS_POLL_INTERVAL_MS = 1_500L
        const val DEFAULT_TIMER_MINUTES = 5
        const val HOLE_TAG = "IslandHole"
    }
}
