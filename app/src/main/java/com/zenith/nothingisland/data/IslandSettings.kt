package com.zenith.nothingisland.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Immutable snapshot of every calibration knob. All values are density-independent pixels.
 *
 * The service renders straight from this object, so adding a knob here + a DataStore key is all
 * that a new visual parameter needs.
 */
data class IslandSettings(
    val offsetXDp: Int = DEFAULT_OFFSET_X_DP,
    val offsetYDp: Int = DEFAULT_OFFSET_Y_DP,
    val widthDp: Int = DEFAULT_WIDTH_DP,
    val heightDp: Int = DEFAULT_HEIGHT_DP,
    val cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
    /** Outline of the pill. [strokeWidthDp] and [strokeColorArgb] only apply while this is true. */
    val strokeEnabled: Boolean = DEFAULT_STROKE_ENABLED,
    val strokeWidthDp: Int = DEFAULT_STROKE_WIDTH_DP,
    /** ARGB int; the default is the original 15% white hairline (0x26FFFFFF). */
    val strokeColorArgb: Int = DEFAULT_STROKE_COLOR_ARGB,
    val hideInFullscreen: Boolean = DEFAULT_HIDE_IN_FULLSCREEN,
    /**
     * Keeps the island anchored to the portrait top-centre: its geometry is always derived from
     * the natural (portrait) display width, so landscape apps and fullscreen video do not resize
     * or re-anchor it. Rotation-aware behaviour is used when this is false.
     */
    val lockPortrait: Boolean = DEFAULT_LOCK_PORTRAIT,
    /** When true the overlay places itself relative to the physical camera hole and avoids it. */
    val holeEnabled: Boolean = DEFAULT_HOLE_ENABLED,
    /** Horizontal hole offset from the portrait screen centre. */
    val holeCenterXDp: Int = DEFAULT_HOLE_CENTER_X_DP,
    /** Hole centre measured down from the portrait screen top. */
    val holeCenterYDp: Int = DEFAULT_HOLE_CENTER_Y_DP,
    val holeRadiusDp: Int = DEFAULT_HOLE_RADIUS_DP,
    val holeMode: IslandHoleMode = DEFAULT_HOLE_MODE,
    /** Hole position from the pill's left edge (20..80); only used by [IslandHoleMode.RATIO]. */
    val holeRatioPercent: Int = DEFAULT_HOLE_RATIO_PERCENT,
    /** Edge that carries the hole; only used by [IslandHoleMode.ONE_SIDE]. */
    val holeSide: IslandHoleSide = DEFAULT_HOLE_SIDE,
    /** Draws the red alignment ring on the real overlay (calibration only). */
    val showHoleGuide: Boolean = DEFAULT_SHOW_HOLE_GUIDE,
    val islandShape: IslandShape = DEFAULT_ISLAND_SHAPE,
    /**
     * Motion runs at 90 Hz normally and at 120 Hz when this is on. The panel only receives a
     * frame-rate hint while something is actually animating, so battery use stays idle when the
     * island is still.
     */
    val highQualityMotion: Boolean = DEFAULT_HIGH_QUALITY_MOTION,
    /**
     * Seconds the island stays visible with nothing to show before it curls into the camera hole.
     * Any content cancels the countdown.
     */
    val idleContractDelaySec: Int = DEFAULT_IDLE_CONTRACT_DELAY_SEC,
) {
    /** Keeps every value inside its supported range so the overlay can never be drawn broken. */
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
        /** The UI only exposes the small side, so the useful range is 20..50. */
        val HOLE_RATIO_UI_RANGE = 20..50

        const val DEFAULT_OFFSET_X_DP = 0
        const val DEFAULT_OFFSET_Y_DP = 6
        const val DEFAULT_WIDTH_DP = 110
        const val DEFAULT_HEIGHT_DP = 32
        const val DEFAULT_CORNER_RADIUS_DP = 16
        const val DEFAULT_STROKE_ENABLED = true
        const val DEFAULT_STROKE_WIDTH_DP = 1

        /** Color.argb(38, 255, 255, 255): the original hairline, byte for byte. */
        const val DEFAULT_STROKE_COLOR_ARGB = 0x26FFFFFF
        const val DEFAULT_HIDE_IN_FULLSCREEN = true
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
    }
}

private val Context.islandSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "island_settings",
)

/**
 * Single source of truth for the island's look. The UI writes here; [IslandService][com.zenith.nothingisland.service.IslandService]
 * collects [settings] and redraws the window, which keeps the service and the app decoupled.
 */
class IslandSettingsRepository private constructor(private val context: Context) {

    val settings: Flow<IslandSettings> = context.islandSettingsDataStore.data
        .catch { throwable ->
            // A corrupt or unreadable file must not crash the service; fall back to defaults.
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
        }
    }

    suspend fun setOffsetXDp(value: Int) = update { it.copy(offsetXDp = value) }

    suspend fun setOffsetYDp(value: Int) = update { it.copy(offsetYDp = value) }

    suspend fun setWidthDp(value: Int) = update { it.copy(widthDp = value) }

    suspend fun setHeightDp(value: Int) = update { it.copy(heightDp = value) }

    suspend fun setCornerRadiusDp(value: Int) = update { it.copy(cornerRadiusDp = value) }

    suspend fun setStrokeEnabled(enabled: Boolean) = update { it.copy(strokeEnabled = enabled) }

    suspend fun setStrokeWidthDp(value: Int) = update { it.copy(strokeWidthDp = value) }

    /** [argb] is stored as-is (alpha included); the UI offers opaque swatches and an RRGGBB field. */
    suspend fun setStrokeColorArgb(argb: Int) = update { it.copy(strokeColorArgb = argb) }

    suspend fun setHideInFullscreen(enabled: Boolean) = update { it.copy(hideInFullscreen = enabled) }

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

    suspend fun setHighQualityMotion(enabled: Boolean) =
        update { it.copy(highQualityMotion = enabled) }

    suspend fun setIdleContractDelaySec(value: Int) =
        update { it.copy(idleContractDelaySec = value) }

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
    idleContractDelaySec = this[Keys.idleContractDelay]
        ?: IslandSettings.DEFAULT_IDLE_CONTRACT_DELAY_SEC,
).coerced()

/** Unknown/renamed enum names fall back instead of throwing, so old data always loads. */
private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
    if (this == null) default else runCatching { enumValueOf<T>(this) }.getOrDefault(default)
