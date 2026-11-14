# Mandarin data

Regenerate `shared/src/jvmSharedMain/resources/romanization/mandarin.bin` with:

```bash
python3 scripts/generate_mandarin_table.py
```

The generator pins downloads by SHA-256 and caches them under `build/desktop-slim`. Android and desktop share the converter in `jvmSharedMain` and bundle the table as a Java resource. Runtime needs neither network access nor a third-party romanization library; Android can romanize Chinese on first use while offline. iOS continues to use Foundation transliteration. Keep the licenses and attribution notice packaged with the table.

## Sources and compatibility

Base readings come from [Unicode Unihan 17.0.0](https://www.unicode.org/Public/17.0.0/ucd/Unihan.zip) `kMandarin`, under the [Unicode License V3](https://www.unicode.org/license.txt). Phrases and compatibility readings come from [pinyin 0.4.0](https://github.com/houbb/pinyin), whose published POM declares Apache License 2.0. Its [license](https://github.com/houbb/pinyin/blob/master/LICENSE.txt) and modification notice accompany the generated data.

Preserve the old BMP repertoire, first readings, longest-phrase-first selection, tone marks and punctuation behavior. Do not substitute frequency-based readings or remove phrases whose pronunciation matches individual characters; either change can alter segmentation and existing lyrics.

Traditional phrases remain unsimplified. Some non-CJK runs split into characters, and supplementary characters split into UTF-16 surrogate units. Fixing these requires an explicit behavior change and updated expectations in `MandarinRomanizerTest`.

## MPY1 format

Header fields are big-endian. They contain the magic, 16-bit syllable count with length-prefixed UTF-8 syllables, 16-bit phrase-alphabet count with UTF-16 characters, 8-bit index widths, 16-bit character-run count and 32-bit phrase count. Syllables exclude NUL and supplementary characters so Java `readUTF` can read their encoding.

The remaining bits are MSB-first with zero padding only at EOF. Character runs store 16-bit start/length followed by syllable indices. Sorted phrases store 4-bit shared-prefix and suffix lengths, suffix alphabet indices, and per-character bits for syllable overrides.

## Verification

The generator validates downloads, produces identical hashes on repeated runs and asserts a size below 300,000 bytes. Run `./gradlew :shared:desktopTest --tests '*MandarinRomanizerTest' :shared:testAndroidHostTest --tests '*MandarinRomanizerTest'` after changes. The shared JVM suite covers the compatibility corpus and lyrics integration on both platforms. Treat output differences as behavior changes, including after shrinking. `:desktopApp:verifyReleaseDistributableJars` requires the table and rejects old pinyin, nlp-common and heaven JARs.
