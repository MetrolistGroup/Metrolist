@file:Suppress("FunctionName")

package com.metrolist.desktop.utils

import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.playback.PlayerState
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

object NativeMemoryTrimmer {
    private const val SETTLE_DELAY_MS = 5_000L
    private const val ARENA_ENV = "MALLOC_ARENA_MAX"
    private const val LINUX_ARENA_LIMIT = "2"
    private const val CLOSE_RANGE_CLOEXEC = 1 shl 2

    private val explicitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val trimAction: (() -> Unit)? by lazy {
        runCatching {
            when {
                Platform.isLinux() -> {
                    val allocator = Native.load(Platform.C_LIBRARY_NAME, LinuxAllocator::class.java)
                    val action: () -> Unit = { allocator.malloc_trim(NativeLong(0L)) }
                    action
                }

                Platform.isWindows() -> {
                    val allocator = Native.load("msvcrt", WindowsAllocator::class.java)
                    val action: () -> Unit = { allocator._heapmin() }
                    action
                }

                Platform.isMac() -> {
                    val allocator = Native.load(Platform.C_LIBRARY_NAME, MacAllocator::class.java)
                    val zone = requireNotNull(allocator.malloc_default_zone())
                    val action: () -> Unit = { allocator.malloc_zone_pressure_relief(zone, NativeLong(0L)) }
                    action
                }

                else -> {
                    null
                }
            }
        }.getOrNull()
    }

    fun limitArenasByReexec() {
        if (!Platform.isLinux() || System.getenv(ARENA_ENV) != null) return
        runCatching {
            fun nulSeparated(path: String) = File(path).readText().split('\u0000').filter(String::isNotEmpty)
            val argv = nulSeparated("/proc/self/cmdline")
            if (argv.isEmpty()) return
            val env = nulSeparated("/proc/self/environ") + "$ARENA_ENV=$LINUX_ARENA_LIMIT"
            val libc = Native.load(Platform.C_LIBRARY_NAME, LinuxAllocator::class.java)
            // Descriptors the JVM already opened (jars) must not leak into the new image; stdio stays.
            libc.close_range(3, -1, CLOSE_RANGE_CLOEXEC)
            // Resolve the link so the process name (comm) stays the launcher's, not "exe".
            val executable = File("/proc/self/exe").toPath().toRealPath().toString()
            libc.execve(executable, argv.toTypedArray(), env.toTypedArray())
        }
    }

    fun initialize() {
        AppState.scope.launch {
            trimAfterEvents(
                merge(
                    PlayerState.player.currentSong
                        .mapNotNull { it?.id }
                        .distinctUntilChanged()
                        .map { },
                    PlayerState.player.isPlaying
                        .drop(1)
                        .filter { !it }
                        .map { },
                    explicitRequests,
                ),
            ) {
                withContext(Dispatchers.IO) {
                    runCatching { trimAction?.invoke() }
                }
            }
        }
    }

    fun schedule() {
        explicitRequests.tryEmit(Unit)
    }

    internal suspend fun trimAfterEvents(
        events: Flow<Unit>,
        settleDelayMs: Long = SETTLE_DELAY_MS,
        trim: suspend () -> Unit,
    ) {
        events.collectLatest {
            delay(settleDelayMs.milliseconds)
            trim()
        }
    }

    private interface LinuxAllocator : Library {
        fun malloc_trim(pad: NativeLong): Int

        fun close_range(
            first: Int,
            last: Int,
            flags: Int,
        ): Int

        fun execve(
            path: String,
            argv: Array<String>,
            envp: Array<String>,
        ): Int
    }

    private interface WindowsAllocator : Library {
        fun _heapmin(): Int
    }

    private interface MacAllocator : Library {
        fun malloc_default_zone(): Pointer?

        fun malloc_zone_pressure_relief(
            zone: Pointer,
            goal: NativeLong,
        ): NativeLong
    }
}
