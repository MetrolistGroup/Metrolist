#!/bin/sh

APPDIR="${APPDIR:-$(dirname "$(readlink -f "$0")")}"
export APPDIR
# Fewer glibc arenas keep the JVM's native heap from growing with thread count.
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-4}"
export MALLOC_MMAP_THRESHOLD_="${MALLOC_MMAP_THRESHOLD_:-131072}"

install_desktop_entry() {
    # Package managers install their own desktop entries.
    [ -n "${APPIMAGE:-}" ] || return 0
    case "$APPIMAGE" in /*) ;; *) return 0 ;; esac
    case "${XDG_DATA_HOME:-}" in
        /*) data_home="$XDG_DATA_HOME" ;;
        *) data_home="$HOME/.local/share" ;;
    esac
    old_ifs="$IFS"
    IFS=:
    for directory in ${XDG_DATA_DIRS:-/usr/local/share:/usr/share}; do
        if [ -f "$directory/applications/Metrolist.desktop" ]; then
            IFS="$old_ifs"
            return 0
        fi
    done
    IFS="$old_ifs"
    cr="$(printf '\r')"
    case "$APPIMAGE$data_home" in
        *'
'*|*"$cr"*) return 1 ;;
    esac
    target="$data_home/applications/Metrolist.desktop"
    # Leave entries the user or another tool created alone.
    if [ -f "$target" ] && ! grep -qx 'X-Metrolist-AppImage=true' "$target"; then
        return 0
    fi
    icon="$data_home/icons/hicolor/512x512/apps/Metrolist.png"
    mkdir -p "$data_home/applications" "$(dirname "$icon")" || return 1
    cp "$APPDIR/Metrolist.png" "$icon" || return 1
    # Desktop entries unescape strings before parsing quoted Exec arguments.
    launcher="$(printf '%s' "$APPIMAGE" | sed -e 's/\\/\\\\\\\\/g' -e 's/["`$]/\\\\&/g' -e 's/%/%%/g')"
    icon="$(printf '%s' "$icon" | sed 's/\\/\\\\/g')"
    entry="$(mktemp "$data_home/applications/.Metrolist.XXXXXX")" || return 1
    if ! {
        while IFS= read -r line || [ -n "$line" ]; do
            case "$line" in
                Exec=*) printf 'Exec=env "%s" %%u\n' "$launcher" ;;
                Icon=*) printf 'Icon=%s\n' "$icon" ;;
                *) printf '%s\n' "$line" ;;
            esac
        done < "$APPDIR/Metrolist.desktop" > "$entry" &&
            printf '%s\n' 'X-Metrolist-AppImage=true' >> "$entry"
    } || ! chmod 644 "$entry" || ! mv -f "$entry" "$target"; then
        rm -f "$entry"
        return 1
    fi
}

install_desktop_entry || printf '%s\n' 'Metrolist could not install its AppImage desktop entry' >&2
exec "$APPDIR/bin/Metrolist" "$@"
