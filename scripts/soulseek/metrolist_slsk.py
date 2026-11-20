"""Find a song on Soulseek, pick the best high-quality match and download it.

Prints the downloaded file path on stdout (`search` prints a JSON list of versions instead).
Exit codes: 0 done, 1 error, 2 no match or the peer did not send the file, 3 not logged in or login rejected.

`metrolist-slsk login` checks the credentials with the Soulseek server and keeps the
password in the system keychain; only the username goes in the config file.
"""

import argparse
import asyncio
import getpass
import json
import os
import re
import subprocess
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

LOSSLESS = {"flac", "wav", "aiff", "aif", "ape", "wv", "alac"}
LOSSY = {"mp3", "m4a", "aac", "ogg", "opus"}
# Words that mark a different recording; skipped unless the requested title has them too.
VARIANT_WORDS = {"remix", "live", "instrumental", "karaoke", "acapella", "cover", "edit", "demo", "sped", "slowed", "nightcore"}
CONFIG_PATH = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "metrolist-slsk" / "config.json"
KEYRING_SERVICE = "metrolist-slsk"
EXIT_NO_MATCH = 2
EXIT_NOT_LOGGED_IN = 3

# Attribute keys from the Soulseek protocol (aioslsk AttributeKey).
BITRATE, DURATION, VBR, SAMPLE_RATE, BIT_DEPTH = 0, 1, 2, 4, 5


@dataclass(frozen=True)
class Candidate:
    username: str
    filename: str
    filesize: int
    attrs: dict  # attribute key -> int
    free_slots: bool
    speed: int
    queue: int


def words(text: str) -> list[str]:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return re.findall(r"[a-z0-9]+", text)


def clean_title(title: str) -> str:
    """Drop '(feat. x)', '[Official Video]' and ' - Remastered 2011' style suffixes."""
    title = re.sub(r"[\(\[][^\)\]]*(feat|ft\.|official|video|audio|lyric|remaster|explicit)[^\)\]]*[\)\]]", "", title, flags=re.I)
    title = re.sub(r"\s+-\s+.*remaster.*$", "", title, flags=re.I)
    return title.strip()


def ext_of(filename: str) -> str:
    return filename.rsplit(".", 1)[-1].lower() if "." in filename else ""


def quality_tier(c: Candidate, mode: str) -> int:
    """Higher is better; 0 means rejected. 'highest' wants lossless, 'high' wants 320-class lossy."""
    ext = ext_of(c.filename)
    bitrate = c.attrs.get(BITRATE, 0)
    if ext == "m4a" and bitrate >= 700:
        ext = "alac"
    if ext in LOSSLESS:
        hires = c.attrs.get(BIT_DEPTH, 16) > 16 or c.attrs.get(SAMPLE_RATE, 44100) > 48000
        return (4 if hires else 3) if mode == "highest" else (1 if hires else 2)
    if ext in LOSSY:
        vbr = bool(c.attrs.get(VBR))
        if bitrate >= 320 or (vbr and bitrate >= 245):
            return 2 if mode == "highest" else 4
        if bitrate >= 256 or (vbr and bitrate >= 220):
            return 1 if mode == "highest" else 3
    return 0


def matches(c: Candidate, artist: str, title: str, duration: int | None) -> bool:
    path_words = set(words(c.filename))
    base_words = set(words(c.filename.replace("\\", "/").rsplit("/", 1)[-1]))
    title_words = words(clean_title(title))
    if not title_words or not set(title_words) <= base_words:
        return False
    # Artist often lives in the folder name, so check the whole path.
    if artist and not set(words(artist)) <= path_words:
        return False
    if (base_words & VARIANT_WORDS) - set(title_words):
        return False
    file_duration = c.attrs.get(DURATION)
    return not (duration and file_duration and abs(file_duration - duration) > 3)


