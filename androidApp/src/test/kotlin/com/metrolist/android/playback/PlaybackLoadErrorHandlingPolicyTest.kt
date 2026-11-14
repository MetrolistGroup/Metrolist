package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.metrolist.shared.playback.PlaybackHttpStatusCarrier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaybackLoadErrorHandlingPolicyTest {
    private val dataSpec = DataSpec.Builder().setUri("https://example.test/audio").build()
    private val policy = PlaybackLoadErrorHandlingPolicy()

    @Test
    fun directUrlsRequiringReplacementFailImmediatelyForBothHttpBackends() {
        for (status in listOf(401, 403, 404, 410)) {
            for (error in errors(status)) {
                assertEquals(C.TIME_UNSET, retryDelay(IOException("wrapped", error), 1))
            }
        }
    }

    @Test
    fun transientHttpFailuresKeepThreeBoundedRetries() {
        for (status in listOf(408, 425, 429, 500, 502, 503)) {
            for (error in errors(status)) {
                for (attempt in 1..3) {
                    assertEquals((attempt - 1) * 1000L, retryDelay(IOException("wrapped", error), attempt))
                }
                assertEquals(C.TIME_UNSET, retryDelay(error, 4))
            }
        }
    }

    @Test
    fun audioNetworkRecoveryRetainsTheBufferPastTheNormalRetryBudget() {
        val recoveryPolicy = PlaybackLoadErrorHandlingPolicy(keepBufferDuringNetworkRecovery = true)
        assertEquals(Int.MAX_VALUE, recoveryPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        for (status in listOf(408, 425, 500, 502, 503)) {
            for (error in errors(status)) {
                assertEquals(4_000L, retryDelay(IOException("wrapped", error), 4, recoveryPolicy))
                assertEquals(10_000L, retryDelay(error, 100, recoveryPolicy))
            }
        }
        assertEquals(C.TIME_UNSET, retryDelay(errors(403).first(), 1, recoveryPolicy))
    }

    private fun errors(status: Int): List<IOException> =
        listOf(
            object : IOException(), PlaybackHttpStatusCarrier {
                override val playbackHttpStatusCode = status
            },
            HttpDataSource.InvalidResponseCodeException(status, null, null, emptyMap(), dataSpec, byteArrayOf()),
        )

    private fun retryDelay(
        error: IOException,
        count: Int,
        policy: PlaybackLoadErrorHandlingPolicy = this.policy,
    ): Long =
        policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo.Builder(1, dataSpec, 0).build(),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                error,
                count,
            ),
        )
}
