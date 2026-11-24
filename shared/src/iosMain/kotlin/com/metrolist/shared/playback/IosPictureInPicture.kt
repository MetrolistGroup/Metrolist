package com.metrolist.shared.playback

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayerLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.UIKit.UIDevice
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
internal fun iosPictureInPictureSupported(): Boolean =
    AVPictureInPictureController.isPictureInPictureSupported()

@OptIn(ExperimentalForeignApi::class)
internal fun requestIosPictureInPicture(
    controller: IosPlaybackController,
    isPlaying: Boolean,
    hasVideo: Boolean,
) {
    if (!isPlaying || !hasVideo || !iosPictureInPictureSupported()) return
    val layer = controller.pictureInPicturePlayerLayer() as? AVPlayerLayer ?: return
    IosPictureInPictureBridge.start(layer)
}

@OptIn(ExperimentalForeignApi::class)
private object IosPictureInPictureBridge : NSObject(), AVPictureInPictureControllerDelegateProtocol {
    private var controller: AVPictureInPictureController? = null

    fun start(layer: AVPlayerLayer) {
        val pip = controller?.takeIf { it.playerLayer === layer } ?: AVPictureInPictureController(playerLayer = layer).also {
            it.delegate = this
            controller = it
        }
        if (pip.isPictureInPicturePossible()) {
            pip.startPictureInPicture()
        }
    }
}
