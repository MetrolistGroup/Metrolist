package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.innertubex.sabr.SabrResponseDiagnostics
import io.ktor.client.HttpClient
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

@OptIn(ExperimentalSabrApi::class)
fun sabrAudioPlaybackChunks(
    httpClient: HttpClient,
    bootstrap: SabrBootstrap,
    playbackPositionMs: () -> Long,
    initialPlayerTimeMs: Long,
    onResponse: ((SabrResponseDiagnostics) -> Unit)? = null,
    requestRetryDelay: suspend (Long) -> Unit = { delay(it) },
    logger: InnerTubeLogger = InnerTubeLogger.NONE,
    tokenSupplier: (suspend () -> ByteArray?)? = null,
    onAttestationPending: (() -> Unit)? = null,
): Flow<SabrChunk> =
    flow {
        val tokenAvailable = atomic(bootstrap.poToken?.isNotEmpty() == true)
        var tokenObserved = tokenAvailable.value
        var pendingReported = false
        emitAll(
            SabrAudioStream(
                httpClient = httpClient,
                bootstrap = bootstrap,
                maxRequestsWithoutProgress = MAX_NO_PROGRESS_REQUESTS,
                playbackPositionMs = playbackPositionMs,
                onResponse = { response ->
                    onResponse?.invoke(response)
                    // A late token can arrive during a request. Allow that response before judging its attestation.
                    if (tokenObserved && response.protectionStatus == PROTECTION_ATTESTATION_PENDING) {
                        if (!pendingReported) {
                            pendingReported = true
                            onAttestationPending?.invoke()
                        }
                        if (response.requestNumber > 0) {
                            throw SabrProtocolException(
                                "SABR attestation remains pending with a PO token",
                                SabrFailureKind.ATTESTATION_REQUIRED,
                            )
                        }
                    }
                    tokenObserved = tokenAvailable.value
                },
                requestRetryDelay = requestRetryDelay,
                initialPlayerTimeMs = initialPlayerTimeMs,
                logger = logger,
                tokenSupplier =
                    tokenSupplier?.let { supplier ->
                        { supplier()?.also { if (it.isNotEmpty() && it.size <= 16 * 1024) tokenAvailable.value = true } }
                    },
            ).chunks(),
        )
    }

// The first response can request a backoff before it supplies audio.
private const val MAX_NO_PROGRESS_REQUESTS = 2
private const val PROTECTION_ATTESTATION_PENDING = 2
