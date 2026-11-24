import Foundation
import Shared

struct RecognitionPayload: Codable, Equatable {
    let trackId: String
    let title: String
    let artist: String
    let album: String?
    let coverArtUrl: String?
    let coverArtHqUrl: String?
    let genre: String?
    let releaseDate: String?
    let label: String?
    let shazamUrl: String?
    let appleMusicUrl: String?
    let spotifyUrl: String?
    let isrc: String?
    let youtubeVideoId: String?
    let recognizedAtEpochMillis: Int64

    init(
        trackId: String,
        title: String,
        artist: String,
        album: String?,
        coverArtUrl: String?,
        coverArtHqUrl: String?,
        genre: String?,
        releaseDate: String?,
        label: String?,
        shazamUrl: String?,
        appleMusicUrl: String?,
        spotifyUrl: String?,
        isrc: String?,
        youtubeVideoId: String?,
        recognizedAtEpochMillis: Int64
    ) {
        self.trackId = trackId
        self.title = title
        self.artist = artist
        self.album = album
        self.coverArtUrl = coverArtUrl
        self.coverArtHqUrl = coverArtHqUrl
        self.genre = genre
        self.releaseDate = releaseDate
        self.label = label
        self.shazamUrl = shazamUrl
        self.appleMusicUrl = appleMusicUrl
        self.spotifyUrl = spotifyUrl
        self.isrc = isrc
        self.youtubeVideoId = youtubeVideoId
        self.recognizedAtEpochMillis = recognizedAtEpochMillis
    }

    init(result: RecognitionResult, recognizedAtEpochMillis: Int64) {
        self.init(
            trackId: result.trackId,
            title: result.title,
            artist: result.artist,
            album: result.album,
            coverArtUrl: result.coverArtUrl,
            coverArtHqUrl: result.coverArtHqUrl,
            genre: result.genre,
            releaseDate: result.releaseDate,
            label: result.label,
            shazamUrl: result.shazamUrl,
            appleMusicUrl: result.appleMusicUrl,
            spotifyUrl: result.spotifyUrl,
            isrc: result.isrc,
            youtubeVideoId: result.youtubeVideoId,
            recognizedAtEpochMillis: recognizedAtEpochMillis
        )
    }

    func toResult() -> RecognitionResult {
        RecognitionResult(
            trackId: trackId,
            title: title,
            artist: artist,
            album: album,
            coverArtUrl: coverArtUrl,
            coverArtHqUrl: coverArtHqUrl,
            genre: genre,
            releaseDate: releaseDate,
            label: label,
            shazamUrl: shazamUrl,
            appleMusicUrl: appleMusicUrl,
            spotifyUrl: spotifyUrl,
            isrc: isrc,
            youtubeVideoId: youtubeVideoId
        )
    }

    func encode() throws -> String {
        let data = try JSONEncoder().encode(self)
        return String(decoding: data, as: UTF8.self)
    }

    static func decode(_ string: String) throws -> RecognitionPayload {
        let data = Data(string.utf8)
        return try JSONDecoder().decode(RecognitionPayload.self, from: data)
    }
}
