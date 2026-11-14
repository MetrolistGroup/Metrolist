package com.metrolist.shared.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleWorkTest {
    @Test
    fun hidingCancelsInFlightWorkAndItsChildrenAndShowingProcessesOnlyCurrentState() =
        runTest {
            val visible = MutableStateFlow(false)
            val artwork = MutableStateFlow("first")
            val started = mutableListOf<String>()
            val cancelled = mutableListOf<String>()
            val childrenCancelled = mutableListOf<String>()
            backgroundScope.launch {
                artwork.collectLatestWhileVisible(visible) { cover ->
                    started += cover
                    try {
                        coroutineScope {
                            launch {
                                try {
                                    awaitCancellation()
                                } finally {
                                    childrenCancelled += cover
                                }
                            }
                            awaitCancellation()
                        }
                    } finally {
                        cancelled += cover
                    }
                }
            }
            runCurrent()
            assertEquals(0, artwork.subscriptionCount.value)
            artwork.value = "second"
            visible.value = true
            runCurrent()
            assertEquals(listOf("second"), started)

            visible.value = false
            runCurrent()
            assertEquals(listOf("second"), cancelled)
            assertEquals(cancelled, childrenCancelled)
            assertEquals(0, artwork.subscriptionCount.value)
            artwork.value = "third"
            runCurrent()
            artwork.value = "current"
            runCurrent()
            assertEquals(listOf("second"), started)

            visible.value = true
            runCurrent()
            assertEquals(listOf("second", "current"), started)
            artwork.value = "edited current"
            runCurrent()
            assertEquals(listOf("second", "current"), cancelled)
            assertEquals(cancelled, childrenCancelled)
            assertEquals(listOf("second", "current", "edited current"), started)
        }
}
