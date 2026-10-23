package com.metrolist.music.widget

import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import com.metrolist.music.R
import com.metrolist.music.constants.CurrentThemeColorKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.ui.theme.DefaultThemeColor
import com.metrolist.music.ui.theme.pureBlack
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.first

internal data class WidgetThemeColors(
    val surface: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val progressTrack: Int,
)

internal object WidgetTheme {
    suspend fun fromPreferences(
        context: Context,
        artworkThemeColor: Int? = null,
        hasArtwork: Boolean = false,
    ): WidgetThemeColors {
        val preferences = context.dataStore.data.first()
        val dynamicTheme = preferences[DynamicThemeKey] ?: true
        val selectedThemeColor = preferences[SelectedThemeColorKey] ?: DefaultThemeColor.toArgb()
        val effectiveThemeColor = if (dynamicTheme && hasArtwork) {
            artworkThemeColor ?: selectedThemeColor
        } else {
            selectedThemeColor
        }

        // The service also runs while MainActivity is closed, so persist the playing artwork's
        // effective seed here instead of relying solely on an activity-owned collector.
        if (preferences[CurrentThemeColorKey] != effectiveThemeColor) {
            context.safeDataStoreEdit { it[CurrentThemeColorKey] = effectiveThemeColor }
        }

        return create(
            context = context,
            themeColor = effectiveThemeColor,
            darkMode = preferences[DarkModeKey] ?: "AUTO",
            pureBlack = preferences[PureBlackKey] ?: false,
        )
    }

    suspend fun refreshWidgets(
        context: Context,
        widgetManager: MetrolistWidgetManager,
    ) {
        // Re-render widgets through their normal renderers. Theme-only partial RemoteViews are
        // not cached by Android and can lose their styling when the host restores a widget.
        widgetManager.refreshWidgetsForTheme()
        context.sendBroadcast(
            Intent(context, MusicRecognizerWidgetReceiver::class.java).apply {
                action = MusicRecognizerWidgetReceiver.ACTION_UPDATE_WIDGET
            },
        )
    }

