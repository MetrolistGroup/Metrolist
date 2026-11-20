from metrolist_slsk import BIT_DEPTH, BITRATE, DURATION, SAMPLE_RATE, VBR, Candidate, rank


def c(name, attrs=None, free=True, queue=0):
    return Candidate("peer", name, 1, attrs or {}, free, 100, queue)


flac = c("Music\\Daft Punk\\Discovery\\03 Digital Love.flac", {DURATION: 301})
hires = c("Music\\Daft Punk - Discovery (24-96)\\03 Digital Love.flac", {DURATION: 301, BIT_DEPTH: 24, SAMPLE_RATE: 96000})
mp3 = c("mp3\\Daft Punk\\03 - Digital Love.mp3", {BITRATE: 320, DURATION: 300})
v0 = c("mp3\\Daft Punk\\Digital Love.mp3", {BITRATE: 250, VBR: 1, DURATION: 301})
low = c("Daft Punk\\Digital Love.mp3", {BITRATE: 128, DURATION: 301})
remix = c("Daft Punk\\Digital Love (Remix).flac", {DURATION: 301})
wrong_len = c("Daft Punk\\Digital Love.flac", {DURATION: 420})
wrong_artist = c("Someone Else\\Digital Love.flac", {DURATION: 301})
all_files = [low, mp3, remix, wrong_len, flac, wrong_artist, v0, hires]

assert rank(all_files, "Daft Punk", "Digital Love", 301, "highest") == [hires, flac, mp3, v0]
assert rank(all_files, "Daft Punk", "Digital Love", 301, "high") == [mp3, v0, flac, hires]
# A requested remix may match remix files.
assert rank(all_files, "Daft Punk", "Digital Love (Remix)", 301, "highest")[0] == remix
# Title suffixes from YouTube Music don't block matching.
assert rank([flac], "Daft Punk", "Digital Love (Official Audio)", 301, "highest") == [flac]
# A free slot beats a queued peer at equal quality.
busy = c("Daft Punk\\Digital Love.flac", {DURATION: 301}, free=False, queue=9)
assert rank([busy, flac], "Daft Punk", "Digital Love", 301, "highest") == [flac, busy]

# The picker lists 320 kbps, then CD-quality lossless, then hi-res, and drops other versions and low bitrates.
from metrolist_slsk import option_json, options  # noqa: E402

assert options(all_files, "Daft Punk", "Digital Love", 301) == [mp3, v0, flac, hires]
assert [option_json(o)["format"] for o in options(all_files, "Daft Punk", "Digital Love", 301)] == ["320", "320", "lossless", "hires"]
assert len(options([mp3] * 5, "Daft Punk", "Digital Love", 301, per_format=3)) == 3
print("ok")