def rank(candidates: list[Candidate], artist: str, title: str, duration: int | None, mode: str) -> list[Candidate]:
    scored = []
    for c in candidates:
        tier = quality_tier(c, mode)
        if tier and matches(c, artist, title, duration):
            # Prefer: quality tier, a peer with a free slot, a known duration, a short queue, a fast peer.
            scored.append(((tier, c.free_slots, DURATION in c.attrs, -c.queue, c.speed), c))
    scored.sort(key=lambda s: s[0], reverse=True)
    return [c for _, c in scored]


FORMAT_ORDER = ("320", "lossless", "hires")  # the order the picker lists versions in


def format_group(c: Candidate) -> str | None:
    """'320' for 320 kbps or V0 lossy, 'lossless' for CD-quality lossless, 'hires' for 24-bit or >48 kHz lossless."""
    tier = quality_tier(c, "highest")
    return {2: "320", 3: "lossless", 4: "hires"}.get(tier)


def options(candidates: list[Candidate], artist: str, title: str, duration: int | None, per_format: int = 3) -> list[Candidate]:
    """The best few matching files per format, in FORMAT_ORDER."""
    groups = {g: [] for g in FORMAT_ORDER}
    for c in candidates:
        group = format_group(c)
        if group and matches(c, artist, title, duration):
            groups[group].append(c)
    # Within a format prefer a free slot, a known duration, a short queue, a fast peer.
    key = lambda c: (c.free_slots, DURATION in c.attrs, -c.queue, c.speed)  # noqa: E731
    return [c for g in FORMAT_ORDER for c in sorted(groups[g], key=key, reverse=True)[:per_format]]


def option_json(c: Candidate) -> dict:
    return {
        "format": format_group(c),
        "username": c.username,
        "filename": c.filename,
        "size": c.filesize,
        "extension": ext_of(c.filename),
        "bitrate": c.attrs.get(BITRATE),
        "bitDepth": c.attrs.get(BIT_DEPTH),
        "sampleRate": c.attrs.get(SAMPLE_RATE),
        "freeSlot": c.free_slots,
    }


def probe(path: Path) -> dict:
    """Describes an audio file on disk from its own headers, in the same shape as a search option."""
    import mutagen

    info = getattr(mutagen.File(path), "info", None)
    attrs = {
        key: value
        for key, value in (
            (BITRATE, getattr(info, "bitrate", 0) // 1000),
            (BIT_DEPTH, getattr(info, "bits_per_sample", 0)),
            (SAMPLE_RATE, getattr(info, "sample_rate", 0)),
            (DURATION, int(getattr(info, "length", 0))),
        )
        if value
    }
    c = Candidate("", path.name, path.stat().st_size, attrs, True, 0, 0)
    # Lossy files under 320 kbps have no picker format; their bitrate still shows in the label.
    return option_json(c) | {"format": format_group(c) or "320", "filename": str(path)}


def now_playing() -> tuple[str, str, int | None]:
    info = json.loads(subprocess.run(["media-control", "get"], capture_output=True, text=True, check=True).stdout or "null")
    if not info or not info.get("title"):
        sys.exit("Nothing is playing")
    duration = info.get("duration")
    return info.get("artist", ""), info["title"], int(duration) if duration else None


def read_config() -> dict:
    return json.loads(CONFIG_PATH.read_text()) if CONFIG_PATH.exists() else {}


def write_config(config: dict):
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(config, indent=2) + "\n")
    CONFIG_PATH.chmod(0o600)


def load_config() -> dict:
    import keyring

    config = read_config()
    config.pop("password", None)  # never read a plaintext password from disk
    config["username"] = os.environ.get("SLSK_USERNAME", config.get("username"))
    config["password"] = os.environ.get("SLSK_PASSWORD") or (
        keyring.get_password(KEYRING_SERVICE, config["username"]) if config["username"] else None
    )
    if not config["username"] or not config["password"]:
        log("Not logged in to Soulseek. Run: metrolist-slsk login")
        sys.exit(EXIT_NOT_LOGGED_IN)
    return config


