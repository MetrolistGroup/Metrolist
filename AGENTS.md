# Agent guide

Metrolist is a YouTube Music client for Android, desktop and iOS.

## One task per PR

- Keep each PR to one task or issue. Include all affected callers and platforms needed to complete it.
- Include necessary tests, strings and documentation. Leave unrelated features, refactors, formatting, dependency updates and opportunistic fixes out.
- Agree on separate changes for independent goals before implementing them. Ask about unclear behavior; do not invent requirements.
- Check `git status` first. Preserve existing work; never stage, revert or format unrelated files.
- Do not commit, push, open a PR, merge or rewrite history without explicit human approval. Do not change `appVersion` unless release work is explicitly authorized.

- Do **not** commit, push, merge, or rewrite git history unless a human explicitly asks
- Never force push to `main`. On other branches, use `--force-with-lease` when an authorized history rewrite requires a force push.
- Prefer a PR over a direct push to the target branch, including when write permissions allow direct pushes. Permission to write is not permission to bypass review.
- Do **not** bump app version
- Build before requesting review; ask humans when requirements are unclear
- Match existing code style; comment only non-obvious logic
- Do **not** use hardcoded English (`"en"`) or country (`"US"`) defaults. Always derive locale from system locale, user settings, or resource directories. Never silently fall back to English.

## Rejected features

Do not propose or implement these additions:

- Liquid Glass UI.
- Other streaming services as music-playback backends.
- Google Play Store publication or distribution support.
- Comment sections.
- YouTube Shorts.
- Music alarms.
- New-music notifications.
- Multiple queues.
- Listen Together profile pictures taken directly from local storage without uploading them.
- Tizen TV support.
- A web/browser client.
- Audio visualizers.
- Custom app icons.

- Keep each PR focused on one problem or feature and inspect the complete diff against the remote base before publication.
- Before starting and before publishing, check the canonical issue and related PRs, including cross-repository references, for assignments and existing efforts. Do not take over assigned work or duplicate an existing implementation; report unclear overlap with links.
- Preserve attribution, provenance, license notices and unrelated changes. Do not alter another contributor's work or issue assignments without authorization.

Explain this policy and ask for a maintainer decision before coding a rejected feature. Do not delete existing functionality. Playlist imports, artwork and scrobbling are separate from streaming backends.

- When a PR fixes an issue, always reference it in the PR body with `fixes: #<issue>` or `resolves: #<issue>`. When an issue is associated with the PR but is not resolved by it, use `related to: #<issue>`.
- Only when pushing without a PR, put the issue reference in the commit message instead, using the same keywords. When there is a PR, keep the reference in the PR body.
- Keep the PR body to a few lines of plain prose, without lists. Answer only what was solved and why, plus any required issue reference. Omit emphatic claims, filler, boilerplate, generic claims, and unnecessary commentary.
- Keep both PR bodies and commit messages clean, factual, and accurate to the actual changes. Never invent work performed, test results, or issue resolutions.
- Never merge a draft PR. Before merging any PR, confirm it is marked ready for review and its author is not still working on it.
- When a commit fixes an issue, reference it with `fixes:`. Use `related to:` for partial fixes and diagnostics.

## Writing to maintainers

