package com.metrolist.shared.ui.mobile

import com.metrolist.shared.listentogether.parseListenTogetherInvitation
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.settings.SettingsPageId
import io.ktor.http.URLProtocol
import io.ktor.http.Url

internal sealed interface MobileRoute {
    data object Home : MobileRoute

    data object Search : MobileRoute

    data object Library : MobileRoute

    data object EasterEgg : MobileRoute

    data class Settings(
        val initialPage: SettingsPageId? = null,
    ) : MobileRoute

    data object History : MobileRoute

    data object Statistics : MobileRoute

    data object Integrations : MobileRoute

    data class ListenTogether(
        val initialRoomCode: String? = null,
    ) : MobileRoute

    data class Login(
        val isSwitchingChannel: Boolean,
    ) : MobileRoute

    data object TokenLogin : MobileRoute

    data object About : MobileRoute

    data class Album(
        val id: String,
        val title: String,
    ) : MobileRoute

    data class Artist(
        val id: String,
        val name: String,
    ) : MobileRoute

    data class AboutArtist(
        val artist: ArtistItem,
        val catalogTitles: List<String>,
    ) : MobileRoute

    data class Playlist(
        val id: String,
        val title: String,
    ) : MobileRoute

    data class Podcast(
        val id: String,
        val title: String = "",
    ) : MobileRoute

    data class Browse(
        val destination: MobileBrowseDestination,
    ) : MobileRoute

    data class LocalShelf(
        val key: String,
        val section: HomeSection,
    ) : MobileRoute

    data class SongCollection(
        val type: String,
        val title: String,
        val localPlaylistId: String? = null,
    ) : MobileRoute
}

internal sealed interface MobileDeepLink {
    data class Tab(
        val route: MobileRoute,
        val query: String? = null,
    ) : MobileDeepLink

    data class Destination(
        val route: MobileRoute,
    ) : MobileDeepLink

    data class Video(
        val id: String,
    ) : MobileDeepLink

    data object Player : MobileDeepLink
}

internal fun routeForMobileItem(item: YTItem): MobileRoute? =
    when (item) {
        is PodcastItem -> {
            MobileRoute.Podcast(item.id, item.title)
        }

        is PlaylistItem -> {
            val builtin = BuiltinPlaylist.fromId(item.id)
            when {
                builtin == BuiltinPlaylist.HISTORY -> MobileRoute.History
                builtin?.collectionType != null -> MobileRoute.SongCollection(builtin.collectionType, item.title)
                item.id.startsWith("MPSP") -> MobileRoute.Podcast(item.id, item.title)
                item.isLocal -> MobileRoute.SongCollection("local", item.title, item.id)
                item.id.isLikedMusicPlaylistId() -> MobileRoute.SongCollection("liked", item.title)
                else -> MobileRoute.Playlist(item.id, item.title)
            }
        }

        is AlbumItem -> {
            MobileRoute.Album(item.id, item.title)
        }

        is ArtistItem -> {
            CollectionPreviews.record(item)
            MobileRoute.Artist(item.id, item.title)
        }

        else -> {
            item.mobileBrowseDestination()?.let { destination ->
                when {
                    destination.id.startsWith("MPREb_") -> {
                        MobileRoute.Album(destination.id, destination.title)
                    }

                    destination.id.startsWith("UC") || destination.id.startsWith("FEmusic_library_privately_owned_artist") -> {
                        MobileRoute.Artist(destination.id, destination.title)
                    }

                    destination.id.startsWith("MPSP") -> {
                        MobileRoute.Podcast(destination.id, destination.title)
                    }

                    destination.id.startsWith("VL") || destination.id.startsWith("PL") -> {
                        MobileRoute.Playlist(destination.id, destination.title)
                    }

                    else -> {
                        MobileRoute.Browse(destination)
                    }
                }
            }
        }
    }

internal fun String.isLikedMusicPlaylistId(): Boolean = this == "LM" || this == "VLLM" || startsWith("LM:")

private val youtubeVideoId = Regex("^[A-Za-z0-9_-]{11}$")
private val listenTogetherRoomCode = Regex("^[A-Za-z0-9]{8}$")
private val partyCodePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

private fun listenTogetherInvitation(code: String?): MobileDeepLink? =
    code?.takeIf(listenTogetherRoomCode::matches)?.let {
        MobileDeepLink.Destination(MobileRoute.ListenTogether(it.uppercase()))
    }

private fun partyInvitation(code: String?): MobileDeepLink? =
    code?.takeIf(partyCodePattern::matches)?.let {
        MobileDeepLink.Destination(MobileRoute.ListenTogether(it.uppercase()))
    }

