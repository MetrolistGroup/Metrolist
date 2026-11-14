package com.metrolist.shared.db.entities

import androidx.room3.Entity

@Entity(tableName = "history_stat_exclusion", primaryKeys = ["kind", "value"])
data class HistoryStatExclusion(
    val kind: String,
    val value: String,
    val encodedValue: String = value,
)
