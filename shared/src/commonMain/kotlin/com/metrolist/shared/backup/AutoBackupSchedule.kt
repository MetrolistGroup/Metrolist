package com.metrolist.shared.backup

enum class AutoBackupFrequency(
    val intervalMillis: Long,
) {
    DAILY(24L * 60 * 60 * 1000),
    WEEKLY(7L * 24 * 60 * 60 * 1000),
    MONTHLY(30L * 24 * 60 * 60 * 1000),
    ;

    companion object {
        fun fromSetting(value: String): AutoBackupFrequency = valueOf(value)
    }
}

enum class AutoBackupDestination {
    LOCAL,
    GOOGLE_DRIVE,
    ;

    companion object {
        fun fromSetting(value: String): AutoBackupDestination = valueOf(value)
    }
}

fun isAutoBackupDue(
    lastSuccessEpochMillis: Long,
    nowEpochMillis: Long,
    frequency: AutoBackupFrequency,
): Boolean =
    lastSuccessEpochMillis <= 0 ||
        (nowEpochMillis >= lastSuccessEpochMillis && nowEpochMillis - lastSuccessEpochMillis >= frequency.intervalMillis)
