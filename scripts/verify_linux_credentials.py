#!/usr/bin/env python3
"""Opt-in real Secret Service regression probe; never opens Metrolist or the user's keyring.

Compile desktop tests and supply their runtime classpath via --classpath-file.
Each probe invocation starts a fresh JVM, with only synthetic credentials.
"""

import argparse
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time


UNLOCK_SCRIPT = """from gi.repository import Gio, GLib
connection = Gio.bus_get_sync(Gio.BusType.SESSION, None)
def call(interface, method, parameters):
    return connection.call_sync("org.freedesktop.secrets", "/org/freedesktop/secrets", interface, method,
                                parameters, None, Gio.DBusCallFlags.NONE, 5000, None)
session = call("org.freedesktop.Secret.Service", "OpenSession", GLib.Variant("(sv)", ("plain", GLib.Variant("s", "")))).unpack()[1]
call("org.gnome.keyring.InternalUnsupportedGuiltRiddenInterface", "UnlockWithMasterPassword",
     GLib.Variant("(o(oayays))", ("/org/freedesktop/secrets/collection/login", (session, [], list(b"fake-password"), "text/plain"))))
"""


def run_session(classpath, java, root):
    def run(*args, input=None, env=None):
        result = subprocess.run(args, input=input, text=True, env=env, timeout=45)
        if result.returncode:
            raise RuntimeError(f"Credential probe command failed ({result.returncode}): {args[0]}")
        return result

    def probe(mode, *values, env=None):
        run(java, "-Djava.util.prefs.userRoot=" + str(root / "prefs"), "-cp", classpath,
            "com.metrolist.shared.settings.LinuxSecretServiceProbe", mode, *values, env=env)

    def unlock():
        run("gnome-keyring-daemon", "--unlock", "--components=secrets", "--control-directory",
            str(root / "control"), input="fake-password")
        run("gnome-keyring-daemon", "--start", "--components=secrets", "--control-directory", str(root / "control"))
        run(sys.executable, str(root / "unlock-keyring.py"))

    def lock():
        run("dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.secrets",
            "/org/freedesktop/secrets", "org.freedesktop.Secret.Service.Lock",
            "array:objpath:/org/freedesktop/secrets/collection/login")

    probe("pending", "lyricsAiApiKey=fake-late-service")
    probe("pending")
    unlock()
    probe("recovered", "lyricsAiApiKey=fake-late-service")
    probe("seed")
    probe("recovered", "innerTubeCookie=SAPISID=fake-cookie", "lastFmSession=fake-lastfm")

    from gi.repository import Gio, GLib
    connection = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    pid = connection.call_sync(
        "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "GetConnectionUnixProcessID",
        GLib.Variant("(s)", ("org.freedesktop.secrets",)), None, Gio.DBusCallFlags.NONE, 5000, None,
    ).unpack()[0]
    os.kill(pid, signal.SIGTERM)
    for _ in range(100):
        present = connection.call_sync(
            "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner",
            GLib.Variant("(s)", ("org.freedesktop.secrets",)), None, Gio.DBusCallFlags.NONE, 5000, None,
        ).unpack()[0]
        if not present:
            break
        time.sleep(0.02)
    assert not present
    probe("pending", "lastFmSession=lastfm-while-service-absent")
    probe("pending")
    unlock()
    probe("recovered", "innerTubeCookie=SAPISID=fake-cookie", "lastFmSession=lastfm-while-service-absent")

    lock()
    pid = connection.call_sync(
        "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "GetConnectionUnixProcessID",
        GLib.Variant("(s)", ("org.freedesktop.secrets",)), None, Gio.DBusCallFlags.NONE, 5000, None,
    ).unpack()[0]
    os.kill(pid, signal.SIGSTOP)
    try:
        probe("native-timeout")
    finally:
        os.kill(pid, signal.SIGCONT)
    probe("pending", "visitorData=visitor-while-locked")
    probe("pending")
    unlock()
    probe("recovered", "innerTubeCookie=SAPISID=fake-cookie", "visitorData=visitor-while-locked",
          "lastFmSession=lastfm-while-service-absent", "discordAccessToken=fake-discord")

    empty_path = root / "without-secret-tool"
    empty_path.mkdir()
    missing_env = dict(os.environ, PATH=str(empty_path))
    probe("pending", "lyricsDeepLApiKey=fake-missing-tool", env=missing_env)
    probe("pending", env=missing_env)
    probe("tool-recovery", env=missing_env)
    probe("recovered", "innerTubeCookie=SAPISID=fake-cookie", "lyricsDeepLApiKey=fake-tool-recovery",
          "lastFmSession=lastfm-while-service-absent")

    lock()
    probe("race")
    probe("recovered", "innerTubeCookie=SAPISID=last-login", "innerTubeAuthUser=2",
          "visitorData=last-visitor", "lastFmSession=lastfm-while-service-absent", "discordAccessToken=fake-discord")
    probe("large-write")  # The native writer must store more than secret-tool's 8192-byte stdin limit.
    probe("large-restart")
    probe("interrupted-save")
    probe("recovered", "innerTubeCookie=SAPISID=last-login", "visitorData=visitor-before-halt",
          "lastFmSession=lastfm-while-service-absent", "discordAccessToken=fake-discord")

    session = connection.call_sync(
        "org.freedesktop.secrets", "/org/freedesktop/secrets", "org.freedesktop.Secret.Service", "OpenSession",
        GLib.Variant("(sv)", ("plain", GLib.Variant("s", ""))), None, Gio.DBusCallFlags.NONE, 5000, None,
    ).unpack()[1]
    collection = connection.call_sync(
        "org.freedesktop.secrets", "/org/freedesktop/secrets",
        "org.gnome.keyring.InternalUnsupportedGuiltRiddenInterface", "CreateWithMasterPassword",
        GLib.Variant("(a{sv}(oayays))", (
            {"org.freedesktop.Secret.Collection.Label": GLib.Variant("s", "Default keyring")},
            (session, [], list(b"fake-password"), "text/plain"),
        )), None, Gio.DBusCallFlags.NONE, 5000, None,
    ).unpack()[0]
    connection.call_sync(
        "org.freedesktop.secrets", "/org/freedesktop/secrets", "org.freedesktop.Secret.Service", "SetAlias",
        GLib.Variant("(so)", ("default", collection)), None, Gio.DBusCallFlags.NONE, 5000, None,
    )
    run("secret-tool", "store", "--label=Metrolist Desktop Secrets", "service", "com.metrolist.desktop",
        "schema", "metrolist-settings", "account", "desktop-secrets", input="#Metrolist desktop secrets")
    probe("duplicate-keyrings")
    probe("recovered", "innerTubeCookie=SAPISID=last-login", "lyricsDeepLApiKey=fake-duplicate-keyrings",
          "lastFmSession=lastfm-while-service-absent", "discordAccessToken=fake-discord")
    run("secret-tool", "store", "--label=Metrolist Desktop Secrets", "service", "com.metrolist.desktop",
        "schema", "metrolist-settings", "account", "desktop-secrets", input="innerTubeCookie=SAPISID=stale-duplicate")
    probe("clear-duplicates")
    probe("cleared")

    run("secret-tool", "clear", "service", "com.metrolist.desktop", "schema", "metrolist-settings",
        "account", "desktop-secrets")
    run("secret-tool", "store", "--label=Metrolist Desktop Secrets", "service", "com.metrolist.desktop",
        "schema", "metrolist-settings", "account", "desktop-secrets", input="#Metrolist desktop secrets")
    lock()
    probe("default-keyring")
    probe("recovered", "lyricsDeepLApiKey=fake-default-keyring")

    def call(path, interface, method, parameters):
        return connection.call_sync(
            "org.freedesktop.secrets", path, interface, method, parameters,
            None, Gio.DBusCallFlags.NONE, 5000, None,
        ).unpack()

    def get_secret(path):
        return call(path, "org.freedesktop.Secret.Item", "GetSecret", GLib.Variant("(o)", (session,)))[0]

    def set_secret(path, secret):
        call(path, "org.freedesktop.Secret.Item", "SetSecret", GLib.Variant("((oayays))", (secret,)))

    def item_property(path, name):
        return call(path, "org.freedesktop.DBus.Properties", "Get",
                    GLib.Variant("(ss)", ("org.freedesktop.Secret.Item", name)))[0]

    def touch_secret(path):
        # GNOME Keyring does not advance Modified for an identical payload.
        secret = get_secret(path)
        changed = (session, [], list(bytes(secret[2]) + b"\n#advance-modified"), "text/plain")
        set_secret(path, changed)
        set_secret(path, secret)

    unlock()
    attributes = {"service": "com.metrolist.desktop", "schema": "metrolist-settings", "account": "desktop-secrets"}
    stale = b"innerTubeCookie=SAPISID=locked-duplicate\nlastFmSession=locked-lastfm"
    login_item, prompt = call(
        "/org/freedesktop/secrets/collection/login", "org.freedesktop.Secret.Collection", "CreateItem",
        GLib.Variant("(a{sv}(oayays)b)", (
            {"org.freedesktop.Secret.Item.Label": GLib.Variant("s", "Stale duplicate"),
             "org.freedesktop.Secret.Item.Attributes": GLib.Variant("a{ss}", attributes)},
            (session, [], list(stale), "text/plain"), False,
        )),
    )
    assert prompt == "/"
    unlocked, locked = call("/org/freedesktop/secrets", "org.freedesktop.Secret.Service", "SearchItems",
                            GLib.Variant("(a{ss})", (attributes,)))
    assert not locked and len(unlocked) == 2
    default_item = next(path for path in unlocked if path != login_item)
    touch_secret(login_item)
    time.sleep(1.1)  # Secret Service timestamps have one-second resolution.
    touch_secret(default_item)
    login_modified = item_property(login_item, "Modified")
    default_modified = item_property(default_item, "Modified")
    assert 0 < login_modified < default_modified, (login_modified, default_modified)
    lock()
    assert item_property(login_item, "Locked")
    assert item_property(login_item, "Modified") == login_modified
    probe("recovered", "lyricsDeepLApiKey=fake-default-keyring")
    probe("duplicate-write", "fake-locked-duplicate")
    probe("recovered", "lyricsDeepLApiKey=fake-locked-duplicate")
    assert call("/org/freedesktop/secrets/collection/login", "org.freedesktop.DBus.Properties", "Get",
                GLib.Variant("(ss)", ("org.freedesktop.Secret.Collection", "Locked")))[0]
    unlock()
    assert bytes(get_secret(login_item)[2]) == stale
    saved = get_secret(default_item)

    header = (session, [], list(b"#Metrolist desktop secrets\n"), "text/plain")
    set_secret(default_item, header)
    lock()
    probe("unreadable-duplicates")
    probe("unreadable-duplicates")
    unlock()
    assert get_secret(default_item) == header
    assert bytes(get_secret(login_item)[2]) == stale
    probe("recovered", "innerTubeCookie=SAPISID=locked-duplicate", "lastFmSession=locked-lastfm",
          "visitorData=visitor-while-unreadable")
    assert get_secret(default_item) == header

    set_secret(default_item, saved)
    current_login = get_secret(login_item)
    time.sleep(1.1)
    touch_secret(login_item)
    login_modified = item_property(login_item, "Modified")
    assert login_modified > item_property(default_item, "Modified")
    lock()
    assert item_property(login_item, "Locked")
    assert item_property(login_item, "Modified") == login_modified
    probe("unreadable-duplicates")
    probe("unreadable-duplicates")
    unlock()
    assert get_secret(default_item) == saved
    assert get_secret(login_item) == current_login
    probe("recovered", "innerTubeCookie=SAPISID=locked-duplicate", "lastFmSession=locked-lastfm",
          "visitorData=visitor-while-unreadable")
    assert get_secret(default_item) == saved

    malformed = (session, [], list(b"innerTubeCookie=\\uXYZW"), "text/plain")
    set_secret(login_item, malformed)
    probe("recovered", "lyricsDeepLApiKey=fake-locked-duplicate")
    probe("duplicate-write", "fake-malformed-duplicate")
    probe("recovered", "lyricsDeepLApiKey=fake-malformed-duplicate")
    assert get_secret(login_item) == malformed

    saved = get_secret(default_item)
    set_secret(default_item, malformed)
    probe("unreadable-duplicates")
    assert get_secret(default_item) == malformed
    assert get_secret(login_item) == malformed
    set_secret(default_item, saved)
    probe("recovered", "lyricsDeepLApiKey=fake-malformed-duplicate", "visitorData=visitor-while-unreadable")
    assert get_secret(login_item) == malformed
    print("PASS isolated Secret Service: late startup, locked startup, missing tool, concurrent login, large writes, interrupted save, duplicate keyrings, older and newer locked duplicates, header-only default with populated locked Login, malformed duplicates, unreadable writes, default collection, and restarts")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", required=True, type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--inside-session", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    classpath = args.classpath_file.resolve().read_text().strip()
    java = str(Path(args.java).resolve())
    if args.inside_session:
        root = Path(os.environ["METROLIST_CREDENTIAL_TEST_ROOT"])
        assert Path(os.environ["HOME"]) == root
        run_session(classpath, java, root)
        return

    with tempfile.TemporaryDirectory(prefix="metrolist-credential-verification-") as directory:
        root = Path(directory)
        (root / "run").mkdir(mode=0o700)
        (root / "unlock-keyring.py").write_text(UNLOCK_SCRIPT)
        config = root / "bus.conf"
        config.write_text(
            '<busconfig><type>session</type><listen>unix:tmpdir=' + directory + '</listen>'
            '<auth>EXTERNAL</auth><policy context="default"><allow send_destination="*"/>'
            '<allow receive_sender="*"/><allow own="*"/></policy></busconfig>'
        )
        env = dict(os.environ, HOME=directory, XDG_CONFIG_HOME=str(root / "config"),
                   XDG_DATA_HOME=str(root / "data"), XDG_STATE_HOME=str(root / "state"),
                   XDG_RUNTIME_DIR=str(root / "run"), GNOME_KEYRING_CONTROL=str(root / "control"),
                   METROLIST_CREDENTIAL_TEST_ROOT=directory, METROLIST_CREDENTIAL_TEST_SECRET_TOOL=shutil.which("secret-tool"),
                   DISPLAY="", WAYLAND_DISPLAY="")
        subprocess.run(
            ["dbus-run-session", "--config-file=" + str(config), "--", sys.executable,
             str(Path(__file__).resolve()), "--classpath-file", str(args.classpath_file.resolve()),
             "--java", java, "--inside-session"], env=env, check=True, timeout=240,
        )


if __name__ == "__main__":
    main()
