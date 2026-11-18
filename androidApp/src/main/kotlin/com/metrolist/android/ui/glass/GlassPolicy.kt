package com.metrolist.android.ui.glass

// Adapted from Square GlassStore (Convx Project, GPL-3.0); see NOTICE-square.txt.
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.utils.rememberPreference
import com.metrolist.android.utils.activeProfileKey
import com.metrolist.android.utils.getActiveProfile
import com.metrolist.android.utils.profilePreferences
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.theme.paletteDarkness

val LocalGlassArtworkColor = staticCompositionLocalOf<Color?> { null }
internal val LocalGlassArtworkTint = staticCompositionLocalOf<State<GlassArtworkTint>?> { null }

@Composable
internal fun currentGlassArtworkTint(): GlassArtworkTint? =
    LocalGlassArtworkTint.current?.value ?: LocalGlassMenuHost.current?.artworkTint

@Composable
internal fun glassSurfaceDarkness(settings: GlassSettings): Float =
    maxOf(paletteDarkness(), glassArtworkDarkness(settings))

@Composable
internal fun glassArtworkForcesDark(settings: GlassSettings): Boolean =
    glassArtworkDarkness(settings) > 0f

@Composable
internal fun glassArtworkDarkness(settings: GlassSettings): Float =
    if (settings.source == GlassTintSource.Artwork) currentGlassArtworkTint()?.darkness ?: 0f else 0f

@Composable
internal fun glassArtworkColor(): Color? {
    val scoped = LocalGlassArtworkTint.current
    if (scoped == null) LocalGlassArtworkColor.current?.let { return it }
    val artwork = currentGlassArtworkTint()?.color
    if (artwork == null) return null
    return if (artwork.alpha == 1f) artwork else lerp(colorPalette().accent, artwork.copy(alpha = 1f), artwork.alpha)
}

const val glassEnabledKey = "liquidGlassEnabled"

/** Fresh installations opt in explicitly; persisted choices remain authoritative. */
const val glassEnabledDefault = false

// This store is separate from the interrupted prototype's percentage preferences.
const val glassTintSourceKey = "source"
const val glassQualityKey = "profile"
const val glassCustomColorKey = "custom_color"
const val glassTintStrengthKey = "tint_strength"
const val glassVibrancyKey = "vibrancy"
const val glassBlurKey = "blur"
const val glassFilmKey = "film"
const val glassIndicatorOpacityKey = "puck"
const val glassDialogDimmingKey = "dialog_dimming"
const val glassMenuDimmingKey = "menu_dimming"
const val glassRimKey = "rim"
const val glassDepthKey = "lens_height"
const val glassAmountKey = "lens_amount"
const val glassDispersionKey = "dispersion"
const val glassRippleEnabledKey = "ripple_enabled"
const val glassRippleIntensityKey = "ripple_intensity"
const val glassRippleSizeKey = "ripple_size"
const val glassJellyEnabledKey = "jelly_enabled"
const val glassJellyIntensityKey = "jelly_intensity"
const val glassDepthEffectKey = "depth_effect"
const val glassHeaderKey = "header"
const val glassNavKey = "nav_bar"
const val glassMenusKey = "menus"
const val glassMiniPlayerKey = "mini_player"
const val glassDropdownsKey = "dropdowns"
const val glassDialogsKey = "dialogs"
const val glassDialogButtonsKey = "dialog_buttons"
const val glassNowPlayingKey = "now_playing"
const val glassImageRimsKey = "image_rims"
const val glassSettingsCardRimsKey = "settings_card_rims"
const val glassSettingsCardsKey = "settings_cards"
const val glassSearchBarsKey = "search_bars"
const val glassActionButtonsKey = "action_buttons"
const val glassOptimizationEnabledKey = "optimization_enabled"
private const val glassIndependentAreasKey = "independent_areas"

/** Tint colours the material without changing the app palette. */
enum class GlassTintSource { None, ThemeAccent, Artwork, SystemAccent, Custom }
enum class GlassQuality { Performance, Balanced, Quality }
enum class GlassRenderer { Refraction, Blur, Tint }

