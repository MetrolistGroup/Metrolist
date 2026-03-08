package com.metrolist.desktop.nativeimage;

import com.metrolist.shared.utils.XdgUtils;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.io.File;

final class NativeCrashLog {
    private static boolean configured;

    static void configure() {
        if (configured) return;
        try {
            File directory = new File(XdgUtils.INSTANCE.getXdgStateHome(), "metrolist/logs");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Cannot create native crash log directory: " + directory);
            }
            File output = new File(directory, "native_err_pid" + ProcessHandle.current().pid() + ".log");
            NativeErrorOutput.redirect(output.getAbsolutePath());
            configured = true;
        } catch (Throwable error) {
            System.err.println("Unable to configure native crash log: " + error.getMessage());
        }
    }
}

// Native Image has no HotSpot ErrorFile option and does not consume _JAVA_OPTIONS.
@TargetClass(className = "com.metrolist.desktop.utils.DesktopJvmErrorFileKt")
final class TargetDesktopJvmErrorFile {
    @Substitute
    public static void configureDesktopJvmErrorFile() {
        NativeCrashLog.configure();
    }
}
