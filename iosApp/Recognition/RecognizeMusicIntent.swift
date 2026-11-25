import AppIntents

@available(iOS 16.0, *)
struct RecognizeMusicIntent: AppIntent {
    static var title: LocalizedStringResource = "Recognize Music"
    static var description = IntentDescription("Recognize music playing nearby.")
    static var openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = await IosRecognitionManager.shared.recognize()
        return .result(dialog: IntentDialog(stringLiteral: message))
    }
}
