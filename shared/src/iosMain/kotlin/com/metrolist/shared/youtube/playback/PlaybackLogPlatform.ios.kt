package com.metrolist.shared.youtube.playback

import platform.UIKit.UIDevice

internal actual fun playbackLogPlatformDetails(): Map<String, String> =
    buildMap {
        put("platform", "ios")
        put("os", "iOS")
        put("osVersion", UIDevice.currentDevice.systemVersion)
        put("device", UIDevice.currentDevice.model)
    }
