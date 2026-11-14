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
    }
    if (System.getProperty("metrolist.native.backendSmoke").toBoolean()) {
        nativeBackendSmoke()
    } else {
        desktopMain(args)
    }
}
