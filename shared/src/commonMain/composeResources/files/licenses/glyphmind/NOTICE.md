# Notices and provenance

Glyphmind code is GPL-3.0-only. The licenses below apply to the corresponding upstream code and datasets. Keep this file and `licenses/` when redistributing the library or dictionary packs.

## Metrolist

The romanization rules, word alignment, Mandarin decoder and regression tests come from [Metrolist](https://github.com/MetrolistGroup/Metrolist), revision `1404ba751491965ae943ff1845d8d7e08a6f2093`, following the history behind tag `v14.0.0`. Copyright 2026 Metrolist contributors, including **l6t9**. Original file headers remain in the port. Contributor history is available at the linked repository; these changes do not claim sole authorship. The GPL version 3 text is in `LICENSE`. Glyphmind selects GPL-3.0-only, not a permissive relicensing of those contributions.

## Kuromoji and IPADIC

`JapaneseDictionary.kt` ports the NORMAL-mode lattice construction, unknown-word grouping, punctuation splitting and least-cost path selection from Atilika's Kuromoji 0.9.0. Copyright 2010-2015 Atilika Inc. and contributors, Apache-2.0. Glyphmind replaces Java buffers and the double-array trie with common Kotlin and sorted prefix lookup. It excludes search/extended modes and user dictionaries, which Metrolist did not use.

`scripts/ExportJapanese.java` transforms the published `kuromoji-ipadic-0.9.0.jar` dictionary into GJP1. Costs, entry order and pronunciation-before-reading selection remain unchanged. The input is `mecab-ipadic-2.7.0-20070801`. Copyright 2000-2003 Nara Institute of Science and Technology. Its NAIST permission and warranty terms and ICOT conditions are retained in `licenses/IPADIC-NOTICE.md` and `licenses/Kuromoji-NOTICE.md`. The Apache text and contributor list are in `licenses/Kuromoji-LICENSE.md` and `licenses/Kuromoji-CONTRIBUTORS.md`.

The Japanese regression oracle was generated with the published JVM Kuromoji 0.9.0 tokenizer. The first 44 inputs are test sentences written for this project; the remaining 50 are FLORES-200 Japanese devtest rows 1-50. Token boundaries and readings are generated outputs. Source hashes are pinned in `scripts/build_packs.py`.

## Mandarin and Unicode

The MPY1 pack combines Unicode Unihan 17.0.0 `kMandarin` readings with the character and phrase dictionaries from [houbb/pinyin 0.4.0](https://github.com/houbb/pinyin). Metrolist's conversion retains historical first readings, phrase selection and BMP coverage. Glyphmind moves the table to a verified download and ports its decoder to common Kotlin. Upstream inputs and SHA-256 values remain pinned in `scripts/generate_mandarin_table.py`.

Unihan uses Unicode License V3, retained in `licenses/Unicode-LICENSE.txt`. Pinyin's published POM declares Apache-2.0; its upstream text is retained in `licenses/pinyin-LICENSE.txt`. The Tatoeba snapshot builder also uses Unihan simplified/traditional variant properties to label Chinese examples.

## Detector corpora

[FLORES-200](https://github.com/facebookresearch/flores/tree/main/flores200), created by Meta AI and its translators, uses CC-BY-SA-4.0. The original archive is `https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz`, SHA-256 `b8b0b76783024b85797e5cc75064eb83fc5288b41e9654dabc7be6ae944011f6`. Training uses dev rows 1-800. Devtest is evaluation-only. The license is in `licenses/CC-BY-SA-4.0.txt`. The source archive contains article metadata and translator-produced sentences. The committed evaluation sample retains their language and row order.

[Tatoeba](https://tatoeba.org/en/downloads) sentence exports use CC-BY-2.0-FR. `data/tatoeba-2026-11-14.tsv.gz` is a selected snapshot of the weekly exports fetched on 2026-11-16. Each row retains the language, split, sentence ID, owner credit and original text. Sentence attribution links are `https://tatoeba.org/en/sentences/show/<id>` and profile links are `https://tatoeba.org/en/user/profile/<owner>`. A `\\N` owner denotes an orphaned sentence; the sentence URL retains its contribution history. Credit belongs to the respective Tatoeba contributors, not Glyphmind. Original export URLs, sizes and hashes are in `data/tatoeba-sources.json`. The complete French license is in `licenses/CC-BY-2.0-FR.html`.

The snapshot SHA-256 is `1215f0be4c38c74d6d89c6b90831edde9ea0f4ec9d224ba243479d145c4b2e65`. The acquisition script deduplicates text, filters sentence length, selects by a stable hash and retains a disjoint test split. The training script lowercases text, hashes character n-grams and quantizes the learned weights. The derived model is distributed under CC-BY-SA-4.0 with the Tatoeba attribution above. It contains weights, not a sentence lookup dictionary. `data/heldout.tsv` and generated test fixtures retain the source licenses.

## Build and runtime dependencies

Kotlin, kotlinx-coroutines, kotlinx-io and the Gradle wrapper are distributed under their upstream Apache-2.0 licenses. The pack builder invokes the pinned Kuromoji artifacts only during data generation. NumPy is a training tool and is not shipped in the library. No HTTP client, JavaScript engine, platform transform or native interop implementation is part of Glyphmind.
