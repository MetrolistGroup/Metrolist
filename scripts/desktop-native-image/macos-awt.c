/* Adapted from Nucleus 67e9920f171a6d569e520d95b53c8e594b44a4d4.
 * Copyright (c) 2026 Elie Gambache. MIT license and provenance:
 * resources/META-INF/native-image/com.metrolist/desktop-experiment/nucleus/README.md
 */
#include <dlfcn.h>

/* The flat-namespace AWT dylibs still reference this removed Cursor native. */
void Java_java_awt_Cursor_finalizeImpl(void) {}

typedef void (*nucleus_init_enc_t)(void *env, const char *name);

int nucleus_init_platform_encoding(void *env) {
    /* Initialize the bundled dynamic libjava, not Native Image's static copy. */
    void *handle = dlopen("@executable_path/libjava.dylib", RTLD_GLOBAL | RTLD_NOW);
    nucleus_init_enc_t initialize = handle ? (nucleus_init_enc_t) dlsym(handle, "InitializeEncoding") : 0;
    if (!initialize) return 0;
    initialize(env, "UTF-8");
    return 1;
}
