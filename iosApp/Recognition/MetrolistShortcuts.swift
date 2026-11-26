import AppIntents

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
