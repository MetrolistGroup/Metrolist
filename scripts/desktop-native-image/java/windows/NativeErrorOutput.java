package com.metrolist.desktop.nativeimage;

import java.util.List;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.word.PointerBase;
import org.graalvm.word.SignedWord;
import org.graalvm.word.WordFactory;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.constant.CConstant;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CShortPointer;

@CContext(NativeErrorOutput.Headers.class)
final class NativeErrorOutput {
    public static final class Headers implements CContext.Directives {
        public List<String> getHeaderFiles() {
            return List.of("<io.h>", "<fcntl.h>", "<sys/stat.h>", "<windows.h>");
        }
    }
    @CConstant("_O_WRONLY") static native int writeOnly();
    @CConstant("_O_CREAT") static native int create();
    @CConstant("_O_APPEND") static native int append();
    @CConstant("_S_IREAD") static native int readMode();
    @CConstant("_S_IWRITE") static native int writeMode();
    @CFunction("_wopen") static native int open(CShortPointer path, int flags, int mode);
    @CFunction("_dup2") static native int dup2(int source, int target);
    @CFunction("_close") static native int close(int fd);
    @CFunction("_get_osfhandle") static native SignedWord osHandle(int fd);
    @CConstant("STD_ERROR_HANDLE") static native long standardError();
    @CFunction("SetStdHandle") static native int setStdHandle(int which, PointerBase handle);

    static void redirect(String path) throws IOException {
        // MSVC's wide API preserves non-ASCII profile paths.
        try (PinnedObject name = PinnedObject.create((path + '\0').toCharArray())) {
            int fd = open(name.addressOfArrayElement(0), writeOnly() | create() | append(), readMode() | writeMode());
            if (fd < 0) throw new IllegalStateException("Cannot open native crash log");
            try {
                if (dup2(fd, 2) < 0) throw new IllegalStateException("Cannot redirect native stderr");
                if (setStdHandle((int) standardError(), WordFactory.pointer(osHandle(2).rawValue())) == 0) {
                    throw new IllegalStateException("Cannot set native stderr handle");
                }
            } finally {
                if (fd != 2) close(fd);
            }
        }
        System.setErr(new PrintStream(new FileOutputStream(path, true), true, StandardCharsets.UTF_8));
    }
}
