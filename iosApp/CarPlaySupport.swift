import Foundation

enum CarPlayCommand: String, Equatable {
    case togglePlayPause
    case skipToNext
    case skipToPrevious
}

struct CarPlayBrowseRow: Equatable {
    let id: String
    let title: String
    let subtitle: String?
    let isPlayable: Bool
    let isContainer: Bool
}

struct CarPlayNowPlayingSnapshot: Equatable {
    let title: String?
    let artist: String?
    let album: String?
    let isPlaying: Bool
    let canSkipNext: Bool
    let canSkipPrevious: Bool
}

enum CarPlayTemplateMapper {
    static func rootItemId(from index: Int, items: [CarPlayBrowseRow]) -> String? {
        guard items.indices.contains(index) else { return nil }
        return items[index].id
    }

    static func childSongSelection(containerId: String, row: CarPlayBrowseRow) -> (containerId: String, songId: String)? {
        guard row.isPlayable else { return nil }
        let components = row.id.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
        guard components.count == 2 else { return nil }
        let songId = String(components[1])
        guard !songId.isEmpty else { return nil }
        return (containerId, songId)
    }
}

enum CarPlayCommandMapper {
    static func command(forButtonTitle title: String, playTitle: String, pauseTitle: String, nextTitle: String, previousTitle: String) -> CarPlayCommand? {
        switch title {
        case playTitle, pauseTitle:
            return .togglePlayPause
        case nextTitle:
            return .skipToNext
        case previousTitle:
            return .skipToPrevious
        default:
            return nil
        }
    }
}
