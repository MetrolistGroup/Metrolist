package com.metrolist.shared.recognition

enum class RecognitionCommand(
    val rawValue: String,
) {
    RECOGNIZE("recognize"),
    ;

    companion object {
        fun from(rawValue: String?): RecognitionCommand? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class RecognitionEntryPoint {
    APP_SHORTCUT,
    CONTROL_CENTER,
    URL,

    ;

    val rawValue: String
        get() =
            when (this) {
                APP_SHORTCUT -> "app_shortcut"
                CONTROL_CENTER -> "control_center"
                URL -> "url"
            }
}
