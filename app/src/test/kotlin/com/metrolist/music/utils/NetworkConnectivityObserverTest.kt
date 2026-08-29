package com.metrolist.music.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkConnectivityObserverTest {
    @Test
    fun networkStatusIsSharedAcrossCollectors() = runBlocking {
        val observer = NetworkConnectivityObserver(ApplicationProvider.getApplicationContext<Context>())

        withTimeout(1_000) {
            val firstCollector = async { observer.networkStatus.first() }
            val secondCollector = async { observer.networkStatus.first() }

            assertEquals(firstCollector.await(), secondCollector.await())
        }
    }
}
