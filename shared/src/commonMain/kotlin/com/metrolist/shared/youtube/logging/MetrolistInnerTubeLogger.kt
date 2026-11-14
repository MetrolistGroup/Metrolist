package com.metrolist.shared.youtube.logging

import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLevel
import com.metrolist.shared.youtube.playback.PlaybackEventLog

object MetrolistInnerTubeLogger : InnerTubeLogger {
    override fun log(event: InnerTubeLogEvent) {
        if (event.tag.contains("Sabr", ignoreCase = true)) {
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> {
                    PlaybackEventLog.debug(
                        PlaybackEventKind.SABR,
                        event.message,
                        event.mediaId,
                        details = event.details,
                    )
                }

                InnerTubeLogLevel.INFO -> {
                    PlaybackEventLog.info(
                        PlaybackEventKind.SABR,
                        event.message,
                        event.mediaId,
                        details = event.details,
                    )
                }

                InnerTubeLogLevel.WARN -> {
                    PlaybackEventLog.warn(
                        PlaybackEventKind.SABR,
                        event.message,
                        event.mediaId,
                        details = event.details,
                    )
                }

                InnerTubeLogLevel.ERROR -> {
                    PlaybackEventLog.error(
                        PlaybackEventKind.SABR,
                        event.message,
                        event.mediaId,
                        details = event.details,
                    )
                }
            }
            return
        }

        // Keep InnerTubeX details (client, failure type, token timing): failure reports summarise them.
        PlaybackEventLog.captureYtLogger(
            level =
                when (event.level) {
                    InnerTubeLogLevel.DEBUG -> PlaybackEventLevel.DEBUG
                    InnerTubeLogLevel.INFO -> PlaybackEventLevel.INFO
                    InnerTubeLogLevel.WARN -> PlaybackEventLevel.WARN
                    InnerTubeLogLevel.ERROR -> PlaybackEventLevel.ERROR
                },
            tag = event.tag,
            message = event.message,
            videoId = event.mediaId,
            details = event.details,
        )
    }
}