enum class GlassAreaMode(val hasMaterial: Boolean, val hasContour: Boolean) {
    Disabled(false, false), ContourOnly(false, true), GlassOnly(true, false), GlassAndContour(true, true),
}

/** Images retain their sharp content and expose only their supported edge treatment. */
enum class GlassImageMode { Disabled, ContourOnly }

enum class GlassArea(val legacyKey: String) {
    Nav(glassNavKey), MiniPlayer(glassMiniPlayerKey), Header(glassHeaderKey), Menus(glassMenusKey),
    Dropdowns(glassDropdownsKey), Dialogs(glassDialogsKey), DialogButtons(glassDialogButtonsKey),
    NowPlaying(glassNowPlayingKey), Images(glassImageRimsKey), SettingsCards(glassSettingsCardsKey),
    SearchBars(glassSearchBarsKey), ActionButtons(glassActionButtonsKey), Switches("switches"), MoodCards("mood_cards"),
    LoadingIndicators("loading_indicators"), PullToRefresh("pull_to_refresh");

    val preferenceKey: String get() = "${legacyKey}_mode"
}

@Immutable
data class GlassSettings(
    val enabled: Boolean = true,
    val source: GlassTintSource = GlassTintSource.None,
    val quality: GlassQuality = GlassQuality.Balanced,
    val customColor: Int = 0xFF8B5CF6.toInt(),
    val tintStrength: Float = .6f,
    val vibrancy: Float = 1.2f,
    val blur: Float = 2f,
    val film: Float = .5f,
    val indicatorOpacity: Float = .26f,
    val dialogDimming: Float = .75f,
    val menuDimming: Float = .75f,
    val rim: Float = .55f,
    val depth: Float = .4f,
    val amount: Float = .6f,
    val dispersion: Boolean = false,
    val depthEffect: Boolean = false,
    val rippleEnabled: Boolean = true,
    val rippleIntensity: Float = 1f,
    val rippleSize: Float = 1f,
    val jellyEnabled: Boolean = true,
    val jellyIntensity: Float = 1f,
    val header: Boolean = true,
    val nav: Boolean = true,
    val menus: Boolean = true,
    val miniPlayer: Boolean = true,
    val dropdowns: Boolean = true,
    val dialogs: Boolean = true,
    val dialogButtons: Boolean = true,
    val nowPlaying: Boolean = true,
    val imageRims: Boolean = true,
    val settingsCardRims: Boolean = true,
    val settingsCards: Boolean = true,
    val searchBars: Boolean = true,
    val actionButtons: Boolean = true,
    val optimizationEnabled: Boolean = true,
    val areaModes: Map<GlassArea, GlassAreaMode> = emptyMap(),
    val contextModes: Map<GlassContextArea, GlassAreaMode> = emptyMap(),
    val scope: GlassScope = GlassScope.Shared,
)

/** Lazily migrate older switches without changing their saved values or appearance. */
fun GlassSettings.mode(area: GlassArea): GlassAreaMode {
    if (area in scope.areas) contextModes[GlassContextArea(scope, area)]?.let {
        return if (area == GlassArea.Images && it != GlassAreaMode.Disabled) GlassAreaMode.ContourOnly else it
    }
    areaModes[area]?.let { return if (area == GlassArea.Images && it != GlassAreaMode.Disabled) GlassAreaMode.ContourOnly else it }
    val material = when (area) {
        GlassArea.Nav -> nav
        GlassArea.MiniPlayer -> miniPlayer
        GlassArea.Header -> header
        GlassArea.Menus -> menus
        GlassArea.Dropdowns -> dropdowns
        GlassArea.Dialogs -> dialogs
        GlassArea.DialogButtons -> dialogs && dialogButtons
        GlassArea.NowPlaying -> nowPlaying
        GlassArea.SettingsCards -> settingsCards
        GlassArea.SearchBars -> searchBars
        GlassArea.ActionButtons -> actionButtons
        GlassArea.MoodCards, GlassArea.Switches, GlassArea.LoadingIndicators, GlassArea.PullToRefresh -> true
        GlassArea.Images -> false
    }
    val contour = when (area) {
        GlassArea.Images -> imageRims
        GlassArea.SettingsCards -> settingsCardRims
        else -> material
    }
    return when {
        material && contour -> GlassAreaMode.GlassAndContour
        material -> GlassAreaMode.GlassOnly
        contour -> GlassAreaMode.ContourOnly
        else -> GlassAreaMode.Disabled
    }
}

