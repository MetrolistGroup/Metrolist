package com.metrolist.shared.model

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

@Stable
@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val songCount: Int?,
    val author: String? = null,
    val isAutoPlaylist: Boolean = false,
)
