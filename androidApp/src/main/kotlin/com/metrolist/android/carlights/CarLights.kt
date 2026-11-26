package com.metrolist.android.carlights

import android.app.UiModeManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.android.App
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.ui.theme.extractThemeColor
import com.metrolist.android.ui.theme.systemDynamicColorScheme
import com.metrolist.shared.settings.CarLightRole
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.theme.orIfIllegible
import com.metrolist.shared.ui.theme.resolveThemeSeedColor
import com.metrolist.shared.ui.theme.themeColorScheme
import com.metrolist.shared.utils.artworkColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Color as AndroidColor

private data class CarLightPalette(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
) {
    fun forRole(role: CarLightRole): Int? =
        when (role) {
            CarLightRole.OFF -> null
            CarLightRole.PRIMARY -> primary
            CarLightRole.SECONDARY -> secondary
            CarLightRole.TERTIARY -> tertiary
        }
}

// Resolve the theme without composition so the lights follow artwork while the screen is off.
object CarLights {
    private val started = AtomicBoolean(false)
    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private var controllerInstance: CarLightsController? = null
    private var trackerJob: Job? = null

    private val palette = MutableStateFlow<CarLightPalette?>(null)

    val controller: CarLightsController
        get() = checkNotNull(controllerInstance) { "CarLights.start() has not been called" }

    @OptIn(FlowPreview::class)
    fun start(
        context: Context,
        applicationScope: CoroutineScope,
        playerConnection: () -> PlayerConnection,
        imageLoader: () -> ImageLoader,
    ) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        scope = applicationScope
        val settings = Settings.shared
        val instance =
            CarLightsController(appContext) { address, name ->
                settings.carLightsDeviceAddress = address
                if (name.isNotEmpty()) settings.carLightsDeviceName = name
            }
        controllerInstance = instance

