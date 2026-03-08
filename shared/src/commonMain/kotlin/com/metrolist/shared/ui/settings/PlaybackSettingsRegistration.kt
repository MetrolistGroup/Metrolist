/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@file:Suppress("FunctionName", "ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.KeepScreenOnMode
import com.metrolist.shared.settings.LoudnessLevel
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.formatPlaybackPitch
import com.metrolist.shared.utils.formatPlaybackSpeed
import com.metrolist.shared.utils.playbackPitchToSemitones
import com.metrolist.shared.utils.semitonesToPlaybackPitch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun SettingsUiRegistryBuilder.registerPlaybackSettings(host: SharedSettingsHost) {
    val settings = Settings.shared
    val audioQualityChoices =
        listOf(
            SettingsChoice("LOW", stringResource(Res.string.audio_quality_low)),
            SettingsChoice("AUTO", stringResource(Res.string.audio_quality_auto)),
            SettingsChoice("HIGH", stringResource(Res.string.audio_quality_high)),
        )
    val loudnessLevelChoices =
        LoudnessLevel.entries.map { level ->
            SettingsChoice(
                level.name,
                stringResource(
                    when (level) {
                        LoudnessLevel.AGGRESSIVE -> Res.string.loudness_level_aggressive
                        LoudnessLevel.LOUD -> Res.string.loudness_level_loud
                        LoudnessLevel.BALANCED -> Res.string.loudness_level_balanced
                        LoudnessLevel.QUIET -> Res.string.loudness_level_quiet
                    },
                ),
            )
        }
    page(
        id = SettingsPageId.PLAYER,
        title = stringResource(Res.string.player_settings_title),
        subtitle = stringResource(Res.string.settings_player_audio_description),
        icon = MetrolistIcons.Outlined.PlayArrow,
        tone = SettingsTone.ROSE,
    ) {
        if (host.platform != SettingsPlatform.IOS) {
            group("cast", stringResource(Res.string.settings_cast_title)) {
                switch(
                    id = "castEnabled",
                    title = stringResource(Res.string.settings_cast_title),
                    description = stringResource(Res.string.cast_description),
                    checked = settings.castEnabled,
                ) {
                    settings.castEnabled = it
                }
            }
        }
        group("audio", stringResource(Res.string.general)) {
            choice(
                id = "audioQuality",
                title = stringResource(Res.string.audio_quality),
                valueLabel = audioQualityChoices.firstOrNull { it.id == settings.audioQuality }?.label,
                choices = audioQualityChoices,
                selectedChoiceId = settings.audioQuality,
                description = stringResource(Res.string.settings_audio_quality_summary),
            ) {
                settings.audioQuality = it
                host.onSettingChanged(SettingsChangeEffect.AUDIO, null)
            }
            if (host.platform != SettingsPlatform.IOS) {
                switch(
                    id = "sponsorBlockEnabled",
                    title = stringResource(Res.string.sponsorblock_podcasts),
                    description = stringResource(Res.string.sponsorblock_podcasts_description),
                    checked = settings.sponsorBlockEnabled,
                ) { settings.sponsorBlockEnabled = it }
            }
            switch(
                id = "skipSilence",
                title = stringResource(Res.string.skip_silence),
                description = stringResource(Res.string.skip_silence_desc),
                checked = settings.skipSilence,
            ) {
                settings.skipSilence = it
                host.onSettingChanged(SettingsChangeEffect.AUDIO, null)
            }
            if (host.platform != SettingsPlatform.IOS) {
                switch(
                    id = "skipSilenceInstant",
                    title = stringResource(Res.string.skip_silence_instant),
                    description =
                        if (settings.skipSilence) {
                            stringResource(Res.string.skip_silence_instant_desc)
                        } else {
                            stringResource(Res.string.settings_requires_enabled, stringResource(Res.string.skip_silence))
                        },
                    checked = settings.skipSilenceInstant,
                    enabled = settings.skipSilence,
                ) {
                    settings.skipSilenceInstant = it
                    host.onSettingChanged(SettingsChangeEffect.AUDIO, null)
                }
            }
            switch(
                id = "keepPausedOnSongChange",
                title = stringResource(Res.string.keep_paused_on_song_change),
                description = stringResource(Res.string.keep_paused_on_song_change_description),
                checked = settings.keepPausedOnSongChange,
            ) { settings.keepPausedOnSongChange = it }
            switch(
                id = "autoSkipOnError",
                title = stringResource(Res.string.auto_skip_next_on_error),
                description = stringResource(Res.string.auto_skip_next_on_error_desc),
                checked = settings.autoSkipNextOnError,
            ) { settings.autoSkipNextOnError = it }
            inlineSlider(
                id = "seekDuration",
                title = stringResource(Res.string.seek_duration),
                description =
                    stringResource(
                        when (host.platform) {
                            SettingsPlatform.DESKTOP -> Res.string.seek_duration_description
                            SettingsPlatform.ANDROID -> Res.string.seek_duration_description_android
                            SettingsPlatform.IOS -> Res.string.seek_duration_description_skip_controls
                        },
                    ),
                valueLabel = stringResource(Res.string.settings_seconds_value, settings.seekDurationSeconds),
                value = settings.seekDurationSeconds.toFloat(),
                defaultValue = settings.defaultValue(settings::seekDurationSeconds).toFloat(),
                range = 5f..30f,
                steps = 4,
            ) { settings.seekDurationSeconds = it.roundToInt() }
            if (host.platform != SettingsPlatform.DESKTOP) {
                switch(
                    id = "seekExtraSeconds",
                    title = stringResource(Res.string.seek_seconds_addup),
                    description = stringResource(Res.string.seek_seconds_addup_desc),
                    checked = settings.seekExtraSeconds,
                ) { settings.seekExtraSeconds = it }
            }
            switch(
                id = "varispeed",
                title = stringResource(Res.string.varispeed),
                description = stringResource(Res.string.varispeed_description),
                checked = settings.varispeed,
            ) { enabled ->
                settings.varispeed = enabled
                if (enabled) {
                    settings.playbackPitch = settings.playbackSpeed
                    host.onFloatSettingChanged(SettingsChangeEffect.PLAYBACK_PITCH, settings.playbackPitch)
                }
            }
            if (host.platform == SettingsPlatform.ANDROID) {
                switch(
                    id = "audioTrackPlaybackParams",
                    title = stringResource(Res.string.audio_track_playback_params),
                    description = stringResource(Res.string.audio_track_playback_params_description),
                    checked = settings.audioTrackPlaybackParams,
                ) { settings.audioTrackPlaybackParams = it }
            }
            if (host.platform == SettingsPlatform.DESKTOP) {
                val playbackSpeed = host.playbackSpeedOverride ?: settings.playbackSpeed
                inlineSlider(
                    id = "playbackSpeed",
                    title = stringResource(Res.string.playback_speed),
                    description = host.playbackSpeedDescription,
                    valueLabel = stringResource(Res.string.playback_speed_value, formatPlaybackSpeed(playbackSpeed)),
                    value = playbackSpeed.coerceIn(0.5f, 2f),
                    defaultValue = settings.defaultValue(settings::playbackSpeed),
                    range = 0.5f..2f,
                    steps = 29,
                    enabled = host.playbackSpeedEnabled,
                ) { value ->
                    val rounded = (value * 20).roundToInt() / 20f
                    settings.playbackSpeed = rounded
                    host.onFloatSettingChanged(SettingsChangeEffect.PLAYBACK_SPEED, rounded)
                    if (settings.varispeed) {
                        settings.playbackPitch = rounded
                        host.onFloatSettingChanged(SettingsChangeEffect.PLAYBACK_PITCH, rounded)
                    }
                }
                if (!settings.varispeed) {
                    val playbackPitchSemitones = playbackPitchToSemitones(settings.playbackPitch)
                    inlineSlider(
                        id = "playbackPitch",
                        title = stringResource(Res.string.playback_pitch),
                        valueLabel = formatPlaybackPitch(playbackPitchSemitones),
                        value = playbackPitchSemitones,
                        defaultValue = playbackPitchToSemitones(settings.defaultValue(settings::playbackPitch)),
                        range = -12f..12f,
                        steps = 479,
                    ) { value ->
                        val semitones = (value * 20).roundToInt() / 20f
                        settings.playbackPitch = semitonesToPlaybackPitch(semitones)
                        host.onFloatSettingChanged(SettingsChangeEffect.PLAYBACK_PITCH, settings.playbackPitch)
                    }
                }
            }
        }
        group("sound", stringResource(Res.string.settings_sound)) {
            switch(
                "audioNormalization",
                stringResource(Res.string.audio_normalization),
                checked = settings.audioNormalization,
                description = stringResource(Res.string.settings_normalization_summary),
            ) {
                settings.audioNormalization = it
                host.onSettingChanged(SettingsChangeEffect.AUDIO, null)
            }
            choice(
                id = "loudnessLevel",
                title = stringResource(Res.string.loudness_level),
                valueLabel = loudnessLevelChoices.first { it.id == settings.loudnessLevel.name }.label,
                choices = loudnessLevelChoices,
                selectedChoiceId = settings.loudnessLevel.name,
                enabled = settings.audioNormalization,
                description = stringResource(Res.string.settings_loudness_target_summary),
            ) {
                settings.loudnessLevel = LoudnessLevel.valueOf(it)
                host.onSettingChanged(SettingsChangeEffect.AUDIO, null)
            }
            if (SettingsPageId.EQUALIZER in host.availablePages) {
                navigation(
                    "equalizer",
                    stringResource(Res.string.metroeq),
                    SettingsPageId.EQUALIZER,
                    description = stringResource(Res.string.settings_equalizer_overview),
                )
            }
            switch(
                id = "crossfadeEnabled",
                title = stringResource(Res.string.crossfade),
                description = stringResource(Res.string.track_transition_fade_desc),
                checked = settings.crossfadeEnabled,
            ) {
                settings.crossfadeEnabled = it
                host.onSettingChanged(SettingsChangeEffect.AUDIO, null)
            }
            inlineSlider(
                id = "crossfadeDuration",
                title = stringResource(Res.string.crossfade_duration),
                valueLabel =
                    pluralStringResource(
                        Res.plurals.seconds,
                        settings.crossfadeDuration.roundToInt(),
                        settings.crossfadeDuration.roundToInt(),
                    ),
                value = settings.crossfadeDuration,
                defaultValue = settings.defaultValue(settings::crossfadeDuration),
                range = 1f..15f,
                steps = 13,
                enabled = settings.crossfadeEnabled,
            ) {
                settings.crossfadeDuration = it.roundToInt().toFloat()
                host.onSettingChanged(SettingsChangeEffect.AUDIO, null)
            }
            switch(
                id = "crossfadeGapless",
                title = stringResource(Res.string.crossfade_gapless),
                description = stringResource(Res.string.crossfade_gapless_desc),
                checked = settings.crossfadeGapless,
                enabled = settings.crossfadeEnabled,
            ) {
                settings.crossfadeGapless = it
                host.onSettingChanged(SettingsChangeEffect.AUDIO, null)
            }
        }
        group("queue", stringResource(Res.string.queue)) {
            switch(
                id = "legacyShuffle",
                title = stringResource(Res.string.legacy_shuffle),
                description = stringResource(Res.string.legacy_shuffle_desc),
                checked = settings.useLegacyShuffle,
            ) { settings.useLegacyShuffle = it }
            switch(
                id = "persistentQueue",
                title = stringResource(Res.string.persistent_queue),
                description = stringResource(Res.string.persistent_queue_desc),
                checked = settings.persistentQueue,
            ) { settings.persistentQueue = it }
            if (settings.persistentQueue) {
                switch(
                    id = "promptContinuePlayback",
                    title = stringResource(Res.string.prompt_continue_playback),
                    description = stringResource(Res.string.settings_resume_prompt_summary),
                    checked = settings.promptContinuePlayback,
                ) { settings.promptContinuePlayback = it }
            }
            switch(
                id = "persistentShuffle",
                title = stringResource(Res.string.persistent_shuffle_title),
                description = stringResource(Res.string.persistent_shuffle_desc),
                checked = settings.persistentShuffleAcrossQueues,
            ) { settings.persistentShuffleAcrossQueues = it }
            switch(
                id = "preventDuplicates",
                title = stringResource(Res.string.prevent_duplicate_tracks_in_queue),
                description = stringResource(Res.string.prevent_duplicate_tracks_in_queue_desc),
                checked = settings.preventDuplicateTracksInQueue,
            ) { settings.preventDuplicateTracksInQueue = it }
            switch(
                id = "autoLoadMore",
                title = stringResource(Res.string.auto_load_more),
                description = stringResource(Res.string.auto_load_more_desc),
                checked = settings.autoLoadMore,
            ) { settings.autoLoadMore = it }
            switch(
                id = "rememberShuffleRepeat",
                title = stringResource(Res.string.remember_shuffle_and_repeat),
                description = stringResource(Res.string.remember_shuffle_and_repeat_desc),
                checked = settings.rememberShuffleAndRepeat,
            ) { settings.rememberShuffleAndRepeat = it }
            switch(
                id = "autoDownloadOnLike",
                title = stringResource(Res.string.auto_download_on_like),
                checked = settings.autoDownloadOnLike,
            ) { enabled ->
                if (host.platform == SettingsPlatform.ANDROID) {
                    host.onSettingChanged(SettingsChangeEffect.AUTO_DOWNLOAD_ON_LIKE, enabled)
                } else {
                    settings.autoDownloadOnLike = enabled
                }
            }
        }
        if (host.platform != SettingsPlatform.DESKTOP) {
            group("device", stringResource(Res.string.appearance_misc)) {
                if (host.platform == SettingsPlatform.ANDROID) {
                    switch(
                        "stopOnTaskClear",
                        stringResource(Res.string.stop_music_on_task_clear),
                        checked = settings.stopMusicOnTaskClear,
                        description = stringResource(Res.string.settings_stop_on_close_summary),
                    ) {
                        settings.stopMusicOnTaskClear = it
                    }
                }
                switch(
                    id = "pauseOnMute",
                    title = stringResource(Res.string.pause_music_when_media_is_muted),
                    description = stringResource(Res.string.pause_music_when_media_is_muted_desc),
                    checked = settings.pauseOnMute,
                ) { settings.pauseOnMute = it }
                val choices =
                    listOf(
                        SettingsChoice(KeepScreenOnMode.NEVER.name, stringResource(Res.string.none)),
                        SettingsChoice(KeepScreenOnMode.PLAYER.name, stringResource(Res.string.appearance_player)),
                        SettingsChoice(KeepScreenOnMode.LYRICS.name, stringResource(Res.string.lyrics)),
                        SettingsChoice(
                            KeepScreenOnMode.PLAYER_AND_LYRICS.name,
                            stringResource(
                                Res.string.keep_screen_on_both,
                                stringResource(Res.string.lyrics),
                                stringResource(Res.string.appearance_player),
                            ),
                        ),
                    )
                choice(
                    id = "keepScreenOnMode",
                    title = stringResource(Res.string.keep_screen_on),
                    valueLabel = choices.first { it.id == settings.keepScreenOnMode.name }.label,
                    choices = choices,
                    selectedChoiceId = settings.keepScreenOnMode.name,
                    description = stringResource(Res.string.settings_screen_awake_summary),
                ) { settings.keepScreenOnMode = KeepScreenOnMode.valueOf(it) }
            }
        }
    }
}
