package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.shared.settings.Settings

object MetrolistPlayerConfigRepository : PlayerConfigRepository {
    override val enabled: Boolean = false

    override val sourceUrl: String
        get() = Settings.shared.githubNTransformConfigUrl

    override val defaultSourceUrl: String
        get() = Settings.DEFAULT_FARADAY_PLAYER_CONFIG_URL

    override var cachedJson: String
        get() = Settings.shared.playerConfigRemoteJson
        set(value) {
            Settings.shared.playerConfigRemoteJson = value
        }

    override var cachedAtMs: Long
        get() = Settings.shared.playerConfigRemoteFetchedAt
        set(value) {
            Settings.shared.playerConfigRemoteFetchedAt = value
        }

    override var cachedSourceUrl: String
        get() = Settings.shared.playerConfigRemoteSourceUrl
        set(value) {
            Settings.shared.playerConfigRemoteSourceUrl = value
        }

    override var cachedEtag: String
        get() = Settings.shared.playerConfigRemoteEtag
        set(value) {
            Settings.shared.playerConfigRemoteEtag = value
        }
}
