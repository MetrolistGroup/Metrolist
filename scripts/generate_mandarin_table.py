#!/usr/bin/env python3
"""Generate the Android and desktop Mandarin table; downloads are pinned and cached under build/.

Unihan supplies kMandarin readings. The old library's defaults and complete greedy
phrase dictionary preserve compatibility, including its historical reading choices.
See docs/desktop-mandarin.md for provenance and the binary format.
"""
import hashlib
import io
from pathlib import Path
import struct
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "build/desktop-slim"
DEST = ROOT / "shared/src/jvmSharedMain/resources/romanization"


def download(name, url, digest):
    path = CACHE / name
    if not path.exists():
        with urllib.request.urlopen(url) as response:
            path.write_bytes(response.read())
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"Checksum mismatch: {path}")
    return data


class Bits:
    def __init__(self, output):
        self.output, self.value, self.length = output, 0, 0

    def write(self, value, width):
        assert 0 <= value < (1 << width)
        self.value = (self.value << width) | value
        self.length += width
        while self.length >= 8:
            self.length -= 8
            self.output.write(bytes([(self.value >> self.length) & 255]))
        self.value &= (1 << self.length) - 1

    def finish(self):
        if self.length:
            self.write(0, 8 - self.length)


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    DEST.mkdir(parents=True, exist_ok=True)
    unihan = download("Unihan.zip", "https://www.unicode.org/Public/17.0.0/ucd/Unihan.zip",
                      "f7a48b2b545acfaa77b2d607ae28747404ce02baefee16396c5d2d7a8ef34b5e")
    legacy = download("pinyin-0.4.0.jar", "https://repo.maven.apache.org/maven2/com/github/houbb/pinyin/0.4.0/pinyin-0.4.0.jar",
                      "b202205ce1e9d659d00e88711f1debfc208a94b063b9885ed2de43a7a3e729be")
    readings = {}
    with zipfile.ZipFile(io.BytesIO(unihan)) as archive:
        for line in archive.read("Unihan_Readings.txt").decode().splitlines():
            if not line or line.startswith("#"):
                continue
            code, field, value = line.split("\t")
            if field == "kMandarin" and int(code[2:], 16) <= 0xFFFF:
                readings[chr(int(code[2:], 16))] = value.split()[0]
    with zipfile.ZipFile(io.BytesIO(legacy)) as archive:
        old = dict(line.split(":") for line in archive.read("pinyin_dict_char.txt").decode().splitlines())
        old = {char: value.split(",")[0] for char, value in old.items() if ord(char) <= 0xFFFF}
        readings = {char: readings.get(char, value) for char, value in old.items()}
        overrides = {char: value for char, value in old.items() if readings[char] != value}
        readings.update(overrides)
        phrases = dict(line.split(":") for line in archive.read("pinyin_dict_phrase.txt").decode().splitlines())
        phrases = {key: value.split() for key, value in phrases.items() if all(0x4E00 <= ord(c) <= 0x9FA5 for c in key)}
    syllables = sorted(set(readings.values()) | {s for values in phrases.values() for s in values})
    ids = {value: i for i, value in enumerate(syllables)}
    alphabet = sorted(set("".join(phrases)))
    char_ids = {char: i for i, char in enumerate(alphabet)}
    syllable_width = (len(syllables) - 1).bit_length()
    char_width = (len(alphabet) - 1).bit_length()
    runs = []
    for char in sorted(readings):
        if not runs or ord(char) != ord(runs[-1][-1]) + 1:
            runs.append([])
        runs[-1].append(char)
    output = io.BytesIO()
    output.write(b"MPY1")
    output.write(struct.pack(">H", len(syllables)))
    for syllable in syllables:
        encoded = syllable.encode()
        output.write(struct.pack(">H", len(encoded)) + encoded)
    output.write(struct.pack(">H", len(alphabet)))
    for char in alphabet:
        output.write(struct.pack(">H", ord(char)))
    output.write(struct.pack(">BBHI", syllable_width, char_width, len(runs), len(phrases)))
    bits = Bits(output)
    for run in runs:
        bits.write(ord(run[0]), 16)
        bits.write(len(run), 16)
        for char in run:
            bits.write(ids[readings[char]], syllable_width)
    previous = ""
    for phrase, values in sorted(phrases.items()):
        assert len(phrase) == len(values)
        prefix = 0
        while prefix < min(len(phrase), len(previous)) and phrase[prefix] == previous[prefix]:
            prefix += 1
        bits.write(prefix, 4)
        bits.write(len(phrase) - prefix, 4)
        for char in phrase[prefix:]:
            bits.write(char_ids[char], char_width)
        for char, value in zip(phrase, values):
            changed = value != readings.get(char, char)
            bits.write(int(changed), 1)
            if changed:
                bits.write(ids[value], syllable_width)
        previous = phrase
    bits.finish()
    data = output.getvalue()
    assert len(data) < 300_000, len(data)
    (DEST / "mandarin.bin").write_bytes(data)
    print(f"{len(readings)} characters, {len(overrides)} legacy overrides, {len(phrases)} phrases; {len(data)} bytes")


if __name__ == "__main__":
    main()