internal fun resolveMobileDeepLink(callback: String): MobileDeepLink? {
    parseListenTogetherInvitation(callback)?.let { return MobileDeepLink.Destination(MobileRoute.ListenTogether(it)) }
    val uri = runCatching { Url(callback.replace("metrolist://", "https://app.metrolist/")) }.getOrNull()
    val path = uri?.encodedPath?.trim('/') ?: callback.removePrefix("metrolist://").substringBefore('?').trim('/')
    if (!callback.startsWith("metrolist://")) {
        val host = uri?.host?.lowercase()?.removeSuffix(".")
        val isMetrolistHost =
            uri?.protocol == URLProtocol.HTTPS && (host == "metrolist.cc" || host?.endsWith(".metrolist.cc") == true)
        return if (isMetrolistHost) {
            when (path) {
                "listen" -> listenTogetherInvitation(uri.parameters["code"])
                "party" -> partyInvitation(uri.parameters["r"] ?: uri.parameters["code"])
                "watch" -> uri.parameters["v"]?.takeIf(String::isNotBlank)?.let { MobileDeepLink.Video(it) }
                else -> null
            }
        } else {
            null
        }
    }
    return when (path) {
        "watch" -> {
            val videoId = uri?.parameters?.get("v") ?: path.removePrefix("watch/").substringBefore('/')
            videoId.takeIf(String::isNotBlank)?.let { MobileDeepLink.Video(it) }
        }

        "party" -> {
            val code = uri?.parameters?.get("r") ?: uri?.parameters?.get("code") ?: path.removePrefix("party/").substringBefore('/')
            partyInvitation(code)
        }

        "home" -> {
            MobileDeepLink.Tab(MobileRoute.Home)
        }

        "library" -> {
            MobileDeepLink.Tab(MobileRoute.Library)
        }

        "search" -> {
            MobileDeepLink.Tab(
                MobileRoute.Search,
                uri
                    ?.parameters
                    ?.get("q")
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
            )
        }

        "player" -> {
            MobileDeepLink.Player
        }

        "history" -> {
            MobileDeepLink.Destination(MobileRoute.History)
        }

        "statistics" -> {
            MobileDeepLink.Destination(MobileRoute.Statistics)
        }

        "integrations" -> {
            MobileDeepLink.Destination(MobileRoute.Integrations)
        }

        "listen-together" -> {
            MobileDeepLink.Destination(MobileRoute.ListenTogether())
        }

        "settings" -> {
            MobileDeepLink.Destination(MobileRoute.Settings())
        }

        "about" -> {
            MobileDeepLink.Destination(MobileRoute.About)
        }

        "login" -> {
            MobileDeepLink.Destination(MobileRoute.Login(isSwitchingChannel = false))
        }

        "token-login" -> {
            MobileDeepLink.Destination(MobileRoute.TokenLogin)
        }

        else -> {
            fun id(
                prefix: String,
                parameter: String = "id",
            ): String? =
                uri
                    ?.parameters
                    ?.get(parameter)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: path
                        .takeIf { it.startsWith(prefix) }
                        ?.removePrefix(prefix)
                        ?.substringBefore('/')
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
            when {
                path.startsWith("party/") || path == "party" -> {
                    val code = uri?.parameters?.get("r") ?: uri?.parameters?.get("code") ?: path.removePrefix("party/").substringBefore('/')
                    partyInvitation(code)
                }

                path.startsWith("watch/") || path == "watch" -> {
                    val videoId = uri?.parameters?.get("v") ?: path.removePrefix("watch/").substringBefore('/')
                    videoId.takeIf(String::isNotBlank)?.let { MobileDeepLink.Video(it) }
                }

                path.startsWith("listen-together/") -> {
                    listenTogetherInvitation(path.removePrefix("listen-together/"))
                }

                path.startsWith("playlist/") || path == "playlist" -> {
                    id("playlist/")?.let { MobileDeepLink.Destination(MobileRoute.Playlist(it, "")) }
                }

                path.startsWith("album/") || path == "album" -> {
                    id("album/")?.let { MobileDeepLink.Destination(MobileRoute.Album(it, "")) }
                }

                path.startsWith("artist/") || path == "artist" -> {
                    id("artist/")?.let { MobileDeepLink.Destination(MobileRoute.Artist(it, "")) }
                }

                path.startsWith("podcast/") || path == "podcast" -> {
                    id("podcast/")?.let { MobileDeepLink.Destination(MobileRoute.Podcast(it, "")) }
                }

                path.startsWith("auto-playlist/") || path == "auto-playlist" -> {
                    id("auto-playlist/", "type")?.let { MobileDeepLink.Destination(MobileRoute.SongCollection(it, "")) }
                }

                path.startsWith("browse/") || path == "browse" -> {
                    id("browse/")?.let {
                        MobileDeepLink.Destination(MobileRoute.Browse(MobileBrowseDestination(id = it, params = null, title = "")))
                    }
                }

                youtubeVideoId.matches(path) -> {
                    MobileDeepLink.Video(path)
                }

                else -> {
                    null
                }
            }
        }
    }
}

internal val MobileRoute.motionScreen: Screen
    get() =
        when (this) {
            MobileRoute.Home -> Screen.Home
            MobileRoute.Search -> Screen.Search
            MobileRoute.Library -> Screen.Library
            MobileRoute.EasterEgg -> Screen.EasterEgg
            is MobileRoute.Settings -> Screen.Settings
            MobileRoute.History -> Screen.History
            MobileRoute.Statistics -> Screen.Statistics
            MobileRoute.Integrations -> Screen.Integrations
            is MobileRoute.ListenTogether -> Screen.ListenTogether
            is MobileRoute.Login -> if (isSwitchingChannel) Screen.SwitchChannel else Screen.Login
            MobileRoute.TokenLogin -> Screen.TokenLogin
            MobileRoute.About -> Screen.About
            is MobileRoute.Album -> Screen.Album(id)
            is MobileRoute.Artist -> Screen.Artist(id)
            is MobileRoute.AboutArtist -> Screen.AboutArtist(artist.id, artist.title, artist)
            is MobileRoute.Playlist -> Screen.Playlist(id)
            is MobileRoute.Podcast -> Screen.Podcast(id)
            is MobileRoute.Browse -> Screen.Browse(destination.id, destination.params, destination.title)
            is MobileRoute.LocalShelf -> Screen.HomeSection(key, section.title, section)
            is MobileRoute.SongCollection -> Screen.AutoPlaylist(type)
        }