- Before opening an issue or PR, check it against [Rejected features](#rejected-features) and search open and closed issues and PRs for the same problem. Do not file duplicates or rejected features.
- Apply [unslop](skills/unslop/SKILL.md) to every issue, PR body, comment and commit message you write, then cut it to the facts a maintainer needs.

## Code quality constraints

- **No hardcoded strings.** Never hardcode user-facing strings or UI text in Kotlin code. Always extract strings into `shared/src/commonMain/composeResources/values/strings.xml` and access them via `stringResource(Res.string.<key>)` or `pluralStringResource(Res.plurals.<key>, ...)`. Translators localize these strings through Weblate.
- **No hardcoded fallback values.** Never use hardcoded language codes (e.g. `"en"`, `"de"`, `"it"`, `"es"`) as fallbacks or defaults. Derive locale-specific values from resource directories, system locale, or user settings at runtime.
- **No placeholders or workarounds.** Do not introduce placeholder values, hardcoded fallback strings, or workaround hacks. If a value depends on configuration, resolve it properly from settings or resources.
- **No hardcoded lists of supported languages.** Never hardcode `listOf("en", "de", "es", "it")` or similar. Derive bundled languages dynamically from the `composeResources/values*/` directory structure.
- **No inline fully qualified class names.** Always use proper `import` statements. Never write `com.metrolist.shared.viewmodels.UserPlaylist` inline; import it and use `UserPlaylist`.
- **No foreign strings in resource files.** Each `values-XX/strings.xml` must contain only text in language XX. The base `values/strings.xml` must contain only English. Never mix languages across resource files (e.g. no Italian in `values/strings.xml`, no English in `values-it/strings.xml`).
- **Desktop homepage sections:** Do not show library or locally-sourced sections on the desktop home page. Personalized API recommendation sections and Speed Dial are allowed, but filter out all `FEmusic_library*` browse IDs and library/local-only sections.
- **Content language must respect user settings.** When `contentLanguage` or `contentCountry` is set to `SYSTEM`, use the JVM system locale. When set to a specific code, use that code. Never silently fall back to English.
- **Shared strings:** Android and desktop string resources live only in `shared/src/commonMain/composeResources/values*/strings.xml`. Desktop uses `com.metrolist.shared.generated.resources.Res`; Android gets a generated subset containing only the keys it references (see `docs/strings.md`).

## Modules

| Module | Work here for |
| --- | --- |
| `androidApp` | Android UI, lifecycle and playback integration. |
| `desktopApp` | Desktop UI and application integration. Kotlin is in `src/jvmMain/kotlin/`; resources belong in `src/desktopMain/resources/`. |
| `iosApp` | Swift/Xcode host. Shared iOS Kotlin is in `shared/src/iosMain/`. |
| `shared` | Reusable logic and UI in `commonMain`, JVM HTTP/SABR in `jvmSharedMain`, desktop FFmpeg in `desktopMain`. |
| `listenTogetherProto` | JVM protobuf generation. Models and codecs live in `shared`. |
| `metroproto` | Upstream protobuf schema submodule. Do not modify it here. |
| `opusDecoder` | Vendored Media3/libopus JNI decoder. `buildOpusJni` is cacheable and builds ARM ABIs only. |

InnerTubeX is an external dependency maintained in a separate repository.

Use [development.md](docs/development.md) for setup and the code map. Before build, packaging, CI, strings or protocol changes, read the relevant [reference](docs/README.md). Update it when behavior changes.

## Implementation rules

- DEFAULT (Standard) player seek bars must use the stock Material 3 `Slider`; never replace or restyle its thumb or track or change its dimensions. Passing the player palette through `colors` is the only allowed customization.
- Read the connected code and its callers first. Reuse existing components and patterns. Fix shared causes in shared code.
- Match existing style and use imports instead of inline fully qualified class names. Comment only non-obvious reasons.
- Do not add placeholder values, hardcoded fallback strings or workaround hacks. Resolve configuration from settings or resources and preserve validation and error handling.
- Define preference keys in `PreferenceKeys.kt`; access them through `Settings.shared.<name>`.
- On Android, use `stringResource()`, `colorResource()` and `painterResource()`, not `LocalContext.current` for lookups. Use `LocalResources.current` for direct access. Add `ExperimentalMaterial3Api` or `ExperimentalMaterial3ExpressiveApi` opt-ins when required.
- Desktop Home must exclude library/local-only sections and `FEmusic_library*` browse IDs. Personalized API recommendations and Speed Dial are allowed.
- Keep Listen Together's upstream schema, JVM generation, shared models and both codecs synchronized, including `PortableMessageCodec`. Follow [listen-together.md](docs/listen-together.md#protocol). Do not replace shared models with generated JVM types.

## Strings and locale

- Never hardcode user-facing Kotlin text. Add English to `shared/src/commonMain/composeResources/values/strings.xml` and use `stringResource(Res.string.<key>)` or `pluralStringResource(Res.plurals.<key>, ...)`.
- Desktop uses `com.metrolist.shared.generated.resources.Res`; Android generates its referenced subset. Do not create platform-local catalogs.
- Add English only to `values/strings.xml`, including KMP-only keys. The shared XML catalogs are the single source of truth; translators maintain `values-*/strings.xml` only through Weblate as described in [strings.md](docs/strings.md). Missing UI translations fall back to the base English resources at runtime. Do not add translation side tables or copy English into locale catalogs.
- Derive locale defaults and fallbacks from system locale, settings or resources. Never silently fall back to English or hardcode language or country defaults. Derive bundled languages from `composeResources/values*/`, never a hardcoded list.
- When `contentLanguage` or `contentCountry` is `SYSTEM`, use the JVM system locale. Otherwise use the selected code.

## Verify and report

- Update backend tests to cover the new behavior. Do not add or maintain coded UI tests or trivial tests.
- Build affected code and run relevant [checks](docs/checks.md) before review. Fix failures you introduce; report unrelated blockers without expanding scope.
- After pushing, check the PR's CI jobs. A failing job is a real result: fix it, or report the job, failing step and log excerpt. Do not describe a failing or unfinished job as passing, absent or irrelevant, even when the failure comes from the base branch.
- Format changed Kotlin with `ktlint --format`. Documentation-only edits need link checks and `git diff --check`, not an app rebuild.
- Live YouTube tests are opt-in and never run in CI. Ask for device or emulator testing when backend checks are insufficient.
- Review the final diff for unrelated changes, debug code and secrets. Report actual changes, commands, results and unverified behavior. Never invent verification.

## PRs and commits

- Use Conventional Commits for commits and PR titles, such as `fix(player): preserve queue position`. Name new branches `<type>/<short-kebab-case-description>`.
- Keep PR bodies to a few factual sentences without lists. Describe the actual change; omit filler and invented claims.
- Reference a fixed issue in the PR body with `fixes: #<issue>` or `resolves: #<issue>`. Use `related to: #<issue>` when it is not resolved.
- Keep issue references in the PR body, not the commit message. Only when pushing without a PR, put the reference in the commit message using the same keywords.
