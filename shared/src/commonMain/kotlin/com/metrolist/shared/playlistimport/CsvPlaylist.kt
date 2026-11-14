package com.metrolist.shared.playlistimport

data class CsvColumnMapping(
    val hasHeader: Boolean,
    val title: Int,
    val artist: Int,
    val album: Int = -1,
    val url: Int = -1,
) {
    fun isValid(columnCount: Int): Boolean {
        val selected = listOf(title, artist, album, url).filter { it >= 0 }
        return (title >= 0 || url >= 0) &&
            listOf(title, artist, album, url).all { it in -1 until columnCount } &&
            selected.distinct().size == selected.size
    }
}

class CsvPlaylist(
    val rows: List<List<String>>,
) {
    val columnCount = rows.maxOfOrNull { it.size } ?: 0

    fun guessMapping(): CsvColumnMapping {
        val headers = rows.firstOrNull().orEmpty().map { it.trim().lowercase().replace(Regex("[_\\s]+"), " ") }

        fun column(vararg names: String) = headers.indexOfFirst { it in names }
        val title = column("title", "track", "track name", "track title", "song", "song title", "name")
        val artist = column("artist", "artists", "artist name", "artist names", "artist name(s)", "creator", "author")
        val album = column("album", "album name", "album title")
        val url =
            column("video id", "videoid", "youtube id", "youtube video id", "youtube url", "url", "uri", "link", "track uri", "track url")
        val hasHeader = listOf(title, artist, album, url).any { it >= 0 }
        return if (hasHeader) {
            CsvColumnMapping(true, title, artist, album, url)
        } else {
            CsvColumnMapping(false, if (columnCount > 0) 0 else -1, if (columnCount > 1) 1 else -1)
        }
    }

    fun tracks(
        mapping: CsvColumnMapping,
        resolveLocalReference: (String) -> String? = { null },
    ): List<PlaylistImportManager.CsvTrack> {
        if (!mapping.isValid(columnCount)) throw ImportException(ImportErrorKey.NoTracksRecognized)
        return rows
            .asSequence()
            .drop(if (mapping.hasHeader) 1 else 0)
            .mapNotNull { row ->
                fun cell(index: Int) = row.getOrNull(index)?.trim().orEmpty()
                val title = cell(mapping.title)
                val rawUrl = cell(mapping.url)
                val videoId = UniversalPlaylistParser.extractVideoId(rawUrl)
                val resolvedLocal = rawUrl.takeIf { it.isNotBlank() && videoId == null }?.let(resolveLocalReference)
                val reference = if (videoId == null) rawUrl.takeIf(UniversalPlaylistParser::looksLikeDirectReference) else null
                if (title.isBlank() && reference == null && resolvedLocal == null && videoId == null) return@mapNotNull null
                PlaylistImportManager.CsvTrack(
                    title = title,
                    artist = cell(mapping.artist),
                    videoId = videoId,
                    directReference = resolvedLocal ?: reference,
                    isResolvedLocalReference = resolvedLocal != null,
                    album = cell(mapping.album),
                    customTitle = title.takeIf(String::isNotBlank),
                )
            }.take(PlaylistImportManager.MAX_PLAYLIST_TRACKS)
            .toList()
    }

    companion object {
        fun parse(text: String): CsvPlaylist {
            if (text.length > PlaylistImportManager.MAX_CSV_CHARACTERS) throw ImportException(ImportErrorKey.FileReadFailed)
            val input = text.removePrefix("\uFEFF")
            val delimiter = detectDelimiter(input)
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var closedQuote = false
            var index = 0

            fun endCell() {
                row += cell.toString()
                cell.clear()
                closedQuote = false
            }
            while (index < input.length) {
                val char = input[index]
                when {
                    quoted -> {
                        if (char != '"') {
                            cell.append(char)
                        } else if (input.getOrNull(index + 1) == '"') {
                            cell.append('"')
                            index++
                        } else {
                            quoted = false
                            closedQuote = true
                        }
                    }

                    char == delimiter -> {
                        endCell()
                    }

                    char == '\r' || char == '\n' -> {
                        endCell()
                        rows += row
                        row = mutableListOf()
                        if (char == '\r' && input.getOrNull(index + 1) == '\n') index++
                    }

                    char == '"' && cell.isEmpty() && !closedQuote -> {
                        quoted = true
                    }

                    char == '"' || closedQuote -> {
                        throw ImportException(ImportErrorKey.FileReadFailed)
                    }

                    else -> {
                        cell.append(char)
                    }
                }
                index++
            }
            if (quoted) throw ImportException(ImportErrorKey.FileReadFailed)
            if (cell.isNotEmpty() || row.isNotEmpty() || closedQuote) {
                endCell()
                rows += row
            }
            return CsvPlaylist(rows)
        }

        private fun detectDelimiter(text: String): Char {
            val delimiters = listOf(',', ';', '\t')
            val counts = mutableListOf<IntArray>()
            var row = IntArray(delimiters.size)
            var quoted = false
            var index = 0
            while (index < text.length && counts.size < 20) {
                val char = text[index]
                if (char == '"') {
                    if (quoted && text.getOrNull(index + 1) == '"') index++ else quoted = !quoted
                } else if (!quoted) {
                    if (char == '\r' || char == '\n') {
                        if (row.any { it > 0 }) counts += row
                        row = IntArray(delimiters.size)
                        if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                    } else {
                        val delimiterIndex = delimiters.indexOf(char)
                        if (delimiterIndex >= 0) row[delimiterIndex]++
                    }
                }
                index++
            }
            if (row.any { it > 0 }) counts += row
            val best =
                delimiters.indices.maxWithOrNull(
                    compareBy<Int> { candidate ->
                        counts
                            .map { it[candidate] }
                            .filter { it > 0 }
                            .groupingBy { it }
                            .eachCount()
                            .values
                            .maxOrNull() ?: 0
                    }.thenBy { candidate -> counts.firstOrNull()?.get(candidate) ?: 0 },
                ) ?: 0
            return delimiters[best]
        }
    }
}
