package com.metrolist.shared.discord

fun resolveDiscordButtonLabel(
    configured: String,
    default: String,
    localized: String,
): String = if (configured.isEmpty() || configured == default) localized else configured

object DiscordDefaults {
    const val YOUTUBE_WATCH_URL = "https://music.youtube.com/watch?v="

    const val BUTTON1_LABEL = "Listen on YouTube Music"
    const val BUTTON1_URL_TEMPLATE = "{song.url}"

    const val BUTTON2_LABEL = "Visit Metrolist"
    const val BUTTON2_URL = "https://github.com/MetrolistGroup/Metrolist"

    const val STATE_TEMPLATE = "{artist.name}"
    const val DETAILS_TEMPLATE = "{song.name}"

    const val DISCORD_OAUTH_AUTHORIZE = "https://discord.com/oauth2/authorize"
    const val DISCORD_OAUTH_TOKEN = "https://discord.com/api/v10/oauth2/token"
    const val DISCORD_REDIRECT_URI = "metrolist-auth://discord/callback"

    const val DISCORD_SCOPES = "openid sdk.social_layer_presence"
}
