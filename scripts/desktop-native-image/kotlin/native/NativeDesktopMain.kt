package com.metrolist.desktop.nativeimage

import com.metrolist.desktop.utils.configureDesktopJvmErrorFile
import java.nio.file.Path
import com.metrolist.desktop.main as desktopMain

fun main(args: Array<String>) {
    if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
        val executable =
            Path.of(
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElseThrow(),
            )
        val home = executable.toAbsolutePath().parent
        System.setProperty("java.home", home.toString())
        val resources =
            if (System.getProperty("os.name").startsWith("Mac")) {
                home.resolve("../Resources")
            } else {
                home.resolve("resources")
            }
        System.setProperty("compose.application.resources.dir", resources.normalize().toString())
        configureDesktopJvmErrorFile()
        // Native executables have no jstack; this writes every Java stack to native_err_pid*.log for hang reports.
        System.getenv("METROLIST_NATIVE_THREAD_DUMP_SECONDS")?.toLongOrNull()?.let { seconds ->
            Thread.ofPlatform().daemon().name("native-thread-dump").start {
                Thread.sleep(seconds * 1000)
                for ((thread, stack) in Thread.getAllStackTraces()) {
                    System.err.println("\"${thread.name}\" ${thread.state}")
                    stack.forEach { System.err.println("\tat $it") }
                }
                System.err.flush()
            }
        }
    }
    if (System.getProperty("metrolist.native.backendSmoke").toBoolean()) {
        nativeBackendSmoke()
    } else {
        desktopMain(args)
    }
}
