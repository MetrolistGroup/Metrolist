package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.browseId
import com.metrolist.shared.api.YouTubeResponseParser.pageTitle
import com.metrolist.shared.api.YouTubeResponseParser.parseBrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.runText
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object YouTubePodcastParser {
    suspend fun loadContinuations(
        initial: PodcastPage,
        savedEpisodes: List<EpisodeItem>,
        load: suspend (String) -> BrowseResponse,
    ): PodcastPage {
        val episodes = mutableListOf<EpisodeItem>()
        var last = initial
        pages(initial, savedEpisodes, load)
            .catch { error ->
                if (error is CancellationException || error !is Exception) throw error
            }.collect { page ->
                episodes.addAll(page.episodes)
                last = page
            }
        return last.copy(episodes = episodes.toList())
    }

    fun pages(
        initial: PodcastPage,
        savedEpisodes: List<EpisodeItem>,
        load: suspend (String) -> BrowseResponse,
    ): Flow<PodcastPage> =
        flow {
            val savedById = savedEpisodes.associateBy { it.id }
            val author = initial.podcast.author?.let { ArtistTiny(initial.podcast.channelId, it, initial.podcast.thumbnail) }
            val seenTokens = initial.consumedContinuations.toMutableSet()
            val pending = initial.continuations.mapNotNull { it.normalizedContinuation() }.toMutableSet()
            pending.removeAll(seenTokens)
            val seenEpisodes = initial.episodes.mapTo(mutableSetOf()) { it.id }
            var page = initial
            emit(page)
            while (true) {
                val token = pending.firstOrNull() ?: break
                val response = load(token)
                page =
                    withContext(responseDecodeDispatcher) {
                        val parsed = parseBrowseResponse(response)
                        val episodes =
                            buildList {
                                for (items in parsed.sections.values) {
                                    for (item in items) {
                                        val episode =
                                            when (item) {
                                                is EpisodeItem -> item.copy(author = item.author ?: author, podcastId = initial.podcast.id)
                                                is SongItem -> item.toEpisodeItem(author = author, podcastId = initial.podcast.id)
                                                else -> continue
                                            }
                                        if (!seenEpisodes.add(episode.id)) continue
                                        add(
                                            savedById[episode.id]?.let {
                                                episode.copy(
                                                    saved = true,
                                                    playlistSetVideoId = it.playlistSetVideoId,
                                                )
                                            }
                                                ?: episode,
                                        )
                                    }
                                }
                            }
                        seenTokens.add(token)
                        pending.remove(token)
                        parsed.continuationBranches.forEach { branch ->
                            branch.token
                                .normalizedContinuation()
                                ?.takeUnless { it in seenTokens }
                                ?.let(pending::add)
                        }
                        initial.copy(
                            podcast = initial.podcast.copy(episodeCount = seenEpisodes.size.toString()),
                            episodes = episodes,
                            continuations = pending.toList(),
                            consumedContinuations = seenTokens.toSet(),
                        )
                    }
                emit(page)
            }
        }

    fun parsePodcast(
        response: BrowseResponse,
        podcastId: String,
        savedEpisodes: List<EpisodeItem>,
    ): PodcastPage {
        val parsed = parseBrowseResponse(response)
        val header = response.header
        val responsiveHeader =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.get("tabs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("tabRenderer")
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject
                ?.get("contents")
                ?.jsonArray
                ?.firstNotNullOfOrNull { section ->
                    section.jsonObject["musicResponsiveHeaderRenderer"]?.jsonObject
                }
        val headerCandidates =
            listOfNotNull(
                responsiveHeader,
                header?.musicResponsiveHeaderRenderer,
                header?.musicDetailHeaderRenderer,
                header?.musicVisualHeaderRenderer,
                header?.musicImmersiveHeaderRenderer,
                header?.musicHeaderRenderer,
            )
        val headerObj = headerCandidates.firstOrNull()

        val title =
            headerObj?.get("title")?.jsonObject.runText()
                ?: response.pageTitle()
                ?: parsed.sections.values
                    .flatten()
                    .filterIsInstance<PodcastItem>()
                    .firstOrNull()
                    ?.title
                ?: "Podcast"
        val thumbnail =
            headerCandidates
                .firstNotNullOfOrNull { candidate ->
                    listOf("thumbnail", "straplineThumbnail").firstNotNullOfOrNull { field ->
                        candidate[field]
                            ?.jsonObject
                            ?.let { thumb ->
                                thumb["musicThumbnailRenderer"]?.jsonObject
                                    ?: thumb["croppedSquareThumbnailRenderer"]?.jsonObject
                            }?.get("thumbnail")
                            ?.jsonObject
                            ?.get("thumbnails")
                            ?.jsonArray
                            ?.lastOrNull()
                            ?.jsonObject
                            ?.get("url")
                            ?.jsonPrimitive
                            ?.contentOrNull
                    }
                }?.replace(Regex("=w\\d+-h\\d+.*$"), "=w1000-h1000")
        val authorRun =
            headerCandidates.firstNotNullOfOrNull { candidate ->
                candidate["straplineTextOne"]
                    ?.jsonObject
                    ?.get("runs")
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
            }
        val podcastAuthor =
            authorRun
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { name ->
                    ArtistTiny(
                        authorRun["navigationEndpoint"]?.jsonObject.browseId(),
                        name,
                        thumbnail = thumbnail,
                    )
                }
        val author = podcastAuthor?.name
        val description =
            headerObj?.get("description")?.jsonObject.runText()
                ?: headerObj
                    ?.get("description")
                    ?.jsonObject
                    ?.get("musicDescriptionShelfRenderer")
                    ?.jsonObject
                    ?.get("description")
                    ?.jsonObject
                    .runText()
        val saved =
            headerObj
                ?.get("buttons")
                ?.jsonArray
                ?.firstNotNullOfOrNull { button ->
                    button.jsonObject["toggleButtonRenderer"]
                        ?.jsonObject
                        ?.get("isToggled")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                }
                ?: (
                    headerObj
                        ?.get("menu")
                        ?.jsonObject
                        ?.get("menuRenderer")
                        ?.jsonObject
                        ?.toString()
                        ?.contains("MUSIC_LIBRARY_STATUS_IN_LIBRARY") == true
                )

        val savedEpisodesById = savedEpisodes.associateBy { it.id }
        val episodes =
            parsed.sections.values
                .flatten()
                .mapNotNull { item ->
                    when (item) {
                        is EpisodeItem -> item.copy(author = item.author ?: podcastAuthor, podcastId = podcastId)
                        is SongItem -> item.toEpisodeItem(author = podcastAuthor, podcastId = podcastId)
                        else -> null
                    }
                }.distinctBy { it.id }
                .map { episode ->
                    savedEpisodesById[episode.id]?.let { savedEpisode ->
                        episode.copy(saved = true, playlistSetVideoId = savedEpisode.playlistSetVideoId)
                    } ?: episode
                }

        return PodcastPage(
            podcast =
                PodcastItem(
                    podcastId,
                    title,
                    thumbnail,
                    author = author,
                    channelId = podcastAuthor?.id?.takeIf { it.startsWith("UC") },
                    episodeCount = episodes.size.takeIf { it > 0 }?.toString(),
                ),
            episodes = episodes,
            description = description,
            saved = saved,
            continuations = parsed.continuationBranches.map { it.token },
        )
    }
}

internal fun SongItem.toEpisodeItem(
    author: ArtistTiny? = null,
    podcastId: String? = null,
): EpisodeItem =
    EpisodeItem(
        id = id,
        title = title,
        thumbnail = thumbnail,
        author = artists.firstOrNull() ?: author,
        duration = duration,
        podcastId = podcastId,
        playlistSetVideoId = playlistSetVideoId,
        playbackPositionMs = playbackPositionMs,
        playbackProgressPercent = playbackProgressPercent,
        isUnavailable = isUnavailable,
    )