    fun apply(
        views: RemoteViews,
        layoutId: Int,
        colors: WidgetThemeColors,
        recognizerActive: Boolean = false,
    ) {
        when (layoutId) {
            R.layout.widget_music_player -> {
                tintRoot(views, colors.surface)
                views.setTextColor(R.id.widget_song_title, colors.textPrimary)
                views.setTextColor(R.id.widget_artist_name, colors.textSecondary)
                tintBackground(views, R.id.widget_play_pause_container, colors.primaryContainer)
                tintImage(views, R.id.widget_play_pause, colors.onPrimaryContainer)
                tintBackground(views, R.id.widget_like_button_container, colors.primaryContainer)
                tintImage(views, R.id.widget_like_button, colors.primary)
                tintImage(views, R.id.widget_progress_fill, colors.primary)
                tintImage(views, R.id.widget_progress_track, colors.progressTrack)
            }

            R.layout.widget_compact_wide -> {
                tintRoot(views, colors.surface)
                views.setTextColor(R.id.widget_wide_song_title, colors.textPrimary)
                views.setTextColor(R.id.widget_wide_artist_name, colors.textSecondary)
                tintBackground(views, R.id.widget_wide_play_container, colors.primaryContainer)
                tintImage(views, R.id.widget_wide_play_pause, colors.onPrimaryContainer)
                tintBackground(views, R.id.widget_wide_like_button_container, colors.primaryContainer)
                tintImage(views, R.id.widget_wide_like_button, colors.primary)
            }

            R.layout.widget_compact_square -> {
                tintRoot(views, colors.surface)
                tintBackground(views, R.id.widget_compact_play_container, colors.primaryContainer)
                tintImage(views, R.id.widget_compact_play_pause, colors.onPrimaryContainer)
            }

            R.layout.widget_turntable -> {
                tintBackground(views, R.id.widget_turntable_nav_container, colors.primaryContainer)
                tintImage(views, R.id.widget_turntable_prev_button, colors.onPrimaryContainer)
                tintImage(views, R.id.widget_turntable_next_button, colors.onPrimaryContainer)
                tintBackground(views, R.id.widget_turntable_play_container, colors.primaryContainer)
                tintImage(views, R.id.widget_turntable_play_pause, colors.onPrimaryContainer)
            }

            R.layout.widget_playlist -> {
                tintRoot(views, colors.surface)
                views.setTextColor(R.id.widget_playlist_song_title, colors.textPrimary)
                views.setTextColor(R.id.widget_playlist_artist_name, colors.textSecondary)
                tintImage(views, R.id.widget_playlist_prev_icon, colors.textPrimary)
                tintImage(views, R.id.widget_playlist_next_icon, colors.textPrimary)
                tintBackground(views, R.id.widget_playlist_play_pause_container, colors.primaryContainer)
                tintImage(views, R.id.widget_playlist_play_pause, colors.onPrimaryContainer)
                tintBackground(views, R.id.widget_playlist_like_container, colors.primaryContainer)
                tintImage(views, R.id.widget_playlist_like_button, colors.primary)
                tintImage(views, R.id.widget_playlist_progress_fill, colors.primary)
                for (index in 1..8) {
                    tintBackground(
                        views,
                        contextViewId(layoutId, index, "play_container"),
                        colors.primaryContainer,
                    )
                    views.setTextColor(contextViewId(layoutId, index, "title"), colors.textPrimary)
                    tintImage(
                        views,
                        contextViewId(layoutId, index, "play"),
                        colors.onPrimaryContainer,
                    )
                }
            }

            R.layout.widget_recognizer_wide,
            R.layout.widget_recognizer_compact -> {
                tintRoot(views, colors.surface)
                views.setTextColor(R.id.widget_recognizer_song_title, colors.textPrimary)
                views.setTextColor(R.id.widget_recognizer_artist_name, colors.textSecondary)
                tintImage(
                    views,
                    R.id.widget_recognizer_mic_background,
                    if (recognizerActive) colors.primary else colors.primaryContainer,
                )
                tintImage(
                    views,
                    R.id.widget_recognizer_mic_icon,
                    if (recognizerActive) colors.onPrimary else colors.onPrimaryContainer,
                )
                tintImage(views, R.id.widget_recognizer_pulse, colors.primary)
            }

            R.layout.widget_recognizer_tiny -> {
                tintRoot(views, colors.surface)
                tintImage(
                    views,
                    R.id.widget_recognizer_tiny_mic_background,
                    if (recognizerActive) colors.primary else colors.primaryContainer,
                )
                tintImage(
                    views,
                    R.id.widget_recognizer_tiny_mic_icon,
                    if (recognizerActive) colors.onPrimary else colors.onPrimaryContainer,
                )
                tintImage(views, R.id.widget_recognizer_tiny_pulse, colors.primary)
            }
        }
    }

    private fun create(
        context: Context,
        themeColor: Int,
        darkMode: String,
        pureBlack: Boolean,
    ): WidgetThemeColors {
        val isDark = when (darkMode) {
            "ON" -> true
            "OFF" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val useSystemColors = themeColor == DefaultThemeColor.toArgb() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val baseColorScheme = if (useSystemColors) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            dynamicColorScheme(
                seedColor = ComposeColor(themeColor),
                isDark = isDark,
                isAmoled = isDark && pureBlack,
                style = PaletteStyle.TonalSpot,
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
            )
        }
        val colorScheme = if (isDark && pureBlack) baseColorScheme.pureBlack(true) else baseColorScheme
        return WidgetThemeColors(
            surface = colorScheme.surface.toArgb(),
            primary = colorScheme.primary.toArgb(),
            onPrimary = colorScheme.onPrimary.toArgb(),
            primaryContainer = colorScheme.primaryContainer.toArgb(),
            onPrimaryContainer = colorScheme.onPrimaryContainer.toArgb(),
            textPrimary = colorScheme.onSurface.toArgb(),
            textSecondary = colorScheme.onSurfaceVariant.toArgb(),
            progressTrack = colorScheme.surfaceVariant.toArgb(),
        )
    }

    private fun tintRoot(views: RemoteViews, color: Int) {
        tintImage(views, R.id.widget_theme_root_background, color)
    }

