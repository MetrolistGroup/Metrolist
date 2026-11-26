import AppIntents
import SwiftUI
import WidgetKit

// Written by IosNowPlayingWidget in shared/src/iosMain.
struct NowPlayingSnapshot: Decodable {
    struct Labels: Decodable {
        let nowPlaying: String
        let description: String
        let playPause: String
        let next: String
        let previous: String
    }

    let title: String?
    let artists: String?
    let isPlaying: Bool
    let positionMs: Double
    let durationMs: Double
    let rate: Double
    let updatedAtMs: Double
    let canPlayPause: Bool
    let canSkipNext: Bool
    let canSkipPrevious: Bool
    let labels: Labels

    static func load() -> NowPlayingSnapshot? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "MetrolistWidgetAppGroupIdentifier") as? String,
              let json = UserDefaults(suiteName: group)?.string(forKey: "MetrolistWidgetSnapshot")
        else { return nil }
        return try? JSONDecoder().decode(NowPlayingSnapshot.self, from: Data(json.utf8))
    }

    // Wall-clock span of the track while it advances; nil when paused, buffering or past its end.
    var playingInterval: ClosedRange<Date>? {
        guard rate > 0, durationMs > 0 else { return nil }
        let start = Date(timeIntervalSince1970: (updatedAtMs - positionMs / rate) / 1_000)
        let end = start.addingTimeInterval(durationMs / rate / 1_000)
        return end > Date() ? start...end : nil
    }

    // Past its projected end the app was killed or suspended, so the stored play state is stale.
    var showsPlaying: Bool { isPlaying && !(rate > 0 && durationMs > 0 && playingInterval == nil) }

    var progress: Double {
        guard durationMs > 0 else { return 0 }
        return rate > 0 ? 1 : min(max(positionMs / durationMs, 0), 1)
    }
}

struct NowPlayingEntry: TimelineEntry {
    let date = Date()
    let snapshot: NowPlayingSnapshot?
}

struct NowPlayingProvider: TimelineProvider {
    func placeholder(in context: Context) -> NowPlayingEntry {
        NowPlayingEntry(snapshot: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (NowPlayingEntry) -> Void) {
        completion(NowPlayingEntry(snapshot: .load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NowPlayingEntry>) -> Void) {
        let entry = NowPlayingEntry(snapshot: .load())
        // The app reloads on every change; re-read at track end in case it was suspended meanwhile.
        let policy = entry.snapshot?.playingInterval.map { TimelineReloadPolicy.after($0.upperBound) } ?? .never
        completion(Timeline(entries: [entry], policy: policy))
    }
}

@main
struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        let labels = NowPlayingSnapshot.load()?.labels
        return StaticConfiguration(kind: "MetrolistNowPlayingWidget", provider: NowPlayingProvider()) { entry in
            NowPlayingWidgetView(snapshot: entry.snapshot)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName(labels?.nowPlaying ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "")
        .description(labels?.description ?? "")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

private struct NowPlayingWidgetView: View {
    let snapshot: NowPlayingSnapshot?

    var body: some View {
        if let snapshot {
            VStack(alignment: .leading, spacing: 8) {
                Text(snapshot.title ?? snapshot.labels.nowPlaying)
                    .font(.headline)
                    .lineLimit(1)
                if let artists = snapshot.artists {
                    Text(artists)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                if let interval = snapshot.playingInterval {
                    ProgressView(timerInterval: interval, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                } else {
                    ProgressView(value: snapshot.progress)
                }
                HStack(spacing: 12) {
                    control("previous", "backward.fill", snapshot.labels.previous, enabled: snapshot.canSkipPrevious)
                    control("playPause", snapshot.showsPlaying ? "pause.fill" : "play.fill", snapshot.labels.playPause, enabled: snapshot.canPlayPause)
                    control("next", "forward.fill", snapshot.labels.next, enabled: snapshot.canSkipNext)
                }
            }
        } else {
            Image(systemName: "music.note")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
        }
    }

    private func control(_ command: String, _ systemImage: String, _ label: String, enabled: Bool) -> some View {
        Button(intent: PlaybackControlIntent(command: command)) {
            Image(systemName: systemImage)
                .resizable()
                .scaledToFit()
                .frame(width: 18, height: 18)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}
