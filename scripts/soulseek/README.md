# metrolist-slsk

Finds a song on Soulseek and downloads it. Only files whose name matches the title and artist, and whose length is within 3 seconds of the song, count; remixes, live and other versions the title doesn't ask for are skipped, as are files below 320 kbps (the automatic mode below also accepts 256 kbps).

In Metrolist desktop, **Download from Soulseek** in a song or player menu opens a picker. It searches first, then lists up to three files for each format with their size, in this order: 320 kbps or V0, CD-quality lossless, then hi-res lossless (24-bit or above 48 kHz). Picking one downloads that file, and the song then plays that file instead of streaming, offline too. **Downloaded versions** in the same menu lists every version kept for the song and switches between them or back to streaming. Metrolist records the versions in `soulseek-files.json` in its state directory (`~/.local/state/metrolist` by default). Sign in under **Settings → Integrations → Soulseek**, or in the dialog that opens when a search needs it.

## Install

Desktop packages bundle the helper as one executable built by `build_sidecar.sh`, which freezes it with PyInstaller into `desktopApp/src/desktopMain/resources/<os>/`. CI runs it before each desktop package; set `SOULSEEK_PYTHON` to pick the interpreter and so the architecture. When Metrolist runs from source, or the package has no helper, it uses one installed on the machine:

```sh
uv tool install --force scripts/soulseek
metrolist-slsk login    # prompts for username and password
```

`login` signs in to Soulseek to check the credentials, then stores the password in the system keychain (macOS Keychain, Secret Service on Linux) and only the username in `~/.config/metrolist-slsk/config.json` (mode 600). A wrong password saves nothing. An unused username creates a new Soulseek account. `metrolist-slsk logout` removes both; `metrolist-slsk status` prints the signed-in username. Metrolist passes the password to `login --password-stdin` so it never appears in the process list.

Optional config keys: `download_dir` (default `~/Music/Soulseek`), `listen_port` (default 60000; 60001 is also used) and `search_seconds` (default 10). `SLSK_USERNAME` and `SLSK_PASSWORD` override the stored login for scripts. A `password` key in the config file is ignored.

Soulseek allows one login per account, so close SoulseekQt (or use a second account) while downloading.

## Use

```sh
# What Metrolist runs: list versions as JSON, then download the one picked.
metrolist-slsk search --artist "Daft Punk" --title "Digital Love" --duration 301
metrolist-slsk fetch --username SOME_PEER --filename 'Music\Daft Punk\03 Digital Love.flac'

# Pick automatically (Raycast uses this for the song in macOS Now Playing).
metrolist-slsk --artist "Daft Punk" --title "Digital Love" --quality highest   # lossless first
metrolist-slsk --now-playing --quality high                                    # 320 kbps first; needs `brew install media-control`
```

The automatic mode tries up to five files, dropping a peer that doesn't start sending within 60 seconds or stops sending for 60 seconds; `fetch` applies the same limits to its one file. Downloads print the saved path. Exit code 2 means no matching file or the peer didn't send it; 3 means not logged in or the login was rejected.

Raycast: add `scripts/soulseek/raycast` under Settings → Extensions → Script Commands → Add Directories.

Tests: `uv run --with aioslsk --with keyring python test_rank.py && uv run --with aioslsk --with keyring python test_login.py`.
