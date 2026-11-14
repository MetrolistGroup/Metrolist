package com.metrolist.shared.db

// Preserve dependent rows before rebuilding parents: foreign-key actions remain enabled.
internal val MIGRATION_58_59_SQL =
    buildList {
        add("CREATE TEMP TABLE boolean_defaults_song AS SELECT * FROM `song`")
        add("CREATE TEMP TABLE boolean_defaults_artist AS SELECT * FROM `artist`")
        add("CREATE TEMP TABLE boolean_defaults_album AS SELECT * FROM `album`")
        add("CREATE TEMP TABLE boolean_defaults_playlist AS SELECT * FROM `playlist`")
        add("CREATE TEMP TABLE boolean_defaults_lyrics AS SELECT * FROM `lyrics`")
        add("CREATE TEMP TABLE boolean_defaults_event AS SELECT * FROM `event`")
        add("CREATE TEMP TABLE boolean_defaults_song_artist_map AS SELECT * FROM `song_artist_map`")
        add("CREATE TEMP TABLE boolean_defaults_song_album_map AS SELECT * FROM `song_album_map`")
        add("CREATE TEMP TABLE boolean_defaults_album_artist_map AS SELECT * FROM `album_artist_map`")
        add("CREATE TEMP TABLE boolean_defaults_playlist_song_map AS SELECT * FROM `playlist_song_map`")
        add("CREATE TEMP TABLE boolean_defaults_import_track_match AS SELECT * FROM `import_track_match`")
        add("CREATE TEMP TABLE boolean_defaults_related_song_map AS SELECT * FROM `related_song_map`")
        add("CREATE TEMP TABLE boolean_defaults_history_snapshot AS SELECT * FROM `history_snapshot`")
        add("CREATE TEMP TABLE boolean_defaults_history_provenance AS SELECT * FROM `history_provenance`")
        add("CREATE TEMP TABLE boolean_defaults_sequence AS SELECT * FROM sqlite_sequence WHERE name = 'event'")
        add("DELETE FROM `song_artist_map`")
        add("DELETE FROM `song_album_map`")
        add("DELETE FROM `album_artist_map`")
        add("DELETE FROM `playlist_song_map`")
        add("DELETE FROM `import_track_match`")
        add("DELETE FROM `related_song_map`")
        add("DELETE FROM `history_snapshot`")
        add("DELETE FROM `history_provenance`")
        add("DROP TABLE `song`")
        add(
            """CREATE TABLE IF NOT EXISTS `song` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `customTitle` TEXT DEFAULT NULL, `customArtist` TEXT DEFAULT NULL, `duration` INTEGER NOT NULL, `thumbnailUrl` TEXT, `albumId` TEXT, `albumName` TEXT, `explicit` INTEGER NOT NULL DEFAULT 0, `year` INTEGER, `date` INTEGER, `dateModified` INTEGER, `liked` INTEGER NOT NULL, `likedDate` INTEGER, `totalPlayTime` INTEGER NOT NULL, `inLibrary` INTEGER, `dateDownload` INTEGER, `isLocal` INTEGER NOT NULL DEFAULT 0, `libraryAddToken` TEXT, `libraryRemoveToken` TEXT, `lyricsOffset` INTEGER NOT NULL DEFAULT 0, `romanizeLyrics` INTEGER NOT NULL DEFAULT 1, `isDownloaded` INTEGER NOT NULL DEFAULT 0, `isUploaded` INTEGER NOT NULL DEFAULT 0, `isVideo` INTEGER NOT NULL DEFAULT 0, `isEpisode` INTEGER NOT NULL DEFAULT 0, `playbackPosition` INTEGER DEFAULT NULL, `uploadEntityId` TEXT DEFAULT NULL, `isCached` INTEGER NOT NULL DEFAULT 0, `explicitness` INTEGER DEFAULT NULL, PRIMARY KEY(`id`))""",
        )
        add(
            "INSERT INTO `song` (`id`, `title`, `customTitle`, `customArtist`, `duration`, `thumbnailUrl`, `albumId`, `albumName`, `explicit`, `year`, `date`, `dateModified`, `liked`, `likedDate`, `totalPlayTime`, `inLibrary`, `dateDownload`, `isLocal`, `libraryAddToken`, `libraryRemoveToken`, `lyricsOffset`, `romanizeLyrics`, `isDownloaded`, `isUploaded`, `isVideo`, `isEpisode`, `playbackPosition`, `uploadEntityId`, `isCached`, `explicitness`) SELECT `id`, `title`, `customTitle`, `customArtist`, `duration`, `thumbnailUrl`, `albumId`, `albumName`, CASE `explicit` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `explicit` END, `year`, `date`, `dateModified`, `liked`, `likedDate`, `totalPlayTime`, `inLibrary`, `dateDownload`, CASE `isLocal` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isLocal` END, `libraryAddToken`, `libraryRemoveToken`, `lyricsOffset`, CASE `romanizeLyrics` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `romanizeLyrics` END, CASE `isDownloaded` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isDownloaded` END, CASE `isUploaded` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isUploaded` END, CASE `isVideo` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isVideo` END, CASE `isEpisode` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isEpisode` END, `playbackPosition`, `uploadEntityId`, CASE `isCached` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isCached` END, `explicitness` FROM boolean_defaults_song",
        )
        add("""CREATE INDEX IF NOT EXISTS `index_song_albumId` ON `song` (`albumId`)""")
        add("DROP TABLE `artist`")
        add(
            """CREATE TABLE IF NOT EXISTS `artist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `thumbnailUrl` TEXT, `channelId` TEXT, `lastUpdateTime` INTEGER NOT NULL, `bookmarkedAt` INTEGER, `isLocal` INTEGER NOT NULL DEFAULT 0, `isPodcastChannel` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))""",
        )
        add(
            "INSERT INTO `artist` (`id`, `name`, `thumbnailUrl`, `channelId`, `lastUpdateTime`, `bookmarkedAt`, `isLocal`, `isPodcastChannel`) SELECT `id`, `name`, `thumbnailUrl`, `channelId`, `lastUpdateTime`, `bookmarkedAt`, CASE `isLocal` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isLocal` END, CASE `isPodcastChannel` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isPodcastChannel` END FROM boolean_defaults_artist",
        )
        add("DROP TABLE `album`")
        add(
            """CREATE TABLE IF NOT EXISTS `album` (`id` TEXT NOT NULL, `playlistId` TEXT, `title` TEXT NOT NULL, `year` INTEGER, `thumbnailUrl` TEXT, `themeColor` INTEGER, `songCount` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `explicit` INTEGER NOT NULL DEFAULT 0, `lastUpdateTime` INTEGER NOT NULL, `bookmarkedAt` INTEGER, `likedDate` INTEGER, `inLibrary` INTEGER, `isLocal` INTEGER NOT NULL DEFAULT 0, `isUploaded` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))""",
        )
        add(
            "INSERT INTO `album` (`id`, `playlistId`, `title`, `year`, `thumbnailUrl`, `themeColor`, `songCount`, `duration`, `explicit`, `lastUpdateTime`, `bookmarkedAt`, `likedDate`, `inLibrary`, `isLocal`, `isUploaded`) SELECT `id`, `playlistId`, `title`, `year`, `thumbnailUrl`, `themeColor`, `songCount`, `duration`, CASE `explicit` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `explicit` END, `lastUpdateTime`, `bookmarkedAt`, `likedDate`, `inLibrary`, CASE `isLocal` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isLocal` END, CASE `isUploaded` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isUploaded` END FROM boolean_defaults_album",
        )
        add("DROP TABLE `playlist`")
        add(
            """CREATE TABLE IF NOT EXISTS `playlist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `browseId` TEXT, `createdAt` INTEGER, `lastUpdateTime` INTEGER, `isEditable` INTEGER NOT NULL DEFAULT 1, `bookmarkedAt` INTEGER, `remoteSongCount` INTEGER, `playEndpointParams` TEXT, `thumbnailUrl` TEXT, `shuffleEndpointParams` TEXT, `radioEndpointParams` TEXT, `isLocal` INTEGER NOT NULL DEFAULT 0, `isAutoSync` INTEGER NOT NULL DEFAULT 0, `description` TEXT, `customThumbnailUrl` TEXT, PRIMARY KEY(`id`))""",
        )
        add(
            "INSERT INTO `playlist` (`id`, `name`, `browseId`, `createdAt`, `lastUpdateTime`, `isEditable`, `bookmarkedAt`, `remoteSongCount`, `playEndpointParams`, `thumbnailUrl`, `shuffleEndpointParams`, `radioEndpointParams`, `isLocal`, `isAutoSync`, `description`, `customThumbnailUrl`) SELECT `id`, `name`, `browseId`, `createdAt`, `lastUpdateTime`, CASE `isEditable` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isEditable` END, `bookmarkedAt`, `remoteSongCount`, `playEndpointParams`, `thumbnailUrl`, `shuffleEndpointParams`, `radioEndpointParams`, CASE `isLocal` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isLocal` END, CASE `isAutoSync` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isAutoSync` END, `description`, `customThumbnailUrl` FROM boolean_defaults_playlist",
        )
        add("""CREATE INDEX IF NOT EXISTS `index_playlist_browseId` ON `playlist` (`browseId`)""")
        add("DROP TABLE `lyrics`")
        add(
            """CREATE TABLE IF NOT EXISTS `lyrics` (`id` TEXT NOT NULL, `lyrics` TEXT NOT NULL, `provider` TEXT NOT NULL DEFAULT 'Unknown', `translatedLyrics` TEXT NOT NULL DEFAULT '', `translationLanguage` TEXT NOT NULL DEFAULT '', `translationMode` TEXT NOT NULL DEFAULT '', `translationSourceHash` TEXT NOT NULL DEFAULT '', `translationConfigKey` TEXT NOT NULL DEFAULT '', `translationNotNeeded` INTEGER NOT NULL DEFAULT 0, `offsetMs` INTEGER NOT NULL DEFAULT 0, `isUserSelected` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))""",
        )
        add(
            "INSERT INTO `lyrics` (`id`, `lyrics`, `provider`, `translatedLyrics`, `translationLanguage`, `translationMode`, `translationSourceHash`, `translationConfigKey`, `translationNotNeeded`, `offsetMs`, `isUserSelected`) SELECT `id`, `lyrics`, `provider`, `translatedLyrics`, `translationLanguage`, `translationMode`, `translationSourceHash`, `translationConfigKey`, CASE `translationNotNeeded` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `translationNotNeeded` END, `offsetMs`, CASE `isUserSelected` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `isUserSelected` END FROM boolean_defaults_lyrics",
        )
        add("DROP TABLE `event`")
        add(
            """CREATE TABLE IF NOT EXISTS `event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `playTime` INTEGER NOT NULL, `playTimeKnown` INTEGER NOT NULL DEFAULT 1, `source` TEXT NOT NULL DEFAULT 'LOCAL', `remoteFeedbackToken` TEXT, `remoteSynced` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        add(
            "INSERT INTO `event` (`id`, `songId`, `timestamp`, `playTime`, `playTimeKnown`, `source`, `remoteFeedbackToken`, `remoteSynced`) SELECT `id`, `songId`, `timestamp`, `playTime`, `playTimeKnown`, `source`, `remoteFeedbackToken`, CASE `remoteSynced` WHEN 'true' THEN 1 WHEN 'false' THEN 0 ELSE `remoteSynced` END FROM boolean_defaults_event",
        )
        add("""CREATE UNIQUE INDEX IF NOT EXISTS `index_event_remoteFeedbackToken` ON `event` (`remoteFeedbackToken`)""")
        add("""CREATE INDEX IF NOT EXISTS `index_event_timestamp` ON `event` (`timestamp`)""")
        add("""CREATE INDEX IF NOT EXISTS `index_event_songId` ON `event` (`songId`)""")
        add("UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM boolean_defaults_sequence), seq)) WHERE name = 'event'")
        add("INSERT INTO `song_artist_map` SELECT * FROM boolean_defaults_song_artist_map")
        add("INSERT INTO `song_album_map` SELECT * FROM boolean_defaults_song_album_map")
        add("INSERT INTO `album_artist_map` SELECT * FROM boolean_defaults_album_artist_map")
        add("INSERT INTO `playlist_song_map` SELECT * FROM boolean_defaults_playlist_song_map")
        add("INSERT INTO `import_track_match` SELECT * FROM boolean_defaults_import_track_match")
        add("INSERT INTO `related_song_map` SELECT * FROM boolean_defaults_related_song_map")
        add("INSERT INTO `history_snapshot` SELECT * FROM boolean_defaults_history_snapshot")
        add("INSERT INTO `history_provenance` SELECT * FROM boolean_defaults_history_provenance")
        add("DROP TABLE boolean_defaults_song")
        add("DROP TABLE boolean_defaults_artist")
        add("DROP TABLE boolean_defaults_album")
        add("DROP TABLE boolean_defaults_playlist")
        add("DROP TABLE boolean_defaults_lyrics")
        add("DROP TABLE boolean_defaults_event")
        add("DROP TABLE boolean_defaults_song_artist_map")
        add("DROP TABLE boolean_defaults_song_album_map")
        add("DROP TABLE boolean_defaults_album_artist_map")
        add("DROP TABLE boolean_defaults_playlist_song_map")
        add("DROP TABLE boolean_defaults_import_track_match")
        add("DROP TABLE boolean_defaults_related_song_map")
        add("DROP TABLE boolean_defaults_history_snapshot")
        add("DROP TABLE boolean_defaults_history_provenance")
        add("DROP TABLE boolean_defaults_sequence")
    }
