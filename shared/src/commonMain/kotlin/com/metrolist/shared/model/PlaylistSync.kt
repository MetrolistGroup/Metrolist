package com.metrolist.shared.model

fun String.isSyncablePlaylistId(): Boolean {
    val id = removePrefix("VL")
    return !id.startsWith("auto:") && (id.isEditablePlaylistId() || id.startsWith("OLAK") || id.startsWith("RDCL"))
}