fun GlassSettings.usesGlassMaterial(area: GlassArea): Boolean = enabled && mode(area).hasMaterial
fun GlassSettings.usesGlassContour(area: GlassArea): Boolean = enabled && mode(area).hasContour && rim > 0f

/** Zero rim removes the visible highlight while retaining the complete glass optics. */
fun GlassSettings.forArea(area: GlassArea): GlassSettings = copy(rim = if (mode(area).hasContour) rim else 0f)

/** Square's profile defaults including the store's puck and tint overrides. */
fun glassProfileDefaults(quality: GlassQuality): GlassSettings = when (quality) {
    GlassQuality.Performance -> GlassSettings(quality = quality, film = .55f)
    GlassQuality.Balanced -> GlassSettings(quality = quality)
    GlassQuality.Quality -> GlassSettings(quality = quality, vibrancy = 1.4f, blur = 4f,
        depth = .5f, amount = .8f, dispersion = true)
}

fun defaultGlassQuality(sdk: Int, lowRam: Boolean): GlassQuality =
    if (sdk < 31 || lowRam) GlassQuality.Performance else GlassQuality.Balanced

fun clampGlassValue(key: String, value: Float): Float {
    val range = when (key) {
        glassVibrancyKey -> 0f..2f
        glassBlurKey -> 0f..24f
        glassRippleSizeKey -> .5f..2f
        else -> 0f..1f
    }
    return if (value.isFinite()) value.coerceIn(range) else range.start
}

/** Reject mistyped known backup values before they reach typed SharedPreferences reads. */
internal fun validGlassPreferenceValue(key: String, value: Any): Boolean = when (key) {
    glassQualityKey, glassTintSourceKey -> value is String
    glassCustomColorKey -> value is Int
    glassTintStrengthKey, glassVibrancyKey, glassBlurKey, glassFilmKey,
    glassIndicatorOpacityKey, glassDialogDimmingKey, glassMenuDimmingKey,
    glassRimKey, glassDepthKey, glassAmountKey, glassRippleIntensityKey,
    glassRippleSizeKey, glassJellyIntensityKey -> value is Float && value.isFinite()
    glassDispersionKey, glassDepthEffectKey, glassRippleEnabledKey, glassJellyEnabledKey,
    glassSettingsCardRimsKey, glassIndependentAreasKey,
    glassOptimizationEnabledKey -> value is Boolean
    else -> when {
        GlassArea.entries.any { it.legacyKey == key } -> value is Boolean
        key.endsWith("_mode") -> value is String
        // Preserve settings added by future versions; current readers ignore unknown keys.
        else -> true
    }
}