def client_settings(config: dict, out_dir: Path):
    from aioslsk.settings import CredentialsSettings, Settings, SharesSettings

    settings = Settings(
        credentials=CredentialsSettings(username=config["username"], password=config["password"]),
        shares=SharesSettings(scan_on_start=False, download=str(out_dir)),
    )
    if "listen_port" in config:
        settings.network.listening.port = int(config["listen_port"])
        settings.network.listening.obfuscated_port = int(config["listen_port"]) + 1
    return settings


async def check_login(config: dict):
    from aioslsk.client import SoulSeekClient

    async with SoulSeekClient(client_settings(config, Path.cwd())) as client:
        await client.login()


def login(argv: list[str]):
    import keyring
    from aioslsk.exceptions import AuthenticationError

    parser = argparse.ArgumentParser(prog="metrolist-slsk login", description="Check Soulseek credentials and save them to the system keychain.")
    parser.add_argument("--username")
    parser.add_argument("--password-stdin", action="store_true", help="read the password from the first line of stdin")
    args = parser.parse_args(argv)

    username = (args.username or input("Soulseek username: ")).strip()
    password = sys.stdin.readline().rstrip("\r\n") if args.password_stdin else getpass.getpass("Soulseek password: ")
    if not username or not password:
        sys.exit("Username and password are required")
    config = read_config() | {"username": username, "password": password}
    try:
        asyncio.run(check_login(config))
    except AuthenticationError as e:
        # Soulseek creates an account for an unused username, so this means a taken name or a wrong password.
        log(f"Soulseek rejected the login ({e.reason or 'unknown reason'})")
        sys.exit(EXIT_NOT_LOGGED_IN)
    except Exception as e:  # network down, port in use
        sys.exit(f"Soulseek error: {e}")
    keyring.set_password(KEYRING_SERVICE, username, password)
    config.pop("password")
    write_config(config)
    print(f"Logged in as {username}")


def status():
    import keyring

    username = read_config().get("username")
    if not username or not keyring.get_password(KEYRING_SERVICE, username):
        log("Not logged in")
        sys.exit(EXIT_NOT_LOGGED_IN)
    print(username)


def logout():
    import keyring
    from keyring.errors import PasswordDeleteError

    config = read_config()
    username = config.pop("username", None)
    if username:
        try:
            keyring.delete_password(KEYRING_SERVICE, username)
        except PasswordDeleteError:
            pass
        write_config(config)
    print("Logged out")


async def search_files(client, artist: str, title: str, config: dict) -> list[Candidate]:
    query = " ".join(words(artist) + words(clean_title(title)))
    log(f"Searching Soulseek for: {query}")
    request = await client.searches.search(query)
    await asyncio.sleep(float(config.get("search_seconds", 10)))
    return [
        Candidate(r.username, f.filename, f.filesize, {a.key: a.value for a in f.attributes}, r.has_free_slots, r.avg_speed, r.queue_size)
        for r in request.results
        for f in r.shared_items
    ]


async def fetch_file(client, username: str, filename: str) -> Path | None:
    from aioslsk.transfer.state import TransferState

    log(f"Trying {username}: {filename}")
    transfer = await client.transfers.download(username, filename)
    waited, last_bytes, stalled = 0, 0, 0
    while not transfer.is_finalized():
        await asyncio.sleep(2)
        waited += 2
        started = transfer.state.VALUE == TransferState.DOWNLOADING
        stalled = 0 if transfer.bytes_transfered > last_bytes else stalled + 2
        last_bytes = transfer.bytes_transfered
        # Give a peer 60s to start sending and drop it if it stops sending for 60s.
        if (not started and waited >= 60) or (started and stalled >= 60):
            break
    if transfer.state.VALUE == TransferState.COMPLETE and transfer.local_path:
        return Path(transfer.local_path)
    log(f"Skipped ({transfer.state.VALUE.name.lower()}{': ' + transfer.fail_reason if transfer.fail_reason else ''})")
    await client.transfers.abort(transfer)
    return None


