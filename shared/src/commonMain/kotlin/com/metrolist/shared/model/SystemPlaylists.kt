package com.metrolist.shared.model

internal fun PlaylistItem.isLikedMusicPlaylist(): Boolean = id.isLikedMusicPlaylistId()

internal fun PlaylistItem.isSystemPlaylist(): Boolean = id.isSystemPlaylistId()

// Stored playlist entities keep these IDs in browseId without the VL prefix, so both forms match.
internal fun String.isSystemPlaylistId(): Boolean =
    isLikedMusicPlaylistId() || equals("RDPN", true) || equals("VLRDPN", true) || equals("SE", true) || equals("VLSE", true)

private fun String.isLikedMusicPlaylistId(): Boolean = equals("LM", true) || equals("VLLM", true) || startsWith("LM:")