internal fun glassSettingsFromPreferences(
    preferences: Map<String, *>,
    defaultQuality: GlassQuality,
): GlassSettings {
    val quality = GlassQuality.entries.firstOrNull { it.name == preferences[glassQualityKey] } ?: defaultQuality
    val base = glassProfileDefaults(quality)
    fun value(key: String, fallback: Float) = clampGlassValue(key, preferences[key] as? Float ?: fallback)
    fun flag(key: String, fallback: Boolean) = preferences[key] as? Boolean ?: fallback
    return base.copy(
        source = GlassTintSource.entries.firstOrNull { it.name == preferences[glassTintSourceKey] } ?: GlassTintSource.None,
        customColor = preferences[glassCustomColorKey] as? Int ?: base.customColor,
        tintStrength = value(glassTintStrengthKey, base.tintStrength),
        vibrancy = value(glassVibrancyKey, base.vibrancy),
        blur = value(glassBlurKey, base.blur),
        film = value(glassFilmKey, base.film),
        indicatorOpacity = value(glassIndicatorOpacityKey, base.indicatorOpacity),
        dialogDimming = value(glassDialogDimmingKey, base.dialogDimming),
        menuDimming = value(glassMenuDimmingKey, base.menuDimming),
        rim = value(glassRimKey, base.rim),
        depth = value(glassDepthKey, base.depth),
        amount = value(glassAmountKey, base.amount),
        dispersion = flag(glassDispersionKey, base.dispersion),
        depthEffect = flag(glassDepthEffectKey, base.depthEffect),
        rippleEnabled = flag(glassRippleEnabledKey, true),
        rippleIntensity = value(glassRippleIntensityKey, 1f),
        rippleSize = value(glassRippleSizeKey, 1f),
        jellyEnabled = flag(glassJellyEnabledKey, true),
        jellyIntensity = value(glassJellyIntensityKey, 1f),
        header = flag(glassHeaderKey, true),
        nav = flag(glassNavKey, true),
        menus = flag(glassMenusKey, true),
        miniPlayer = flag(glassMiniPlayerKey, true),
        dropdowns = flag(glassDropdownsKey, true),
        dialogs = flag(glassDialogsKey, true),
        dialogButtons = flag(glassDialogButtonsKey, true),
        nowPlaying = flag(glassNowPlayingKey, true),
        imageRims = flag(glassImageRimsKey, true),
        settingsCardRims = flag(glassSettingsCardRimsKey, true),
        settingsCards = flag(glassSettingsCardsKey, true),
        searchBars = flag(glassSearchBarsKey, true),
        actionButtons = flag(glassActionButtonsKey, true),
        optimizationEnabled = flag(glassOptimizationEnabledKey, true),
        contextModes = GlassScope.entries.flatMap { scope -> scope.areas.mapNotNull { area ->
            val key = GlassContextArea(scope, area)
            val saved = preferences[key.preferenceKey]
            GlassAreaMode.entries.firstOrNull { it.name == saved }?.let { key to it }
        } }.toMap(),
        areaModes = GlassArea.entries.mapNotNull { area ->
            val saved = preferences[area.preferenceKey]
            GlassAreaMode.entries.firstOrNull { it.name == saved }?.let { area to it }
        }.toMap(),
    )
}

/** Captures one app profile; destination policies survive optical quality changes. */
class GlassStore(context: Context, val profileId: String = getActiveProfile(context)) {
    private val preferences = GlassProfilePreferences.preferences(context, profileId)
    private val defaultQuality = defaultGlassQuality(Build.VERSION.SDK_INT,
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true)

    init {
        // Split the former shared menu switch once, then keep each area independent.
        val legacyMenus = preferences.getBoolean(glassMenusKey, true)
        if (!preferences.getBoolean(glassIndependentAreasKey, false)) {
            val missingKeys = listOf(glassDropdownsKey, glassDialogsKey).filterNot(preferences::contains)
            preferences.edit().apply {
                missingKeys.forEach { putBoolean(it, legacyMenus) }
                putBoolean(glassIndependentAreasKey, true)
            }.apply()
        }
    }

    fun read(): GlassSettings = glassSettingsFromPreferences(preferences.all, defaultQuality)

    fun setProfile(profile: GlassQuality) {
        if (read().quality == profile) return
        preferences.edit().apply {
            // A quality selection is an optical preset, never a reset of destinations or tint.
            listOf(glassVibrancyKey, glassBlurKey, glassFilmKey, glassIndicatorOpacityKey,
                glassRimKey, glassDepthKey, glassAmountKey, glassDispersionKey, glassDepthEffectKey)
                .forEach(::remove)
            putString(glassQualityKey, profile.name)
        }.apply()
    }
    /** Restores fresh device defaults; the separate profile-scoped master switch is unchanged. */
    fun reset() {
        preferences.edit().clear().putBoolean(glassIndependentAreasKey, true).apply()
    }
    fun setFloat(key: String, value: Float) { preferences.edit().putFloat(key, clampGlassValue(key, value)).apply() }
    fun setBoolean(key: String, value: Boolean) {
        preferences.edit().apply {
            putBoolean(key, value)
            GlassArea.entries.filter { it.legacyKey == key || key == glassSettingsCardRimsKey && it == GlassArea.SettingsCards }
                .forEach { remove(it.preferenceKey) }
        }.apply()
    }
    fun setAreaMode(area: GlassArea, mode: GlassAreaMode) {
        val supported = if (area == GlassArea.Images && mode != GlassAreaMode.Disabled) GlassAreaMode.ContourOnly else mode
        preferences.edit().putString(area.preferenceKey, supported.name).apply()
    }
    fun setContextMode(scope: GlassScope, area: GlassArea, mode: GlassAreaMode?) {
        require(area in scope.areas) { "Unsupported Glass context: $scope/$area" }
        val key = GlassContextArea(scope, area).preferenceKey
        preferences.edit().apply {
            if (mode == null) remove(key) else putString(key, mode.name)
        }.apply()
    }
    fun setTint(source: GlassTintSource) { preferences.edit().putString(glassTintSourceKey, source.name).apply() }
    fun setColor(color: Int) { preferences.edit().putInt(glassCustomColorKey, color).putString(glassTintSourceKey, GlassTintSource.Custom.name).apply() }
    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences.registerOnSharedPreferenceChangeListener(listener)
    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences.unregisterOnSharedPreferenceChangeListener(listener)
}

