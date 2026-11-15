package com.metrolist.desktop.nativeimage;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** File name and parent/file name of every JavaCPP resource on the image classpath, written by native_image.py. */
final class JavaCppResources {
    private static final Set<String> NAMES = load();

    private static Set<String> load() {
        try (InputStream input = ClassLoader.getSystemResourceAsStream("metrolist-javacpp-resources.txt")) {
            if (input == null) return null;
            Set<String> names = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) names.add(line);
            }
            return names;
        } catch (IOException error) {
            return null;
        }
    }

    static boolean mayExist(String name) {
        if (NAMES == null) return true;
        String[] parts = name.split("/+");
        int last = parts.length - 1;
        return NAMES.contains(last > 0 && !parts[last - 1].isEmpty() ? parts[last - 1] + "/" + parts[last] : parts[last]);
    }
}

// JavaCPP probes about 60 Windows runtime DLL names per FFmpeg class that are never bundled. With exact
// reachability metadata, each missing lookup matches the resource globs of every module, which stalled the
// first Windows playback for over 30 seconds. Names absent from the index skip that lookup.
@TargetClass(className = "org.bytedeco.javacpp.Loader")
final class TargetJavaCppLoader {
    @Alias
    public static native URL[] findResources(Class<?> cls, String name, int maxLength) throws IOException;

    @Substitute
    public static URL findResource(Class<?> cls, String name) throws IOException {
        if (!JavaCppResources.mayExist(name)) return null;
        URL[] url = findResources(cls, name, 1);
        return url.length > 0 ? url[0] : null;
    }
}