        scope.launch(Dispatchers.Main.immediate) {
            launch {
                snapshotFlow {
                    Config(
                        enabled = settings.carLightsEnabled,
                        autoConnect = settings.carLightsAutoConnect,
                        address = settings.carLightsDeviceAddress,
                        name = settings.carLightsDeviceName,
                    )
                }.distinctUntilChanged()
                    .collect { config ->
                        instance.configure(config.enabled, config.autoConnect, config.address, config.name)
                        if (config.enabled) startTracker(playerConnection, imageLoader) else stopTracker()
                    }
            }
            launch {
                combine(
                    palette,
                    snapshotFlow { settings.carLightsCentralRole },
                    snapshotFlow { settings.carLightsDoorsRole },
                    snapshotFlow { settings.carLightsRearRole },
                    snapshotFlow { settings.carLightsVivid },
                ) { palette, central, doors, rear, boost ->
                    palette ?: return@combine null
                    zoneColors(palette, central, doors, rear, boost)
                }.debounce(COLOR_DEBOUNCE_MS)
                    .distinctUntilChanged()
                    .collect { zones -> if (zones != null) instance.setZoneColors(zones) }
            }
        }
    }

    fun resend() {
        val currentPalette = palette.value ?: return
        val settings = Settings.shared
        controller.setZoneColors(
            zoneColors(
                currentPalette,
                settings.carLightsCentralRole,
                settings.carLightsDoorsRole,
                settings.carLightsRearRole,
                settings.carLightsVivid,
            ),
        )
    }

    private fun zoneColors(
        palette: CarLightPalette,
        central: CarLightRole,
        doors: CarLightRole,
        rear: CarLightRole,
        boost: Boolean,
    ): Map<Int, Int> =
        buildMap {
            palette.forRole(central)?.let { put(CarLightProtocol.ZONE_CENTRAL, if (boost) boostForLed(it) else it) }
            palette.forRole(doors)?.let { put(CarLightProtocol.ZONE_DOORS, if (boost) boostForLed(it) else it) }
            palette.forRole(rear)?.let { put(CarLightProtocol.ZONE_REAR, if (boost) boostForLed(it) else it) }
        }

    private fun startTracker(
        playerConnection: () -> PlayerConnection,
        imageLoader: () -> ImageLoader,
    ) {
        if (trackerJob?.isActive == true) return
        trackerJob =
            scope.launch(Dispatchers.Main.immediate) {
                App.instance.startup.await()
                val settings = Settings.shared
                val player = playerConnection()
                val loader = imageLoader()
                // A cold start can retain dark mode from before the system changed it.
                publishSystemDarkTheme(appContext.resources.configuration)
                val artworkSeed = MutableStateFlow<Color?>(null)
                launch {
                    combine(
                        player.currentSong.map { it?.thumbnail }.distinctUntilChanged(),
                        snapshotFlow { settings.materialYouEnabled && settings.dynamicTheme },
                    ) { url, enabled -> url.takeIf { enabled } }
                        .distinctUntilChanged()
                        .collectLatest { url ->
                            artworkSeed.value = url?.let { extractSeed(loader, it) }
                        }
                }
                combine(
                    artworkSeed,
                    snapshotFlow {
                        ThemeInputs(
                            materialYou = settings.materialYouEnabled,
                            songArtwork = settings.materialYouEnabled && settings.dynamicTheme,
                            selectedThemeColor = settings.selectedThemeColor,
                            paletteStyle = settings.themePaletteStyle,
                            dark = settings.getCurrentThemeIsDark(),
                        )
                    }.distinctUntilChanged(),
                    systemContrast(),
                    systemConfiguration(),
                ) { seed, inputs, contrast, _ -> Triple(inputs, seed, contrast) }
                    .collectLatest { (inputs, seed, contrast) ->
                        val scheme = withContext(Dispatchers.Default) { schemeFor(inputs, seed, contrast) }
                        palette.value =
                            CarLightPalette(
                                primary = scheme.primary.rgb(),
                                secondary = scheme.secondary.rgb(),
                                tertiary = scheme.tertiary.rgb(),
                            )
                    }
            }
    }

    private fun systemConfiguration(): Flow<Unit> =
        callbackFlow {
            val callbacks =
                object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        publishSystemDarkTheme(newConfig)
                        trySend(Unit)
                    }

                    override fun onLowMemory() = Unit
                }
            appContext.registerComponentCallbacks(callbacks)
            trySend(Unit)
            awaitClose { appContext.unregisterComponentCallbacks(callbacks) }
        }

    private fun publishSystemDarkTheme(configuration: Configuration) {
        Settings.shared.systemInDarkTheme =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun systemContrast(): Flow<Float> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callbackFlow {
                val manager = appContext.getSystemService(UiModeManager::class.java)
                val listener = UiModeManager.ContrastChangeListener { trySend(it) }
                manager.addContrastChangeListener(appContext.mainExecutor, listener)
                trySend(manager.contrast)
                awaitClose { manager.removeContrastChangeListener(listener) }
            }
        } else {
            flowOf(0f)
        }

    private fun stopTracker() {
        trackerJob?.cancel()
        trackerJob = null
    }

    private suspend fun extractSeed(
        loader: ImageLoader,
        url: String,
    ): Color? =
        try {
            val request =
                ImageRequest
                    .Builder(appContext)
                    .artworkColors(url)
                    .allowHardware(false)
                    .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val bitmap = result.image.toBitmap()
                withContext(Dispatchers.Default) { bitmap.extractThemeColor() }
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not extract artwork seed")
            null
        }

    private data class Config(
        val enabled: Boolean,
        val autoConnect: Boolean,
        val address: String,
        val name: String,
    )

    private data class ThemeInputs(
        val materialYou: Boolean,
        val songArtwork: Boolean,
        val selectedThemeColor: Long,
        val paletteStyle: ThemePaletteStyle,
        val dark: Boolean,
    )

    private fun schemeFor(
        inputs: ThemeInputs,
        artworkSeed: Color?,
        contrast: Float,
    ): ColorScheme {
        val seed = artworkSeed.takeIf { inputs.songArtwork }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && inputs.materialYou && seed == null) {
            val wallpaperSeed = Color(appContext.getColor(android.R.color.system_accent1_500))
            val generated = { themeColorScheme(wallpaperSeed, inputs.dark, inputs.paletteStyle, contrast.toDouble()) }
            return if (inputs.paletteStyle == ThemePaletteStyle.TONAL_SPOT) {
                systemDynamicColorScheme(appContext, inputs.dark).orIfIllegible(inputs.dark) { generated() }
            } else {
                generated()
            }
        }
        val themeColor =
            resolveThemeSeedColor(
                materialYouEnabled = inputs.materialYou,
                songArtworkColorsEnabled = inputs.songArtwork,
                selectedThemeColor = inputs.selectedThemeColor,
                artworkSeedColor = seed,
            )
        return themeColorScheme(themeColor, inputs.dark, inputs.paletteStyle, contrast.toDouble())
    }

    private fun Color.rgb(): Int = toArgb() and 0xFFFFFF

    // Pale Material tones appear near white on RGB LEDs, so retain hue and boost saturation.
    private fun boostForLed(rgb: Int): Int {
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, hsv)
        if (hsv[1] < NEUTRAL_SATURATION) return rgb
        hsv[1] = maxOf(hsv[1], VIVID_SATURATION)
        hsv[2] = 1f
        return AndroidColor.HSVToColor(hsv) and 0xFFFFFF
    }

    private const val TAG = "CarLights"
    private const val COLOR_DEBOUNCE_MS = 400L
    private const val NEUTRAL_SATURATION = 0.08f
    private const val VIVID_SATURATION = 0.85f
}
