-- Synthetic Prism v8 library, authored for Metrolist; see docs/test-fixtures.md.
CREATE TABLE songs (
    id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, artistName TEXT NOT NULL,
    artistId TEXT, albumTitle TEXT, albumId TEXT, durationSec INTEGER NOT NULL,
    thumbnail TEXT, isVideo INTEGER NOT NULL, liked INTEGER NOT NULL,
    likedAt INTEGER NOT NULL, lastPlayed INTEGER NOT NULL, smartDownload INTEGER NOT NULL,
    primaryArtist TEXT NOT NULL DEFAULT '', artistsJson TEXT
);
CREATE TABLE play_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId TEXT NOT NULL,
    timestamp INTEGER NOT NULL, playedMs INTEGER NOT NULL, source TEXT,
    excluded INTEGER NOT NULL DEFAULT 0
);
INSERT INTO songs VALUES ('aaaaaaaaaaa','Liked track','Comma, Artist, Guest','UCartist1','Test album','MPRE_album',205,'https://example.test/song.jpg',1,1,1700000000000,1700000100000,0,'Comma, Artist','[{"name":"Comma, Artist","id":"UCartist1"},{"name":"Guest"}]');
INSERT INTO songs VALUES ('bbbbbbbbbbb','History track','Legacy artist, Guest','UCartist2',NULL,NULL,60,NULL,0,0,0,1700000200000,0,'Legacy artist',NULL);
INSERT INTO songs VALUES ('ccccccccccc','Cached only','Unknown artist',NULL,NULL,NULL,0,NULL,0,0,0,0,1,'',NULL);
INSERT INTO songs VALUES ('local:42','Local track','Local artist',NULL,NULL,NULL,10,NULL,0,1,1700000000000,0,0,'Local artist',NULL);
INSERT INTO songs VALUES ('not-a-video-id','Invalid track','Artist',NULL,NULL,NULL,10,NULL,0,1,1700000000000,0,0,'Artist',NULL);
INSERT INTO play_events VALUES (1,'aaaaaaaaaaa',1700000100000,90000,'library',0);
INSERT INTO play_events VALUES (2,'aaaaaaaaaaa',1700000200000,30000,'mix:daily',1);
INSERT INTO play_events VALUES (3,'bbbbbbbbbbb',1700000300000,15000,NULL,0);
INSERT INTO play_events VALUES (4,'local:42',1700000300000,5000,NULL,0);
