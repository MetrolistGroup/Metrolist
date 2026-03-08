# Strings and translations

## Catalogs

All user-facing text lives in `shared/src/commonMain/composeResources/values*/strings.xml`. Add English only to `values/strings.xml`, including KMP-only keys. Translators maintain locale catalogs through [Weblate](https://translate.metrolist.cc/projects/metrolist-kmp/metrolist-kmp/). Missing translations fall back to base English at runtime.

Desktop and shared Compose code use `com.metrolist.shared.generated.resources.Res`. Android's `generateAndroidStrings` scans non-test sources in `androidApp/src` and `shared/src/androidMain` for `R.string`, `R.plurals`, `@string` and `@plurals` references. It generates only those keys for each locale. Missing referenced base keys fail the build. Do not create platform-local catalogs or translation tables.

Gradle derives bundled language tags from resource directories. Hinglish uses `values-b+hi+Latn` and `hi-Latn`; saved `hin` selections migrate to that tag. `prepareComposeResourceAliases` generates `values-id` from `values-in` and `values-he` from `values-iw` for Compose. Weblate edits the original catalogs, not generated aliases.

Content-language and country preferences use the selected code or system locale. UI resource fallback does not authorize a hardcoded content-language default.

## Escapes

Use plain apostrophes and single percent signs in shared text. Compose displays Android-only `\'` and `%%` literally. For formatting plus a literal percent sign, pass the percent sign as a separate indexed string argument.

The Android generator converts apostrophes to `\u0027`, including plural items and nested markup. Do not apply Android escaping to the shared source catalog.

Configure the Weblate component for these catalogs as **Compose Multiplatform Resource** (`cmp-resource`), not **Android String Resource**. The Android format emits `\'` for apostrophes, which Compose displays literally. This is a server-side component setting and is not controlled by repository files.

## Translation mechanics

Keep placeholders, command syntax and locale text intact. Do not copy English into translated catalogs. Preserve non-breaking spaces in existing separators and measurement units.

Crash UI and report/export labels intentionally use English. Set `translatable="false"` on `crash_*` and `database_downgrade`, and remove locale overrides. Use crash-specific actions instead of changing translated generic actions. The validator rejects translated crash keys and excludes non-translatable keys from missing-key reports.

Imported catalogs retain source headers and the [attribution notice](translation-attribution.md). Builds read shared catalogs directly; there is no upstream translation-import step.

## Pruning

`python3 scripts/find_unused_strings.py` reports unreferenced base keys. `--apply` removes them from every catalog without rewriting remaining bytes. Literal mentions protect keys; dynamic literal prefixes protect matching families. Review the result before staging. The script never changes source code.

## Validation

```sh
python3 scripts/check_compose_resource_escapes.py
python3 scripts/test_compose_resource_escapes.py
python3 scripts/check_translation_mechanics.py
python3 scripts/test_translation_mechanics.py
python3 scripts/test_find_unused_strings.py
python3 scripts/test_generate_android_strings.py
```

The last command runs an isolated Gradle build. Set `GRADLE` to use an executable other than `./gradlew`. The [pre-commit hook](checks.md#hooks) checks staged XML, including malformed XML, without reading unstaged edits.
