package com.metrolist.shared.model

fun String.isEditablePlaylistId(): Boolean {
    val id = removePrefix("VL")
    return id.isNotBlank() &&
        id !in setOf("LM", "SE", "liked", "downloaded") &&
        listOf("VL", "MPRE", "OLAK", "RD", "FE", "MPSP", "LP", "LM:").none { id.startsWith(it) }
}

val PlaylistItem.canEdit: Boolean
    get() = isEditable && (isLocal || id.isEditablePlaylistId())
