import AppIntents

// Built into the app and the widget. iOS runs AudioPlaybackIntent in the app process, where handler is set at launch.
@available(iOS 17.0, *)
struct PlaybackControlIntent: AudioPlaybackIntent {
    // Hidden from Shortcuts and Siri, so these resource keys are never displayed.
    static let title: LocalizedStringResource = "now_playing"
    static let isDiscoverable = false
    @MainActor static var handler: ((String) -> Void)?

    @Parameter(title: "play_pause")
    var command: String

    init(command: String) {
        self.command = command
    }

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        Self.handler?(command)
        return .result()
    }
}