    private fun tintBackground(views: RemoteViews, viewId: Int, color: Int) {
        val backgroundImageId = when (viewId) {
            android.R.id.background,
            R.id.widget_recognizer_tiny_root -> R.id.widget_theme_root_background
            R.id.widget_play_pause_container -> R.id.widget_play_pause_background
            R.id.widget_like_button_container -> R.id.widget_like_button_background
            R.id.widget_wide_play_container -> R.id.widget_wide_play_background
            R.id.widget_wide_like_button_container -> R.id.widget_wide_like_button_background
            R.id.widget_compact_play_container -> R.id.widget_compact_play_background
            R.id.widget_turntable_nav_container -> R.id.widget_turntable_nav_background
            R.id.widget_turntable_play_container -> R.id.widget_turntable_play_background
            R.id.widget_playlist_play_pause_container -> R.id.widget_playlist_play_pause_background
            R.id.widget_playlist_like_container -> R.id.widget_playlist_like_background
            R.id.widget_recognizer_mic_container -> R.id.widget_recognizer_mic_background
            R.id.widget_playlist_card_1_play_container -> R.id.widget_playlist_card_1_play_background
            R.id.widget_playlist_card_2_play_container -> R.id.widget_playlist_card_2_play_background
            R.id.widget_playlist_card_3_play_container -> R.id.widget_playlist_card_3_play_background
            R.id.widget_playlist_card_4_play_container -> R.id.widget_playlist_card_4_play_background
            R.id.widget_playlist_card_5_play_container -> R.id.widget_playlist_card_5_play_background
            R.id.widget_playlist_card_6_play_container -> R.id.widget_playlist_card_6_play_background
            R.id.widget_playlist_card_7_play_container -> R.id.widget_playlist_card_7_play_background
            R.id.widget_playlist_card_8_play_container -> R.id.widget_playlist_card_8_play_background
            else -> 0
        }

        if (backgroundImageId != 0) {
            // Tint the original shape drawable through an ImageView. This works
            // on Android 11 and older while preserving its rounded corners.
            tintImage(views, backgroundImageId, color)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                viewId,
                "setBackgroundTintList",
                android.content.res.ColorStateList.valueOf(color),
            )
        }
    }
    private fun tintImage(views: RemoteViews, viewId: Int, color: Int) {
        views.setInt(viewId, "setColorFilter", color)
    }

    private fun contextViewId(layoutId: Int, index: Int, kind: String): Int = when (kind) {
        "play_container" -> when (index) {
            1 -> R.id.widget_playlist_card_1_play_container
            2 -> R.id.widget_playlist_card_2_play_container
            3 -> R.id.widget_playlist_card_3_play_container
            4 -> R.id.widget_playlist_card_4_play_container
            5 -> R.id.widget_playlist_card_5_play_container
            6 -> R.id.widget_playlist_card_6_play_container
            7 -> R.id.widget_playlist_card_7_play_container
            else -> R.id.widget_playlist_card_8_play_container
        }
        "title" -> when (index) {
            1 -> R.id.widget_playlist_card_1_title
            2 -> R.id.widget_playlist_card_2_title
            3 -> R.id.widget_playlist_card_3_title
            4 -> R.id.widget_playlist_card_4_title
            5 -> R.id.widget_playlist_card_5_title
            6 -> R.id.widget_playlist_card_6_title
            7 -> R.id.widget_playlist_card_7_title
            else -> R.id.widget_playlist_card_8_title
        }
        else -> when (index) {
            1 -> R.id.widget_playlist_card_1_play
            2 -> R.id.widget_playlist_card_2_play
            3 -> R.id.widget_playlist_card_3_play
            4 -> R.id.widget_playlist_card_4_play
            5 -> R.id.widget_playlist_card_5_play
            6 -> R.id.widget_playlist_card_6_play
            7 -> R.id.widget_playlist_card_7_play
            else -> R.id.widget_playlist_card_8_play
        }
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val ratio = amount.coerceIn(0f, 1f)
        val inverse = 1f - ratio
        return Color.rgb(
            (Color.red(from) * inverse + Color.red(to) * ratio).toInt(),
            (Color.green(from) * inverse + Color.green(to) * ratio).toInt(),
            (Color.blue(from) * inverse + Color.blue(to) * ratio).toInt(),
        )
    }

    private fun luminance(color: Int): Float =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f
}
