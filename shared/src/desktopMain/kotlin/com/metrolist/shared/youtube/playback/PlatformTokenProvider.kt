package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.TokenProvider

@Suppress("FunctionName")
actual fun PlatformTokenProvider(): TokenProvider = DesktopTokenProvider()
