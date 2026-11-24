import AppIntents
import Foundation
import Shared

@available(iOS 16.0, *)
struct RecognizeMusicIntent: AppIntent {
    static var title: LocalizedStringResource = "Recognize Music"
    static var description = IntentDescription("Recognize music playing nearby.")
    static var openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let result = try await IosRecognitionManager.shared.recognize(from: .appShortcut)
        let message = "\(result.title) - \(result.artist)"
        return .result(dialog: IntentDialog(stringLiteral: message))
    }
}

@available(iOS 16.0, *)
struct MetrolistShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: RecognizeMusicIntent(),
            phrases: [
                "Recognize music in \(.applicationName)",
                "Identify song in \(.applicationName)",
                "Shazam in \(.applicationName)",
            ],
            shortTitle: "Recognize Music",
            systemImageName: "shazam.logo"
        )
    }
}
