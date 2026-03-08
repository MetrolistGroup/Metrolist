PRAGMA user_version = 1;
CREATE TABLE song (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    duration INTEGER NOT NULL,
    liked INTEGER NOT NULL,
    artistId INTEGER,
    create_date INTEGER,
    download_state INTEGER NOT NULL
);
CREATE TABLE artist (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);
CREATE TABLE playlist (
    playlistId INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);
CREATE TABLE playlist_song (
    id INTEGER NOT NULL PRIMARY KEY,
    playlistId INTEGER NOT NULL,
    songId TEXT NOT NULL,
    idInPlaylist INTEGER NOT NULL
);
CREATE TABLE download (
    id TEXT NOT NULL PRIMARY KEY
);
INSERT INTO artist VALUES (7, 'Fixture artist');
INSERT INTO song VALUES ('fixture-song', 'Fixture song', 123, 1, 7, 1700000000000, 0);
INSERT INTO playlist VALUES (9, 'Fixture playlist');
INSERT INTO playlist_song VALUES (11, 9, 'fixture-song', 0);
INSERT INTO download VALUES ('legacy-download');
