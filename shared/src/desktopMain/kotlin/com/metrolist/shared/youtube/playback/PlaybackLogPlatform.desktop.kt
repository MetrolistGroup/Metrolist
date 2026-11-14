package com.metrolist.shared.youtube.playback

internal actual fun playbackLogPlatformDetails(): Map<String, String> =
    buildMap {
        put("platform", "desktop")
        put("os", System.getProperty("os.name").orEmpty())
        put("osVersion", System.getProperty("os.version").orEmpty())
        put("osArch", System.getProperty("os.arch").orEmpty())
        put("javaVersion", System.getProperty("java.version").orEmpty())
        put("javaVendor", System.getProperty("java.vendor").orEmpty())
        put("javaVm", System.getProperty("java.vm.name").orEmpty())
    }