@Composable
fun rememberGlassStore(): GlassStore {
    val context = LocalContext.current.applicationContext
    var profileId by remember(context) { mutableStateOf(getActiveProfile(context)) }
    DisposableEffect(context) {
        val preferences = context.profilePreferences
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == activeProfileKey || changedKey == null) profileId = getActiveProfile(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        profileId = getActiveProfile(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(context, profileId) { GlassStore(context, profileId) }
}

/** Isolates a preview's descendants from the persisted application configuration. */
internal val LocalGlassPreviewSettings = staticCompositionLocalOf<GlassSettings?> { null }

/** Rebinds the profile-owned master toggle when a mounted page changes profile. */
@Composable
internal fun rememberGlassEnabled(store: GlassStore): MutableState<Boolean> =
    key(store.profileId) { rememberPreference(glassEnabledKey, glassEnabledDefault) }

@Composable
fun rememberGlassSettings(scope: GlassScope = LocalGlassScope.current): GlassSettings {
    LocalGlassPreviewSettings.current?.let { return it.copy(scope = scope) }
    val store = rememberGlassStore()
    var settings by remember(store) { mutableStateOf(store.read()) }
    val enabled by rememberGlassEnabled(store)
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settings = store.read() }
        store.register(listener)
        settings = store.read()
        onDispose { store.unregister(listener) }
    }
    return settings.copy(enabled = enabled, scope = scope)
}

fun selectGlassRenderer(settings: GlassSettings, sdk: Int, lowRam: Boolean): GlassRenderer = when {
    !settings.enabled || lowRam || settings.quality == GlassQuality.Performance || sdk < 31 -> GlassRenderer.Tint
    sdk < 33 -> GlassRenderer.Blur
    else -> GlassRenderer.Refraction
}

fun glassMotionAllowed(animationScale: Float, touchExploration: Boolean): Boolean =
    animationScale > 0f && !touchExploration

fun glassForeground(tint: Color): Color = if (tint.luminance() > .45f) Color(0xFF17171B) else Color.White

/** Resolves the selected enum source without replacing its saturation or tone. */
@Composable
fun glassTint(settings: GlassSettings): Color = when (settings.source) {
    GlassTintSource.None -> Color.Unspecified
    GlassTintSource.ThemeAccent -> colorPalette().accent
    GlassTintSource.Artwork -> glassArtworkColor() ?: colorPalette().accent
    GlassTintSource.SystemAccent -> if (Build.VERSION.SDK_INT >= 31) Color(LocalContext.current.getColor(android.R.color.system_accent1_400)) else Color.Unspecified
    GlassTintSource.Custom -> Color(settings.customColor)
}

@Composable
fun glassRenderer(settings: GlassSettings): GlassRenderer {
    val context = LocalContext.current
    val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    return selectGlassRenderer(settings, Build.VERSION.SDK_INT, lowRam)
}

@Composable
fun isGlassMotionAllowed(): Boolean = isGlassMotionAllowed(LocalContext.current)

internal fun isGlassMotionAllowed(context: Context): Boolean {
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    return glassMotionAllowed(scale, manager?.isTouchExplorationEnabled == true)
}

/** Shared lens coefficient for the user-controlled colour-splitting switch. */
internal fun GlassSettings.dispersionAmount(): Float = if (dispersion) 1f else 0f
