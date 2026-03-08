#!/bin/sh
# Set rendering options before Skiko loads its native library.

# Wayland support in Skiko
export SKIKO_WAYLAND="${SKIKO_WAYLAND:-1}"

# Embedded GTK login windows are X11 children of AWT windows
export GDK_BACKEND="${GDK_BACKEND:-x11}"

# Prevent AWT from reparenting (needed for some tiling WMs)
export _JAVA_AWT_WM_NONREPARENTING="${_JAVA_AWT_WM_NONREPARENTING:-1}"

# Limit glibc malloc arenas to reduce memory fragmentation
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
# Return large freed native buffers to the OS rather than retaining them in malloc arenas.
export MALLOC_MMAP_THRESHOLD_="${MALLOC_MMAP_THRESHOLD_:-131072}"

# Keep the existing WebView rendering defaults.
# WebKitGTK (used by Wry) often fails with GBM errors on certain drivers/Wayland; users can set either to 0.
export WEBKIT_DISABLE_DMABUF_RENDERER="${WEBKIT_DISABLE_DMABUF_RENDERER:-1}"
export WEBKIT_DISABLE_COMPOSITING_MODE="${WEBKIT_DISABLE_COMPOSITING_MODE:-1}"

# Discover cursor settings.
# Skip discovery if coreutils timeout is unavailable; never block startup indefinitely.
probe_cursor() {
    command -v timeout >/dev/null 2>&1 || return 1
    timeout --kill-after=1s 2s "$@" 2>/dev/null
}
if [ -z "$XCURSOR_THEME" ]; then
    # Try Hyprland
    CURSOR_THEME="$(probe_cursor hyprctl getoption cursor:theme | sed -n 's/.*string: *//p')"
    if [ -z "$CURSOR_THEME" ]; then
        # Try GNOME / gsettings
        CURSOR_THEME="$(probe_cursor gsettings get org.gnome.desktop.interface cursor-theme | tr -d "'")"
    fi
    if [ -n "$CURSOR_THEME" ]; then
        export XCURSOR_THEME="$CURSOR_THEME"
        export GDK_CURSOR_THEME="$CURSOR_THEME"
    fi
fi

if [ -z "$XCURSOR_SIZE" ]; then
    # Try Hyprland
    CURSOR_SIZE="$(probe_cursor hyprctl getoption cursor:size | sed -n 's/.*int: *//p')"
    if [ -z "$CURSOR_SIZE" ]; then
        # Try GNOME / gsettings
        CURSOR_SIZE="$(probe_cursor gsettings get org.gnome.desktop.interface cursor-size)"
    fi
    if [ -n "$CURSOR_SIZE" ] && [ "$CURSOR_SIZE" -eq "$CURSOR_SIZE" ] 2>/dev/null; then
        export XCURSOR_SIZE="$CURSOR_SIZE"
    fi
fi

export METROLIST_CURSOR_CONFIGURED=1

DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
exec "$DIR/../lib/native/Metrolist" "$@"
