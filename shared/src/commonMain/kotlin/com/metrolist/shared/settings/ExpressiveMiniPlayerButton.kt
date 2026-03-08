package com.metrolist.shared.settings

enum class ExpressiveMiniPlayerButton {
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
    PLAYLIST,
    SUBSCRIBE,
    LIKE,
}

val Settings.expressiveMiniPlayerButtons: List<ExpressiveMiniPlayerButton>
    get() =
        buildList {
            if (showExpressiveMiniPlayerPreviousButton) add(ExpressiveMiniPlayerButton.PREVIOUS)
            if (showExpressiveMiniPlayerPlayPauseButton) add(ExpressiveMiniPlayerButton.PLAY_PAUSE)
            if (showExpressiveMiniPlayerNextButton) add(ExpressiveMiniPlayerButton.NEXT)
            if (showExpressiveMiniPlayerPlaylistButton) add(ExpressiveMiniPlayerButton.PLAYLIST)
            if (showExpressiveMiniPlayerSubscribeButton) add(ExpressiveMiniPlayerButton.SUBSCRIBE)
            if (showExpressiveMiniPlayerLikeButton) add(ExpressiveMiniPlayerButton.LIKE)
        }