async def run_session(config: dict, out_dir: Path, work):
    """Logs in, runs `work(client)` and logs out."""
    from aioslsk.client import SoulSeekClient

    out_dir.mkdir(parents=True, exist_ok=True)
    async with SoulSeekClient(client_settings(config, out_dir)) as client:
        await client.login()
        return await work(client)


async def auto_download(client, args, config: dict) -> Path | None:
    candidates = await search_files(client, args.artist, args.title, config)
    ranked = rank(candidates, args.artist, args.title, args.duration, args.quality)
    log(f"{len(candidates)} files found, {len(ranked)} match")
    for c in ranked[:5]:
        if path := await fetch_file(client, c.username, c.filename):
            return path
    return None


async def list_options(client, args, config: dict) -> list[dict]:
    candidates = await search_files(client, args.artist, args.title, config)
    return [option_json(c) for c in options(candidates, args.artist, args.title, args.duration)]


def log(message: str):
    print(message, file=sys.stderr, flush=True)


def main():
    if sys.argv[1:2] == ["login"]:
        return login(sys.argv[2:])
    if sys.argv[1:2] == ["logout"]:
        return logout()
    if sys.argv[1:2] == ["status"]:
        return status()
    if sys.argv[1:2] == ["probe"]:
        if len(sys.argv) != 3:
            sys.exit("usage: metrolist-slsk probe FILE")
        return print(json.dumps(probe(Path(sys.argv[2]))))
    command = sys.argv[1] if sys.argv[1:2] in (["search"], ["fetch"]) else None
    parser = argparse.ArgumentParser(
        prog="metrolist-slsk" + (f" {command}" if command else ""),
        description=__doc__,
        epilog="Commands: search (list versions as JSON), fetch (download one listed file), probe FILE, login, logout, status",
    )
    if command == "fetch":
        parser.add_argument("--username", required=True, help="Soulseek user sharing the file")
        parser.add_argument("--filename", required=True, help="the file's path as listed by search")
    else:
        parser.add_argument("--artist", default="")
        parser.add_argument("--title")
        parser.add_argument("--duration", type=int, help="song length in seconds, used to reject other versions")
        parser.add_argument("--now-playing", action="store_true", help="use the track in macOS Now Playing (needs media-control)")
    if command is None:
        parser.add_argument("--quality", choices=["highest", "high"], default="highest", help="highest = lossless first, high = 320 kbps first")
    parser.add_argument("--out", type=Path, help="download folder")
    args = parser.parse_args(sys.argv[2:] if command else sys.argv[1:])

    if command != "fetch":
        if args.now_playing:
            args.artist, args.title, args.duration = now_playing()
        if not args.title:
            parser.error("--title or --now-playing is required")
    config = load_config()
    out_dir = args.out or Path(config.get("download_dir", Path.home() / "Music" / "Soulseek")).expanduser()
    work = {
        "search": lambda client: list_options(client, args, config),
        "fetch": lambda client: fetch_file(client, args.username, args.filename),
        None: lambda client: auto_download(client, args, config),
    }[command]
    from aioslsk.exceptions import AuthenticationError

    try:
        result = asyncio.run(run_session(config, out_dir, work))
    except AuthenticationError as e:  # password changed since login
        log(f"Soulseek rejected the login ({e.reason or 'unknown reason'}). Run: metrolist-slsk login")
        sys.exit(EXIT_NOT_LOGGED_IN)
    except Exception as e:  # network down, port in use
        sys.exit(f"Soulseek error: {e}")
    if not result:
        log("No matching file" if command != "fetch" else "The peer did not send the file")
        sys.exit(EXIT_NO_MATCH)
    print(json.dumps(result) if command == "search" else result)


if __name__ == "__main__":
    main()
