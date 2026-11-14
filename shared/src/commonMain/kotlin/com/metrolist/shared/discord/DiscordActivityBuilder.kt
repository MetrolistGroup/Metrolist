package com.metrolist.shared.discord

import com.metrolist.shared.model.SongItem

object DiscordActivityBuilder {
    fun build(
        song: SongItem,
        artistName: String,
        albumName: String?,
        artistThumbnail: String?,
        songTitle: String,
        startTimestamp: Long,
        endTimestamp: Long?,
        advancedMode: Boolean,
        activityType: Int = DiscordActivity.TYPE_LISTENING,
        activityName: String? = null,
        stateTemplate: String = DiscordDefaults.STATE_TEMPLATE,
        detailsTemplate: String = DiscordDefaults.DETAILS_TEMPLATE,
        btn1Enabled: Boolean = true,
        btn1Label: String = DiscordDefaults.BUTTON1_LABEL,
        btn1Url: String = DiscordDefaults.BUTTON1_URL_TEMPLATE,
        btn2Enabled: Boolean = true,
        btn2Label: String = DiscordDefaults.BUTTON2_LABEL,
        btn2Url: String = DiscordDefaults.BUTTON2_URL,
        basicBtn1Label: String = DiscordDefaults.BUTTON1_LABEL,
        basicBtn2Label: String = DiscordDefaults.BUTTON2_LABEL,
        unknownAlbum: String = "",
    ): DiscordActivity {
        val state: String
        val details: String?
        val renderedBtn1Label: String?
        val renderedBtn1Url: String?
        val renderedBtn2Label: String?
        val renderedBtn2Url: String?

        val artistId = song.artists.firstOrNull()?.id ?: ""
        val albumId = song.album?.id ?: ""

        if (advancedMode) {
            state =
                DiscordTemplateRenderer.render(
                    stateTemplate.ifEmpty { DiscordDefaults.STATE_TEMPLATE },
                    songTitle,
                    artistName,
                    albumName,
                    song.id,
                    artistId,
                    albumId,
                    unknownAlbum,
                )
            details =
                DiscordTemplateRenderer.render(
                    detailsTemplate.ifEmpty { DiscordDefaults.DETAILS_TEMPLATE },
                    songTitle,
                    artistName,
                    albumName,
                    song.id,
                    artistId,
                    albumId,
                    unknownAlbum,
                )
            renderedBtn1Label =
                if (btn1Enabled) {
                    DiscordTemplateRenderer.render(
                        btn1Label.ifEmpty { DiscordDefaults.BUTTON1_LABEL },
                        songTitle,
                        artistName,
                        albumName,
                        song.id,
                        artistId,
                        albumId,
                        unknownAlbum,
                    )
                } else {
                    null
                }
            renderedBtn1Url =
                if (btn1Enabled) {
                    DiscordTemplateRenderer.render(
                        btn1Url.ifEmpty { DiscordDefaults.BUTTON1_URL_TEMPLATE },
                        songTitle,
                        artistName,
                        albumName,
                        song.id,
                        artistId,
                        albumId,
                        unknownAlbum,
                    )
                } else {
                    null
                }
            renderedBtn2Label =
                if (btn2Enabled) {
                    DiscordTemplateRenderer.render(
                        btn2Label.ifEmpty { DiscordDefaults.BUTTON2_LABEL },
                        songTitle,
                        artistName,
                        albumName,
                        song.id,
                        artistId,
                        albumId,
                        unknownAlbum,
                    )
                } else {
                    null
                }
            renderedBtn2Url =
                if (btn2Enabled) {
                    DiscordTemplateRenderer.render(
                        btn2Url.ifEmpty { DiscordDefaults.BUTTON2_URL },
                        songTitle,
                        artistName,
                        albumName,
                        song.id,
                        artistId,
                        albumId,
                        unknownAlbum,
                    )
                } else {
                    null
                }
        } else {
            state = artistName
            details = songTitle
            renderedBtn1Label = basicBtn1Label
            renderedBtn1Url = "${DiscordDefaults.YOUTUBE_WATCH_URL}${song.id}"
            renderedBtn2Label = basicBtn2Label
            renderedBtn2Url = DiscordDefaults.BUTTON2_URL
        }

        val renderedName =
            if (advancedMode && !activityName.isNullOrEmpty()) {
                DiscordTemplateRenderer.render(
                    activityName,
                    songTitle,
                    artistName,
                    albumName,
                    song.id,
                    artistId,
                    albumId,
                    unknownAlbum,
                )
            } else {
                activityName?.takeIf { it.isNotEmpty() } ?: songTitle
            }

        return DiscordActivity(
            activityType = activityType,
            name = renderedName,
            state = state,
            details = details,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            largeImage = song.thumbnail,
            largeText = albumName,
            smallImage = artistThumbnail,
            smallText = artistName,
            button1Label = renderedBtn1Label,
            button1Url = renderedBtn1Url,
            button2Label = renderedBtn2Label,
            button2Url = renderedBtn2Url,
        )
    }
}
