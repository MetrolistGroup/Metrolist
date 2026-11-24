import XCTest
@testable import Metrolist

final class RecognitionPayloadTests: XCTestCase {
    func testPayloadRoundTripFields() throws {
        let payload = RecognitionPayload(
            trackId: "id",
            title: "Song",
            artist: "Artist",
            album: "Album",
            coverArtUrl: nil,
            coverArtHqUrl: nil,
            genre: nil,
            releaseDate: nil,
            label: nil,
            shazamUrl: nil,
            appleMusicUrl: nil,
            spotifyUrl: nil,
            isrc: nil,
            youtubeVideoId: "vid",
            recognizedAtEpochMillis: 42
        )
        let decoded = try RecognitionPayload.decode(payload.encode())
        XCTAssertEqual(decoded.trackId, "id")
        XCTAssertEqual(decoded.youtubeVideoId, "vid")
        XCTAssertEqual(decoded.recognizedAtEpochMillis, 42)
    }
}
