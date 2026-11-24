import Foundation

enum RecognitionCommand: String, CaseIterable, Codable {
    case recognize = "recognize"

    static func from(userInfo: [AnyHashable: Any]) -> RecognitionCommand? {
        guard let raw = userInfo["command"] as? String else { return nil }
        return RecognitionCommand(rawValue: raw)
    }
}

enum RecognitionEntryPoint: String, Codable {
    case appShortcut = "app_shortcut"
    case controlCenter = "control_center"
    case url = "url"
}
