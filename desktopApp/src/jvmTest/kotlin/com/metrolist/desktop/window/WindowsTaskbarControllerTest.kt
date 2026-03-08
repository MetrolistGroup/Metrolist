package com.metrolist.desktop.window

import dev.nucleusframework.launcher.windows.ThumbnailToolbarButton
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsTaskbarControllerTest {
    @Test
    fun waitsForTheTaskbarMessageUsesLatestStateAndRegistersOnce() {
        val queue = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        var valid = true
        val controller =
            WindowsTaskbarController(
                Executor(queue::addLast),
                { valid },
                {
                    calls += "register:${it.single().tooltip}"
                    true
                },
                {
                    calls += "update:${it.single().tooltip}"
                    true
                },
                { calls += "unregister" },
            )

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
        controller.setButtons(listOf(ThumbnailToolbarButton(0, "old")))
        controller.setButtons(listOf(ThumbnailToolbarButton(0, "latest")))
        drain()
        assertTrue(calls.isEmpty())
        controller.taskbarCreated()
        drain()
        controller.setButtons(listOf(ThumbnailToolbarButton(0, "playing")))
        drain()
        assertEquals(listOf("register:latest", "update:playing"), calls)
        valid = false
        controller.setButtons(listOf(ThumbnailToolbarButton(0, "destroyed")))
        controller.close()
        drain()
        assertEquals(2, calls.size)
    }

    @Test
    fun disposalRejectsQueuedRegistrationAndUnregistersOnTheWorker() {
        val queue = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()

        fun controller() =
            WindowsTaskbarController(
                Executor(queue::addLast),
                { true },
                {
                    calls += "register"
                    true
                },
                { true },
                { calls += "unregister" },
            )

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
        val first = controller()
        first.taskbarCreated()
        first.setButtons(listOf(ThumbnailToolbarButton(0, "play")))
        first.close()
        drain()
        assertTrue(calls.isEmpty())
        val next = controller()
        next.taskbarCreated()
        next.setButtons(listOf(ThumbnailToolbarButton(0, "play")))
        drain()
        next.close()
        assertEquals(listOf("register"), calls)
        drain()
        assertEquals(listOf("register", "unregister"), calls)
    }

    @Test
    fun failedRegistrationDoesNotEnableUpdates() {
        val calls = mutableListOf<String>()
        val controller =
            WindowsTaskbarController(
                Executor(Runnable::run),
                { true },
                {
                    calls += "register"
                    calls.size > 1
                },
                {
                    calls += "update"
                    true
                },
                {},
            )
        controller.taskbarCreated()
        repeat(3) { controller.setButtons(listOf(ThumbnailToolbarButton(0, "play"))) }
        assertEquals(listOf("register", "register", "update"), calls)
    }
}
