package com.metrolist.shared.model

internal fun PlaylistItem.isLikedMusicPlaylist(): Boolean = id.equals("LM", true) || id.equals("VLLM", true) || id.startsWith("LM:")

internal fun PlaylistItem.isSystemPlaylist(): Boolean =
    isLikedMusicPlaylist() || id.equals("RDPN", true) || id.equals("VLRDPN", true) ||
        id.equals("SE", true) || id.equals("VLSE", true)
