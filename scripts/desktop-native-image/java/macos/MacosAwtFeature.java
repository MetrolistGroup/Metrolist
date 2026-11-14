/* Encoding binding adapted from Nucleus 67e9920f171a6d569e520d95b53c8e594b44a4d4.
 * Copyright (c) 2026 Elie Gambache. MIT license and provenance:
 * resources/META-INF/native-image/com.metrolist/desktop-experiment/nucleus/README.md
 */
package com.metrolist.desktop.nativeimage;

import com.oracle.svm.core.jdk.RuntimeSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.word.PointerBase;

@Platforms(Platform.HOSTED_ONLY.class)
public final class MacosAwtFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // The stateless hook instance is stored in the image heap.
        RuntimeClassInitialization.initializeAtBuildTime(MacosAwtStartup.class);
        RuntimeSupport.getRuntimeSupport().addStartupHook(new MacosAwtStartup());
    }
}

@Platforms(Platform.DARWIN.class)
final class MacosAwtStartup implements RuntimeSupport.Hook {
    @CFunction("nucleus_init_platform_encoding")
    private static native int initializeEncoding(PointerBase env);

    @Override
    public void execute(boolean firstIsolate) {
        Path home = Path.of(ProcessHandle.current().info().command().orElseThrow()).toAbsolutePath().getParent();
        System.setProperty("java.library.path", home.toString());
        System.setProperty("sun.boot.library.path", home.toString());
        Path fontConfig = home.resolve("../Resources/fontconfig.bfc").normalize();
        if (System.getProperty("sun.awt.fontconfig", "").isBlank() && Files.isRegularFile(fontConfig)) {
            System.setProperty("sun.awt.fontconfig", fontConfig.toString());
        }
        // The dylibs need a JNIEnv from SubstrateVM before any AWT JNI_OnLoad runs.
        if (initializeEncoding(CurrentIsolate.getCurrentThread()) == 0) {
            throw new UnsatisfiedLinkError("Cannot initialize platform encoding in bundled libjava.dylib");
        }
    }
}
