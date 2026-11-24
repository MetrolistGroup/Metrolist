import XCTest
@testable import Metrolist

final class RecognitionCommandTests: XCTestCase {
    func testRecognizeCommandFromUserInfo() {
        let command = RecognitionCommand.from(userInfo: ["command": "recognize"])
        XCTAssertEqual(command, .recognize)
    }

    func testUnknownCommandReturnsNil() {
        XCTAssertNil(RecognitionCommand.from(userInfo: ["command": "unknown"]))
    }

    func testPayloadRoundTrip() throws {
        let payload = RecognitionPayload(
            trackId: "track-id",
            title: "Title",
            artist: "Artist",
            album: "Album",
            coverArtUrl: "https://example.com/a.jpg",
            coverArtHqUrl: "https://example.com/hq.jpg",
            genre: "Pop",
            releaseDate: "2024",
            label: "Label",
            shazamUrl: "https://shazam.com/t",
            appleMusicUrl: "https://music.apple.com/t",
            spotifyUrl: "https://open.spotify.com/t",
            isrc: "ISRC",
            youtubeVideoId: "videoId",
            recognizedAtEpochMillis: 1234
        )
        let encoded = try payload.encode()
        let decoded = try RecognitionPayload.decode(encoded)
        XCTAssertEqual(decoded, payload)
    }
}
