package com.metrolist.desktop.window

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsTaskbarExecutorTest {
    @Test
    fun queuesUpdatesBeforeWakingTheMessageLoop() {
        val calls = mutableListOf<Int>()
        lateinit var executor: WindowsTaskbarExecutor
        executor = WindowsTaskbarExecutor { executor.runPendingTasks() }

        executor.execute { calls += 1 }
        executor.execute { calls += 2 }

        assertEquals(listOf(1, 2), calls)
    }

    @Test
    fun drainsUpdatesInOrderAndSurvivesAFailedNativeCall() {
        val calls = mutableListOf<Int>()
        var wakeUps = 0
        val executor = WindowsTaskbarExecutor { wakeUps++ }

        executor.execute { calls += 1 }
        executor.execute { error("Native call failed") }
        executor.execute { calls += 2 }
        assertEquals(3, wakeUps)
        assertEquals(emptyList(), calls)

        executor.runPendingTasks()
        executor.runPendingTasks()
        assertEquals(listOf(1, 2), calls)

        executor.execute { calls += 3 }
        executor.runPendingTasks()
        assertEquals(listOf(1, 2, 3), calls)
    }
}
