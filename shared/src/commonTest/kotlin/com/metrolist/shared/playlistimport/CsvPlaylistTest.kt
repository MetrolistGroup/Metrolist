package com.metrolist.shared.playlistimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsvPlaylistTest {
    @Test
    fun preservesQuotedFieldsAndRecordBoundaries() {
        val csv = CsvPlaylist.parse("\uFEFFTitle,Artist,Album\r\n\"Song, \"\"One\"\"\r\nPart two\",\" Artist \",\r\nLast,Other,\"\"\r\n")
        assertEquals(
            listOf(
                listOf("Title", "Artist", "Album"),
                listOf("Song, \"One\"\r\nPart two", " Artist ", ""),
                listOf("Last", "Other", ""),
            ),
            csv.rows,
        )
        assertEquals("Song, \"One\"\r\nPart two", csv.tracks(csv.guessMapping()).first().title)
        assertEquals("Artist", csv.tracks(csv.guessMapping()).first().artist)
    }

    @Test
    fun detectsDelimitersOutsideQuotes() {
        for (delimiter in listOf(',', ';', '\t')) {
            val content = "Title${delimiter}Artist${delimiter}Album\n\"Song,;\t\"${delimiter}\"Artist\nTwo\"${delimiter}Album"
            val csv = CsvPlaylist.parse(content)
            assertEquals(listOf("Song,;\t", "Artist\nTwo", "Album"), csv.rows.last())
            assertEquals("Album", csv.tracks(csv.guessMapping()).single().album)
        }
        val tracks = PlaylistImportManager.parseCsv("Title;Artist\r\nSong;One, Two\r\nOther;Three")
        assertEquals(listOf("One, Two", "Three"), tracks.map { it.artist })
        assertEquals(
            "Two",
            PlaylistImportManager
                .parseCsv("Title,Artist\nSong,One; Two")
                .single()
                .artist
                .substringAfter("; "),
        )
    }

    @Test
    fun honorsAFirstLineSeparatorDirectiveWithoutImportingItAsATrack() {
        for ((delimiter, newline) in listOf(';' to "\r\n", ',' to "\n", '\t' to "\r")) {
            val text =
                "\uFEFFSeP=$delimiter${newline}Artist${delimiter}Title${delimiter}URL$newline" +
                    "João${delimiter}\"Song, Part Two\"${delimiter}https://youtu.be/abcdefghijk"
            val csv = assertNotNull(UniversalPlaylistParser.prepareFile(text, "data.csv", "Playlist", "File").csv)

            assertEquals(CsvColumnMapping(true, title = 1, artist = 0, url = 2), csv.guessMapping())
            val track = csv.tracks(csv.guessMapping()).single()
            assertEquals("Song, Part Two", track.title)
            assertEquals("João", track.artist)
            assertEquals("abcdefghijk", track.videoId)
        }
    }

    @Test
    fun onlyRecognizesAnUnquotedSupportedDirectiveOnTheFirstLine() {
        assertEquals(listOf("sep=;"), CsvPlaylist.parse("\"sep=;\"\nSong;Artist").rows.first())
        assertEquals(listOf("sep=|"), CsvPlaylist.parse("sep=|\nSong;Artist").rows.first())
        assertEquals(listOf("sep=", ""), CsvPlaylist.parse("sep=;").rows.single())
        assertEquals(listOf("sep=", ""), CsvPlaylist.parse("Song;Artist\nsep=;").rows.last())
    }

    @Test
    fun mapsReorderedHeadersAndKeepsYoutubeIdsAndAlbumForMatching() {
        val csv = CsvPlaylist.parse("Album Name,YouTube URL,Artist Name(s),Track Name\nAlbum,https://youtu.be/abcdefghijk,Artist,Song")
        val mapping = csv.guessMapping()
        assertEquals(CsvColumnMapping(true, 3, 2, 0, 1), mapping)
        val track = csv.tracks(mapping).single()
        assertEquals("abcdefghijk", track.videoId)
        assertEquals("Song Artist Album", PlaylistImportManager.resolutionSearchQuery(track))
        assertNull(track.directReference)
        val legacyExport = PlaylistImportManager.parseCsv("Title,Artist,Album,YouTube Video ID\nSong,Artist,Album,abcdefghijk")
        assertEquals("abcdefghijk", legacyExport.single().videoId)
    }

    @Test
    fun manualMappingAndHeaderToggleKeepFirstDataRow() {
        val csv = CsvPlaylist.parse("Artist A,Album A,Song A\nArtist B,Album B,Song B")
        assertFalse(csv.guessMapping().hasHeader)
        val mapping = CsvColumnMapping(false, title = 2, artist = 0, album = 1)
        assertEquals(listOf("Song A", "Song B"), csv.tracks(mapping).map { it.title })
        assertEquals(listOf("Song B"), csv.tracks(mapping.copy(hasHeader = true)).map { it.title })
        assertFalse(mapping.copy(artist = 2).isValid(csv.columnCount))
        assertFalse(mapping.copy(title = 3).isValid(csv.columnCount))
        assertFalse(mapping.copy(title = -1).isValid(csv.columnCount))
        assertFalse(mapping.copy(album = -2).isValid(csv.columnCount))
    }

    @Test
    fun skipsEmptyAndShortRowsWithoutLosingOrder() {
        val csv = CsvPlaylist.parse("Artist,Album,Title\nA,B,First\n\nshort\n,,\nC,D,Second")
        assertEquals(listOf("First", "Second"), csv.tracks(csv.guessMapping()).map { it.title })
        assertTrue(CsvPlaylist.parse("").rows.isEmpty())
        assertEquals(listOf(listOf("")), CsvPlaylist.parse("\"\"").rows)
        assertEquals(listOf(listOf("", "")), CsvPlaylist.parse(",").rows)
        assertEquals(listOf(listOf("  ", " ")), CsvPlaylist.parse("  , ").rows)
        assertTrue(PlaylistImportManager.parseCsv("Title,Artist").isEmpty())
        assertEquals(listOf("Song", "Artist", ""), CsvPlaylist.parse("Song,Artist,").rows.single())
    }

    @Test
    fun supportsUrlOnlyAndResolvedLocalReferences() {
        val csv = CsvPlaylist.parse("url\nhttps://music.youtube.com/watch?v=abcdefghijk")
        assertTrue(csv.guessMapping().isValid(1))
        assertEquals("abcdefghijk", csv.tracks(csv.guessMapping()).single().videoId)
        val local = CsvPlaylist.parse("Title;Artist;URL\nSong;Artist;../song.mp3")
        val track = local.tracks(local.guessMapping()) { "file:///music/song.mp3" }.single()
        assertTrue(track.isResolvedLocalReference)
        assertEquals("file:///music/song.mp3", track.directReference)
    }

    @Test
    fun rejectsMalformedQuotesInsteadOfSilentlyImportingCorruptRows() {
        for (text in listOf("Title,Artist\n\"unclosed,Artist", "Title,Artist\n\"Song\"oops,Artist", "Title,Artist\nSo\"ng,Artist")) {
            val error = assertFailsWith<ImportException> { CsvPlaylist.parse(text) }
            assertEquals(ImportErrorKey.FileReadFailed, error.errorKey)
        }
    }

    @Test
    fun filePreparationWaitsForMappingButLeavesOtherFormatsOnExistingPipeline() {
        for (fileName in listOf("playlist.csv", "playlist.TSV", "playlist.txt")) {
            val data = UniversalPlaylistParser.prepareFile("Artist;Album;Title\nA;B;C", fileName, "Playlist", "File")
            assertNotNull(data.csv)
            assertTrue(data.tracks.isEmpty())
            assertEquals(
                "C",
                data.csv
                    .tracks(data.csv.guessMapping())
                    .single()
                    .title,
            )
        }
        assertFailsWith<ImportException> { UniversalPlaylistParser.prepareFile("", "empty.csv", "Playlist", "File") }
        val singleColumn = UniversalPlaylistParser.prepareFile("Song A\nSong B", "playlist.csv", "Playlist", "File")
        assertNotNull(singleColumn.csv)
        val plain = UniversalPlaylistParser.prepareFile("Artist - Song", "playlist.txt", "Playlist", "File")
        assertNull(plain.csv)
        assertEquals("Song", plain.tracks.single().title)
        val m3u =
            UniversalPlaylistParser.prepareFile(
                "#EXTM3U\n#EXTINF:-1,Artist - Song\nhttps://youtu.be/abcdefghijk",
                "list.m3u",
                "Playlist",
                "File",
            )
        assertNull(m3u.csv)
        assertEquals("abcdefghijk", m3u.tracks.single().videoId)
        val json = UniversalPlaylistParser.prepareFile("[{\"title\":\"Song\",\"artist\":\"Artist\"}]", "list.json", "Playlist", "File")
        assertNull(json.csv)
        assertEquals("Song", json.tracks.single().title)
    }

    @Test
    fun filePreparationUsesTabsForHeaderlessTsvWithCommaRichTitles() {
        for (extension in listOf("tsv", "TSV")) {
            val text = "Part one, part two, part three\tArtist A\r\nIntro, middle, end\tArtist B"
            val data = UniversalPlaylistParser.prepareFile(text, "playlist.$extension", "Playlist", "File")
            val csv = assertNotNull(data.csv)

            assertEquals(listOf(listOf("Part one, part two, part three", "Artist A"), listOf("Intro, middle, end", "Artist B")), csv.rows)
            val tracks = csv.tracks(csv.guessMapping())
            assertEquals(listOf("Part one, part two, part three", "Intro, middle, end"), tracks.map { it.title })
            assertEquals(listOf("Artist A", "Artist B"), tracks.map { it.artist })
        }
    }

    @Test
    fun tsvExtensionDoesNotOverrideAnExplicitSeparatorOrTabsInsideQuotes() {
        for (directive in listOf("", "sep=;\r\n")) {
            val text = "\uFEFF${directive}Title;Artist\r\n\"Song\tPart two\";Artist"
            val csv = assertNotNull(UniversalPlaylistParser.prepareFile(text, "playlist.tsv", "Playlist", "File").csv)

            val track = csv.tracks(csv.guessMapping()).single()
            assertEquals("Song\tPart two", track.title)
            assertEquals("Artist", track.artist)
        }
        val explicitlySemicolonDelimited = "sep=;\nTitle;Artist\nSong\tPart two;Artist"
        val csv =
            assertNotNull(UniversalPlaylistParser.prepareFile(explicitlySemicolonDelimited, "playlist.tsv", "Playlist", "File").csv)
        assertEquals("Song\tPart two", csv.tracks(csv.guessMapping()).single().title)
        val mislabeledTsv = "Title;Artist\nSong\tPart two;Artist\nSong two;Artist two"
        val fallback = assertNotNull(UniversalPlaylistParser.prepareFile(mislabeledTsv, "playlist.tsv", "Playlist", "File").csv)
        assertEquals(listOf("Song\tPart two", "Song two"), fallback.tracks(fallback.guessMapping()).map { it.title })
    }

    @Test
    fun preservesPunctuationInHeaderlessM3uLocalPaths() {
        val path = "Music/One, Two; Three.mp3"
        val resolved = "file:///library/one-two-three.mp3"
        val data =
            UniversalPlaylistParser.prepareFile(path, "playlist.M3U", "Playlist", "File") {
                if (it == path) resolved else null
            }

        assertNull(data.csv)
        val track = data.tracks.single()
        assertEquals("One, Two; Three", track.title)
        assertEquals(resolved, track.directReference)
        assertTrue(track.isResolvedLocalReference)
        assertNull(track.customTitle)
    }

    @Test
    fun preservesHeaderlessM3uVideoIdsWhenUrlHasCommas() {
        val text =
            "https://www.youtube.com/watch?v=abcdefghijk&feature=one,two\n" +
                "https://youtu.be/bcdefghijkl"
        val data = UniversalPlaylistParser.prepareFile(text, "playlist.m3u8", "Playlist", "File")

        assertNull(data.csv)
        assertEquals(listOf("abcdefghijk", "bcdefghijkl"), data.tracks.map { it.videoId })
    }

    @Test
    fun mappedFileRejectsUnresolvedLocalReferences() {
        val remote =
            UniversalPlaylistParser.prepareFile(
                "Artist,URL,Title\nArtist,https://youtu.be/abcdefghijk,Song",
                "playlist.csv",
                "Playlist",
                "File",
            )
        val remoteCsv = assertNotNull(remote.csv)
        val remoteTrack = UniversalPlaylistParser.importableTracks(remoteCsv.tracks(remoteCsv.guessMapping())).single()
        assertEquals("Song", remoteTrack.title)
        assertEquals("abcdefghijk", remoteTrack.videoId)

        val data =
            UniversalPlaylistParser.prepareFile(
                "Title,URL\nLocal song,../song.mp3",
                "playlist.csv",
                "Playlist",
                "File",
            )
        val csv = assertNotNull(data.csv)
        val error =
            assertFailsWith<ImportException> {
                UniversalPlaylistParser.importableTracks(csv.tracks(csv.guessMapping()))
            }
        assertEquals(ImportErrorKey.UnsupportedPlaylistEntries, error.errorKey)
    }
}
