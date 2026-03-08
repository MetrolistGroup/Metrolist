package com.metrolist.android.spotifyimport

import com.metrolist.shared.network.sharedHttpEngine
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import meow.nyx.spotlib.LogLevel
import meow.nyx.spotlib.SpotifyClient
import meow.nyx.spotlib.SpotifyConfig
import meow.nyx.spotlib.SpotifyLogger
import timber.log.Timber

internal val androidSpotifyImportManager: SpotifyImportManager by lazy(::createAndroidSpotifyImportManager)

private fun createAndroidSpotifyImportManager(): SpotifyImportManager =
    SpotifyImportManager(
        clientFactory = {
            SpotifyClient.create(
                SpotifyConfig(
                    engine = sharedHttpEngine,
                    logLevel = LogLevel.SUMMARY,
                    logger = SpotifyLogger { _, message -> Timber.tag("SpotifyApi").d(message) },
                ),
            )
        },
        loadStoredSession = { Settings.shared.spotifySession },
        saveStoredSession = { Settings.shared.spotifySession = it },
    )
