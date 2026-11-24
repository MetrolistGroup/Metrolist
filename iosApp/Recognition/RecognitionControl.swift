import AppIntents
import SwiftUI
import WidgetKit

@available(iOS 18.0, *)
struct RecognitionControl: ControlWidget {
    static let kind = "com.metrolist.recognition.control"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind) {
            ControlWidgetButton(action: RecognizeMusicIntent()) {
                Label("Recognize Music", systemImage: "shazam.logo")
            }
        }
        .displayName("Recognize Music")
        .description("Recognize music playing nearby.")
    }
}
