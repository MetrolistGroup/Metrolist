import XCTest
@testable import Metrolist

final class CarPlayTemplateMapperTests: XCTestCase {
    func testRootItemIdReturnsItemAtIndex() {
        let items = [
            CarPlayBrowseRow(id: "liked_songs", title: "Liked songs", subtitle: nil, isPlayable: false, isContainer: true),
            CarPlayBrowseRow(id: "playlists", title: "Playlists", subtitle: nil, isPlayable: false, isContainer: true),
        ]

        XCTAssertEqual(CarPlayTemplateMapper.rootItemId(from: 1, items: items), "playlists")
        XCTAssertNil(CarPlayTemplateMapper.rootItemId(from: 2, items: items))
    }

    func testChildSongSelectionRequiresPlayableEncodedId() {
        let playable = CarPlayBrowseRow(id: "liked_songs|song-1", title: "Song", subtitle: "Artist", isPlayable: true, isContainer: false)
        let nonPlayable = CarPlayBrowseRow(id: "playlist-1", title: "Playlist", subtitle: nil, isPlayable: false, isContainer: true)

        XCTAssertEqual(
            CarPlayTemplateMapper.childSongSelection(containerId: "liked_songs", row: playable)?.songId,
            "song-1"
        )
        XCTAssertNil(CarPlayTemplateMapper.childSongSelection(containerId: "playlists", row: nonPlayable))
    }
}

final class CarPlayCommandMapperTests: XCTestCase {
    func testCommandMappingUsesLocalizedTitles() {
        XCTAssertEqual(
            CarPlayCommandMapper.command(forButtonTitle: "Reproducir", playTitle: "Reproducir", pauseTitle: "Pausa", nextTitle: "Siguiente", previousTitle: "Anterior"),
            .togglePlayPause
        )
        XCTAssertEqual(
            CarPlayCommandMapper.command(forButtonTitle: "Siguiente", playTitle: "Reproducir", pauseTitle: "Pausa", nextTitle: "Siguiente", previousTitle: "Anterior"),
            .skipToNext
        )
        XCTAssertNil(
            CarPlayCommandMapper.command(forButtonTitle: "Unknown", playTitle: "Play", pauseTitle: "Pause", nextTitle: "Next", previousTitle: "Previous")
        )
    }
}
