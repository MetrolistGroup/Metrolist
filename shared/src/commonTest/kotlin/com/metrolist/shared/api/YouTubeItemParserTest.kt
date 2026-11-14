package com.metrolist.shared.api

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class YouTubeItemParserTest {
    @Test
    fun albumNavigationTakesPriorityOverTheThumbnailPlayAction() {
        val item =
            renderer(
                """{
                  "musicResponsiveListItemRenderer": {
                    "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                      "text": {"runs": [{"text": "Album"}]}
                    }}],
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_album"}},
                    "overlay": {"musicItemThumbnailOverlayRenderer": {"content": {
                      "musicPlayButtonRenderer": {"playNavigationEndpoint": {"watchEndpoint": {"videoId": "first-track"}}}
                    }}}
                  }
                }""",
            )

        val album = assertIs<AlbumItem>(YouTubeItemParser.parseItem(item))
        assertEquals("MPREb_album", album.id)
        assertEquals("Album", album.title)
    }

    @Test
    fun greyedSongsWithWatchEndpointsRemainPlayableWithoutDurationOrArtwork() {
        val watch = """{"watchEndpoint":{"videoId":"track"}}"""
        val title = """"title":{"runs":[{"text":"Track"}]}"""
        val flex = """"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}]"""
        for ((name, fields) in listOf(
            "musicMultiRowListItemRenderer" to """$title,"onTap":$watch""",
            "musicTwoRowItemRenderer" to """$title,"navigationEndpoint":$watch""",
            "musicResponsiveListItemRenderer" to """$flex,"navigationEndpoint":$watch""",
            "musicResponsiveListItemRenderer" to """$flex,"overlay":{"musicItemThumbnailOverlayRenderer":{"content":{
              "musicPlayButtonRenderer":{"playNavigationEndpoint":$watch}
            }}}""",
            "musicResponsiveListItemRenderer" to """"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{
              "text":{"runs":[{"text":"Track","navigationEndpoint":$watch}]}
            }}]""",
        )) {
            val song =
                assertIs<SongItem>(
                    YouTubeItemParser.parseItem(
                        renderer("""{"$name":{$fields,"musicItemRendererDisplayPolicy":"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"}}"""),
                    ),
                )
            assertFalse(song.isUnavailable, name)
            assertEquals("track", song.id)
            assertNull(song.duration)
        }
    }

    @Test
    fun ordinaryRowsAndQueueBylinesKeepAnArtistNamedTitel() {
        val item =
            renderer(
                """{
                  "title":{"runs":[{"text":"Track"}]},
                  "navigationEndpoint":{"watchEndpoint":{"videoId":"track"}},
                  "subtitle":{"runs":[{"text":"Titel • A record"}]}
                }""",
            )
        val queueItem =
            renderer(
                """{"playlistPanelVideoRenderer":{
                  "videoId":"track",
                  "title":{"runs":[{"text":"Track"}]},
                  "longBylineText":{"runs":[{"text":"Titel • A record"}]}
                }}""",
            )

        assertEquals("Titel", assertIs<SongItem>(YouTubeItemParser.parseItem(item)).artists.single().name)
        assertEquals(
            "Titel",
            YouTubeRadioParser
                .parsePlaylistPanelItem(queueItem)
                ?.artists
                ?.single()
                ?.name,
        )
    }

    @Test
    fun explicitTitleRequiresAnExplicitBadge() {
        for (badge in listOf("", "MUSIC_EXPLICIT_BADGE")) {
            val song =
                assertIs<SongItem>(
                    YouTubeItemParser.parseItem(
                        renderer(
                            """{
                "title":{"runs":[{"text":"An explicit title"}]},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"track"}},
                "badges":[{"musicInlineBadgeRenderer":{"icon":{"iconType":"$badge"}}}]
            }""",
                        ),
                    ),
                )
            assertEquals(badge.isNotEmpty(), song.isExplicit)
            assertEquals(badge.isNotEmpty(), song.explicitness)
        }
    }

    @Test
    fun localizedCountsAndEmptyFragmentsDoNotReplaceArtistsOrDropTracks() {
        for ((runs, artist, album) in listOf(
            Triple("1,2 Mio. Aufrufe", null, null),
            Triple("Artist • 1,2 Mio. Aufrufe", "Artist", null),
            Triple("Artist •  • Album", "Artist", null),
            Triple("Artist •  • An Album", "Artist", "An Album"),
        )) {
            val metadata = YouTubeItemParser.extractMetadata(Json.parseToJsonElement("""[{"text":"$runs"}]""").jsonArray)
            assertEquals(artist, metadata.artists.firstOrNull()?.name)
            assertEquals(album, metadata.album?.name)
        }
    }

    @Test
    fun consecutiveSeparatorsPreserveTheTrack() {
        val song =
            assertIs<SongItem>(
                YouTubeItemParser.parseItem(
                    renderer(
                        """
            {
              "flexColumns": [
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Artist •  • Album"}]}}}
              ],
              "playlistItemData": {"videoId": "track"}
            }
        """,
                    ),
                ),
            )
        assertEquals("track", song.id)
        assertEquals("Artist", song.artists.single().name)
    }

    @Test
    fun preservesUnlinkedArtistsStartingWithDigitsInSearchAndPlaylistRows() {
        for (artist in listOf("90's or 20's cartoons", "2Pac", "311", "21 Savage")) {
            val song =
                assertIs<SongItem>(
                    YouTubeItemParser.parseItem(
                        renderer(
                            """
                            {
                              "flexColumns": [
                                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                                  {"text": "$artist"}, {"text": " • "}, {"text": "2024"},
                                  {"text": " • "}, {"text": "58K views"}
                                ]}}}
                              ],
                              "fixedColumns": [{"musicResponsiveListItemFixedColumnRenderer": {"text": {"runs": [{"text": "3:45"}]}}}],
                              "playlistItemData": {"videoId": "yHyPIGOy5fI"}
                            }
                            """,
                        ),
                    ),
                )

            assertEquals(artist, song.artists.single().name)
            assertNull(song.artists.single().id)
            assertNull(song.album)
            assertEquals(225L, song.duration)
        }
    }

    @Test
    fun doesNotTreatLocalizedPlayCountAsUnlinkedAlbum() {
        val metadata =
            YouTubeItemParser.extractMetadata(
                Json
                    .parseToJsonElement(
                        """
                        [
                          {"text": "Artist", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCartist"}}},
                          {"text": " • "}, {"text": "1,2 Mio. Aufrufe"}
                        ]
                        """,
                    ).jsonArray,
            )

        assertEquals("Artist", metadata.artists.single().name)
        assertNull(metadata.album)
    }

    @Test
    fun preservesLinkedArtistAndAlbumNamesThatResembleDates() {
        for (name in listOf("1989", "Today", "Yesterday")) {
            val metadata =
                YouTubeItemParser.extractMetadata(
                    Json
                        .parseToJsonElement(
                            """
                            [
                              {"text": "$name", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCartist"}}},
                              {"text": " • "},
                              {"text": "$name", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREbalbum"}}},
                              {"text": " • "}, {"text": "2026"},
                              {"text": " • "}, {"text": "3:45"}
                            ]
                            """,
                        ).jsonArray,
                )

            assertEquals("UCartist", metadata.artists.single().id)
            assertEquals(name, metadata.artists.single().name)
            assertEquals("MPREbalbum", metadata.album?.id)
            assertEquals(name, metadata.album?.name)
            assertEquals(225L, metadata.duration)
            assertNull(metadata.playCountText)
        }
    }

    @Test
    fun parsesResponsiveSongDurationAndPlaylistIdentity() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Artist", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC_artist"}}},
                      {"text": " • "},
                      {"text": "2024"}
                    ]}}}
                  ],
                  "fixedColumns": [{"musicResponsiveListItemFixedColumnRenderer": {"text": {"runs": [{"text": "3:45"}]}}}],
                  "playlistItemData": {"videoId": "video", "playlistSetVideoId": "set-video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals(225L, song.duration)
        assertEquals("set-video", song.playlistSetVideoId)
        assertEquals("Artist", song.artists.single().name)
        assertNull(song.album)
    }

    @Test
    fun parsesAlbumYearWithoutTreatingItAsDuration() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Album"}]}}}],
                  "subtitle": {"runs": [
                    {"text": "Artist", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC_artist"}}},
                    {"text": " • "},
                    {"text": "2024"}
                  ]},
                  "navigationEndpoint": {"browseEndpoint": {
                    "browseId": "MPREb_album",
                    "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ALBUM"}}
                  }}
                }
                """,
            )

        val album = assertIs<AlbumItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("2024", album.year)
        assertEquals("Artist", album.artists.single().name)
    }

    @Test
    fun parsesRemotePlaylistCountAndArtworkFromLibraryRows() {
        val renderer =
            renderer(
                """
                {
                  "title": {"runs": [{"text": "Playlist"}]},
                  "subtitle": {"runs": [
                    {"text": "Owner", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC_owner"}}},
                    {"text": " • "},
                    {"text": "12 songs"}
                  ]},
                  "thumbnailRenderer": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                    {"url": "https://example.com/cover=w120-h120-l90-rj", "width": 120, "height": 120}
                  ]}}},
                  "navigationEndpoint": {"browseEndpoint": {
                    "browseId": "VLPLremote",
                    "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
                      "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                    }}
                  }}
                }
                """,
            )

        val playlist = assertIs<PlaylistItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("12 songs", playlist.songCount)
        assertEquals("https://example.com/cover=s0", playlist.thumbnail)
    }

    @Test
    fun doesNotTreatCommunityPlaylistViewsAsTrackCount() {
        val renderer =
            renderer(
                """
                {
                  "title": {"runs": [{"text": "Community playlist"}]},
                  "subtitle": {"runs": [
                    {"text": "Owner", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC_owner"}}},
                    {"text": " • "},
                    {"text": "58K views"}
                  ]},
                  "navigationEndpoint": {"browseEndpoint": {
                    "browseId": "VLPLcommunity",
                    "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
                      "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                    }}
                  }}
                }
                """,
            )

        assertNull(assertIs<PlaylistItem>(YouTubeItemParser.parseItem(renderer)).songCount)
    }

    @Test
    fun treatsFirstAmbiguousSongLinkAsArtistAndSecondAsAlbum() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Artist", "navigationEndpoint": {"browseEndpoint": {"browseId": "artist-id"}}}
                    ]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Album", "navigationEndpoint": {"browseEndpoint": {"browseId": "album-id"}}}
                    ]}}}
                  ],
                  "playlistItemData": {"videoId": "video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("Artist", song.artists.single().name)
        assertEquals("artist-id", song.artists.single().id)
        assertEquals("Album", song.album?.name)
        assertEquals("album-id", song.album?.id)
    }

    @Test
    fun keepsUnlinkedArtistWhenOnlyAmbiguousLinkIsAlbum() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Unknown artist"},
                      {"text": " • "},
                      {"text": "Album", "navigationEndpoint": {"browseEndpoint": {"browseId": "album-id"}}}
                    ]}}}
                  ],
                  "playlistItemData": {"videoId": "video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("Unknown artist", song.artists.single().name)
        assertNull(song.artists.single().id)
        assertEquals("Album", song.album?.name)
        assertEquals("album-id", song.album?.id)
    }

    @Test
    fun parsesQuickPickArtwork() {
        val renderer =
            renderer(
                """
                {
                  "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                    {"url": "https://example.com/art=w60-h60-l90-rj"},
                    {"url": "https://example.com/art=w120-h120-l90-rj"}
                  ]}}},
                  "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                    "text": {"runs": [{"text": "Track"}]}
                  }}],
                  "playlistItemData": {"videoId": "video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("https://example.com/art=s0", song.thumbnail)
    }

    @Test
    fun selectsTheLargestArtworkWhenSearchThumbnailsAreOutOfOrder() {
        val renderer =
            renderer(
                """
                {
                  "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                    {"url": "https://yt3.googleusercontent.com/small=w544-h544-l90-rj", "width": 544, "height": 544},
                    {"url": "https://yt3.googleusercontent.com/large=w1200-h1200-l90-rj", "width": 1200, "height": 1200},
                    {"url": "https://yt3.googleusercontent.com/medium=w720-h720-l90-rj", "width": 720, "height": 720}
                  ]}}},
                  "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                    "text": {"runs": [{"text": "Track"}]}
                  }}],
                  "playlistItemData": {"videoId": "video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("https://yt3.googleusercontent.com/large=s0", song.thumbnail)
    }

    @Test
    fun infersOfficialVideoTypeFromTitleWhenEndpointOmitsIt() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "KATSEYE 'Hootie Frutti' Official MV"}
                    ]}}}
                  ],
                  "playlistItemData": {"videoId": "official-video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals(MUSIC_VIDEO_TYPE_OMV, song.musicVideoType)
    }

    @Test
    fun infersLiveVideoTypeFromTitleWhenEndpointOmitsIt() {
        for (title in listOf(
            "Artist - Track (Live)",
            "Artist - Track (Live Performance)",
            "Artist - Track - Live Session",
            "Artist - Track Live at Wembley",
            "Artist - Track - Live",
        )) {
            val renderer =
                renderer(
                    """
                    {
                      "flexColumns": [
                        {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                          {"text": "$title"}
                        ]}}}
                      ],
                      "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                        {"url": "https://example.com/live.jpg", "width": 480, "height": 270}
                      ]}}},
                      "playlistItemData": {"videoId": "live-video"}
                    }
                    """,
                )

            val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

            assertEquals(MUSIC_VIDEO_TYPE_LIVE, song.musicVideoType)
        }
    }

    @Test
    fun keepsLiveAlbumTracksAsSongsWithoutVideoEvidence() {
        for (title in listOf("Track (Live)", "Track - Live at Wembley")) {
            for (dimensions in listOf("", "\"width\": 544, \"height\": 544,")) {
                val song =
                    assertIs<SongItem>(
                        YouTubeItemParser.parseItem(
                            renderer(
                                """
                            {
                              "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                                {$dimensions "url": "https://example.com/album.jpg"}
                              ]}}},
                              "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                                "text": {"runs": [{"text": "$title"}]}
                              }}, {"musicResponsiveListItemFlexColumnRenderer": {
                                "text": {"runs": [{"text": "Live Album", "navigationEndpoint": {
                                  "browseEndpoint": {"browseId": "MPREb_live_album"}
                                }}]}
                              }}],
                              "playlistItemData": {"videoId": "album-track"}
                            }
                            """,
                            ),
                        ),
                    )
                assertNull(song.musicVideoType)
                assertEquals("MPREb_live_album", song.album?.id)
            }
        }
    }

    @Test
    fun radioQueueLiveInferenceRequiresVideoArtworkAndPreservesExplicitAudioType() {
        for ((width, endpointType, expected) in listOf(
            Triple(480, "", MUSIC_VIDEO_TYPE_LIVE),
            Triple(270, "", null),
            Triple(480, "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_ATV"),
        )) {
            val item =
                renderer(
                    """
                {"playlistPanelVideoRenderer": {
                  "videoId": "live-track",
                  "title": {"runs": [{"text": "Track (Live)"}]},
                  "thumbnail": {"thumbnails": [{"url": "https://example.com/art.jpg", "width": $width, "height": 270}]},
                  ${if (endpointType.isEmpty()) "" else "\"navigationEndpoint\": {\"watchEndpoint\": {\"musicVideoType\": \"$endpointType\"}},"}
                  "lengthText": {"runs": [{"text": "3:00"}]}
                }}
                """,
                )
            val song = assertIs<SongItem>(YouTubeRadioParser.parsePlaylistPanelItem(item))
            assertEquals(expected, song.musicVideoType)
        }
    }

    @Test
    fun doesNotInferLiveVideoTypeFromStudioTitleContainingLive() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Oasis - Live Forever"}
                    ]}}}
                  ],
                  "playlistItemData": {"videoId": "studio-track"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertNull(song.musicVideoType)
    }

    @Test
    fun splitsCombinedUnlinkedBylineIntoArtistAndDuration() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Seven Lions, Last Heroes & HALIENE • 3:52"}
                    ]}}}
                  ],
                  "playlistItemData": {"videoId": "video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("Seven Lions, Last Heroes & HALIENE", song.artists.single().name)
        assertNull(song.artists.single().id)
        assertEquals(232L, song.duration)
    }

    @Test
    fun keepsUnlinkedArtistRunAmongLinkedAlbum() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Maroon 5"},
                      {"text": " • "},
                      {"text": "Songs About Jane", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_album"}}}
                    ]}}}
                  ],
                  "playlistItemData": {"videoId": "video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("Maroon 5", song.artists.single().name)
        assertNull(song.artists.single().id)
        assertEquals("Songs About Jane", song.album?.name)
        assertEquals("MPREb_album", song.album?.id)
    }

    @Test
    fun doesNotAssignAlbumBrowseIdToAmbiguousArtist() {
        val renderer =
            renderer(
                """
                {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Artist Name", "navigationEndpoint": {"browseEndpoint": {"browseId": "artist-id"}}},
                      {"text": " • "},
                      {"text": "Album Name", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_album"}}}
                    ]}}}
                  ],
                  "playlistItemData": {"videoId": "video"}
                }
                """,
            )

        val song = assertIs<SongItem>(YouTubeItemParser.parseItem(renderer))

        assertEquals("Artist Name", song.artists.single().name)
        assertEquals("artist-id", song.artists.single().id)
        assertEquals("Album Name", song.album?.name)
        assertEquals("MPREb_album", song.album?.id)
    }

    private fun renderer(json: String) = Json.parseToJsonElement(json.trimIndent()).jsonObject
}
