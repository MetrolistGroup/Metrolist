package com.metrolist.shared.settings

import com.metrolist.shared.constants.DiscordPendingAuthSessionKey
import com.metrolist.shared.constants.DriveBrokerSessionKey
import com.metrolist.shared.constants.DriveOAuthStateKey
import com.metrolist.shared.constants.DriveOAuthVerifierKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthSessionKey

internal val secureSettingsKeys =
    setOf(
        DiscordPendingAuthSessionKey,
        DriveBrokerSessionKey,
        DriveOAuthStateKey,
        DriveOAuthVerifierKey,
        "innerTubeCookie",
        "visitorData",
        "dataSyncId",
        "innerTubeAuthUser",
        "lastFmSession",
        "listenBrainzToken",
        "lyricsAiApiKey",
        "lyricsAiApiKeys",
        LyricsOpenRouterOAuthKey,
        LyricsOpenRouterOAuthSessionKey,
        "lyricsDeepLApiKey",
        "accountName",
        "accountEmail",
        "accountChannelHandle",
        "accountThumbnail",
        "discordAccessToken",
        "discordRefreshToken",
        "listenTogetherDesktopSession",
        "listenTogetherServerUrl",
        "listenTogetherCustomServers",
        "listenTogetherServerMetadata",
        "deviceConnectPairedDevices",
        "listenTogetherPairedRoomCode",
        "spotifySession",
    )
