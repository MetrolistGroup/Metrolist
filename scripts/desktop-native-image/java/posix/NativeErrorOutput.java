package com.metrolist.desktop.nativeimage;

import java.util.List;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.constant.CConstant;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

@CContext(NativeErrorOutput.Headers.class)
final class NativeErrorOutput {
    public static final class Headers implements CContext.Directives {
        public List<String> getHeaderFiles() {
            return List.of("<fcntl.h>", "<unistd.h>", "<sys/stat.h>");
        }
    }
    @CConstant("O_WRONLY") static native int writeOnly();
    @CConstant("O_CREAT") static native int create();
    @CConstant("O_APPEND") static native int append();
    @CConstant("S_IRUSR") static native int readMode();
    @CConstant("S_IWUSR") static native int writeMode();
    @CFunction("open") static native int open(CCharPointer path, int flags, int mode);
    @CFunction("fchmod") static native int fchmod(int fd, int mode);
    @CFunction("dup2") static native int dup2(int source, int target);
    @CFunction("close") static native int close(int fd);

    static void redirect(String path) {
        try (CTypeConversion.CCharPointerHolder name = CTypeConversion.toCString(path)) {
            int fd = open(name.get(), writeOnly() | create() | append(), readMode() | writeMode());
            if (fd < 0) throw new IllegalStateException("Cannot open native crash log");
            try {
                // open is variadic and Apple arm64 passes variadic arguments on the stack, so the mode above is unreliable there.
                if (fchmod(fd, readMode() | writeMode()) < 0) throw new IllegalStateException("Cannot set native crash log mode");
                if (dup2(fd, 2) < 0) throw new IllegalStateException("Cannot redirect native stderr");
            } finally {
                if (fd != 2) close(fd);
            }
        }
    }
}
