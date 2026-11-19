package com.metrolist.shared.backup

enum class AutoBackupFrequency(
    val intervalMillis: Long,
) {
    DAILY(24L * 60 * 60 * 1000),
    WEEKLY(7L * 24 * 60 * 60 * 1000),
    MONTHLY(30L * 24 * 60 * 60 * 1000),
    ;

    companion object {
        fun fromSetting(value: String): AutoBackupFrequency =
            entries.firstOrNull { it.name == value }
                ?: error("Unsupported automatic backup frequency")
    }
}

fun isAutoBackupDue(
    lastSuccessEpochMillis: Long,
    nowEpochMillis: Long,
    frequency: AutoBackupFrequency,
): Boolean =
    lastSuccessEpochMillis <= 0 ||
        (nowEpochMillis >= lastSuccessEpochMillis && nowEpochMillis - lastSuccessEpochMillis >= frequency.intervalMillis)
