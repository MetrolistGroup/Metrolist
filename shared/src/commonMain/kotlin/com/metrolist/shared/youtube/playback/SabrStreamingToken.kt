package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.sabr.SabrBootstrap
import kotlin.io.encoding.Base64

internal fun sabrStreamingTokenSupplier(
    bootstrap: SabrBootstrap,
    profileId: String,
    innerTube: InnerTube,
    provider: TokenProvider?,
): (suspend () -> ByteArray?)? {
    if (bootstrap.poToken != null || provider == null) return null
    val manifest = PlaybackClientCatalog.findManifest(profileId.substringBefore("__")) ?: return null
    // Native tokenless profiles have no declared web-token rule; try page attestation and let rejection recover.
    val kinds =
        manifest.poTokens.gvs.providers
            .ifEmpty { setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION) }
    if (kinds.none { it in provider.capabilities.providers }) return null
    val session = innerTube.sessionSnapshot()
    return supplier@{
        if (innerTube.sessionSnapshot().generation != session.generation || kinds.none { it in provider.capabilities.providers }) {
            return@supplier null
        }
        val visitor =
            bootstrap.tokenVisitorData?.takeIf(String::isNotBlank)
                ?: session.visitorData?.takeIf(String::isNotBlank)
                ?: innerTube.fetchFreshVisitorData(session)
                ?: return@supplier null

        fun isCurrent(): Boolean =
            innerTube.sessionSnapshot().generation == session.generation ||
                (session.visitorData.isNullOrBlank() && innerTube.sessionSnapshotWithVisitorData(session, visitor) != null)
        if (!isCurrent()) return@supplier null
        val result = provider.getPoToken(bootstrap.videoId, visitor, session.cookie.takeIf { manifest.request.cookies })
        if (!isCurrent() || result?.visitorData != visitor) return@supplier null
        val token = result.streamingDataToken
        if (token.isBlank() || token.length > 21848) return@supplier null
        val standard = token.replace('-', '+').replace('_', '/')
        Base64
            .decode(standard.padEnd(standard.length + (4 - standard.length % 4) % 4, '='))
            .takeIf { it.isNotEmpty() && it.size <= 16 * 1024 }
    }
}
