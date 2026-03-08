package com.metrolist.shared.ui.settings

private val miscGroupIds = setOf("misc", "device")

internal fun List<SettingsGroupRegistration>.prioritized(page: SettingsPageId): List<SettingsGroupRegistration> {
    val groupOrder =
        when (page) {
            SettingsPageId.APPEARANCE -> {
                listOf(
                    "theme",
                    "playerAppearance",
                    "tabs",
                    "desktopLayouts",
                    "gestures",
                    "displayDensity",
                )
            }

            SettingsPageId.PLAYER -> {
                listOf("audio", "sound", "video", "cast", "queue", "device")
            }

            SettingsPageId.LYRICS -> {
                listOf("behavior", "lyricsAdvanced", "display", "accessibility", "alignment")
            }

            SettingsPageId.CONTENT -> {
                listOf("youtubeAccount", "localization", "filtering", "homeSections", "autoPlaylists", "artistSources")
            }

            SettingsPageId.PRIVACY -> {
                listOf("history", "blocklists", "diagnostics")
            }

            SettingsPageId.PLAYER_STYLE -> {
                listOf("layout", "background", "progress")
            }

            SettingsPageId.PLAYER_ARTWORK -> {
                listOf("animatedCovers", "sources", "youtubeCanvas", "artistArtwork")
            }

            else -> {
                emptyList()
            }
        }
    return sortedWith(
        compareBy(
            { it.id in miscGroupIds },
            { groupOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
        ),
    ).map { group ->
        val itemOrder =
            when (page to group.id) {
                SettingsPageId.APPEARANCE to "theme" -> {
                    listOf(
                        "themeMode",
                        "materialYou",
                        "themeColor",
                        "dynamicTheme",
                        "paletteStyle",
                        "fonts",
                        "appIcon",
                    )
                }

                SettingsPageId.APPEARANCE to "tabs" -> {
                    listOf(
                        "defaultOpenTab",
                        "showHomeTab",
                        "showSearchTab",
                        "showLibraryTab",
                        "slimNavigation",
                        "forceBottomNavBar",
                        "voiceSearch",
                    )
                }

                SettingsPageId.PLAYER to "audio" -> {
                    listOf(
                        "audioQuality",
                        "seekDuration",
                        "playbackSpeed",
                        "sponsorBlockEnabled",
                        "skipSilence",
                        "skipSilenceInstant",
                        "autoSkipOnError",
                        "keepPausedOnSongChange",
                        "playbackPitch",
                        "varispeed",
                        "audioTrackPlaybackParams",
                    )
                }

                SettingsPageId.PLAYER to "queue" -> {
                    listOf(
                        "autoLoadMore",
                        "autoDownloadOnLike",
                        "persistentQueue",
                        "promptContinuePlayback",
                        "rememberShuffleRepeat",
                        "persistentShuffle",
                        "legacyShuffle",
                        "preventDuplicates",
                    )
                }

                SettingsPageId.CONTENT to "localization" -> {
                    listOf("appLanguage", "contentLanguage", "contentCountry")
                }

                SettingsPageId.CONTENT to "filtering" -> {
                    listOf(
                        "hideExplicit",
                        "hideVideoSongs",
                        "hideNonMusicalVideos",
                        "hidePodcasts",
                        "blockAiArtists",
                        "hideShortSongs",
                        "minimumDuration",
                        "explicitOnly",
                    )
                }

                SettingsPageId.LYRICS to "behavior" -> {
                    listOf("showLyricsOnPlayer", "lyricsAutoScroll", "lyricsClickToSeek", "hideStatusBar")
                }

                SettingsPageId.LYRICS to "display" -> {
                    listOf("lyricsTextSize", "lyricsColor", "lyricsAnimation", "lyricsLineSpacing", "subtitleTextSize")
                }

                SettingsPageId.LYRICS to "accessibility" -> {
                    listOf("lyricsActiveByRealTiming", "lyricsProgressiveBlur", "lyricsPlusLighter")
                }

                SettingsPageId.PRIVACY to "history" -> {
                    listOf(
                        "pauseListenHistory",
                        "pauseSearchHistory",
                        "hideRecentSearches",
                        "clearSearchHistory",
                        "blockScreenshots",
                        "historyPlaylists",
                        "historyDuration",
                        "queueOfflineHistory",
                    )
                }

                SettingsPageId.FONTS to "fonts" -> {
                    listOf("importFont", "resetFont")
                }

                SettingsPageId.PLAYER_STYLE to "layout" -> {
                    listOf(
                        "playerType",
                        "playerThumbnailStyle",
                        "roundedPlayButton",
                        "immersivePlayer",
                        "showPlayerCastButton",
                        "musicVideoPlaybackEnabled",
                        "showPlayerVideoButton",
                        "musicVideoQuality",
                    )
                }

                SettingsPageId.LYRICS_AI_TRANSLATION to "runtime" -> {
                    listOf(
                        "translationEnabled",
                        "targetLanguage",
                        "activationMode",
                        "translationMode",
                    )
                }

                else -> {
                    emptyList()
                }
            }
        group.copy(items = group.items.sortedBy { itemOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE })
    }
}
