# Third-Party Notices

Nucleus itself is distributed under the MIT license (see [`LICENSE`](LICENSE)). This file lists
the third-party components that Nucleus **redistributes** — as derived source, as vendored source,
or as binaries inside a published artifact — together with their licenses and where the full
license text lives.

Full license texts are in [`licenses/`](licenses/):

| File | Applies to |
|------|------------|
| [`licenses/LICENSE-APACHE-2.0.txt`](licenses/LICENSE-APACHE-2.0.txt) | Gradle plugin (derived), `tao`, AccessKit crates (Apache option) |
| [`licenses/LICENSE-MIT-accesskit.txt`](licenses/LICENSE-MIT-accesskit.txt) | AccessKit crates (MIT option) |
| [`licenses/LICENSE-BSD-3-Clause-angle.txt`](licenses/LICENSE-BSD-3-Clause-angle.txt) | ANGLE runtime libraries and EGL/KHR headers |

Both artifacts that redistribute third-party code ship this file and `licenses/` inside their JAR
under `META-INF/` (`nucleus.decorated-window-tao` and the `dev.nucleusframework` Gradle plugin), as
required by Apache-2.0 §4(a).

---

## 1. Compose Multiplatform Gradle plugin — derived source (Apache-2.0)

The Nucleus Gradle plugin (`plugin-build/plugin`) is a **derivative work** of the JetBrains Compose
Multiplatform Gradle plugin (`org.jetbrains.compose.desktop.application`). 127 Kotlin files under
`plugin-build/plugin/src/` retain their original copyright header, for example:

```
Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
```

- Project: JetBrains Compose Multiplatform — https://github.com/JetBrains/compose-multiplatform
- License: Apache License, Version 2.0 — [`licenses/LICENSE-APACHE-2.0.txt`](licenses/LICENSE-APACHE-2.0.txt)
- Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.

Nucleus has substantially modified and extended these files (GraalVM native-image support,
electron-builder packaging backend, AOT cache generation, signing/notarization, auto-update
manifests, sandboxing). Modifications are © Elie Gambache and are MIT-licensed where they are
separable; the Apache-2.0 terms continue to apply to the derived files themselves.

## 2. tao — vendored source, compiled into a shipped binary (Apache-2.0)

`decorated-window-tao` vendors a patched fork of the Rust `tao` crate at
`decorated-window-tao/src/main/native/vendor/tao/`, redirected via `[patch.crates-io]`. It is
compiled into `libnucleus_tao.{dylib,so,dll}`, which ships inside the
`nucleus.decorated-window-tao` JAR.

- Project: tao — https://github.com/tauri-apps/tao
- Version: 0.35.0 (crates.io), plus the six local patches listed in
  `decorated-window-tao/src/main/native/vendor/tao-patches/README.md`
- License: Apache License, Version 2.0 — upstream text kept at
  `decorated-window-tao/src/main/native/vendor/tao/LICENSE`
- Copyright: Tauri Programme within The Commons Conservancy; The winit contributors

## 3. AccessKit — vendored source, compiled into a shipped binary (MIT OR Apache-2.0)

Three AccessKit crates are vendored and patched to project the accessibility tree to AT-SPI
(Linux) and UI Automation (Windows). Each divergence from upstream is documented in the crate's
`VENDORED.md`.

| Crate | Version | Path |
|-------|---------|------|
| `accesskit_windows` | 0.34.0 | `decorated-window-tao/src/main/native/vendor/accesskit_windows/` |
| `accesskit_unix` | 0.22.1 | `decorated-window-tao/src/main/native/vendor/accesskit_unix/` |
| `accesskit_atspi_common` | 0.19.1 | `decorated-window-tao/src/main/native/vendor/accesskit_atspi_common/` |

- Project: AccessKit — https://github.com/AccessKit/accesskit
- License: MIT OR Apache-2.0 (dual). Upstream texts are kept next to each vendored crate as
  `LICENSE-MIT` and `LICENSE-APACHE`.
- Copyright: The AccessKit Authors

## 4. ANGLE (libEGL.dll, libGLESv2.dll) — shipped binary (BSD 3-Clause)

The Tao Windows backend (`decorated-window-tao`) ships the ANGLE runtime libraries `libEGL.dll` and
`libGLESv2.dll` to provide a Direct3D 11 render path (OpenGL ES translated to D3D11, with a WARP
software fallback for RDP / VM / driverless environments).

- Project: The ANGLE Project — https://chromium.googlesource.com/angle/angle
- License: BSD 3-Clause — [`licenses/LICENSE-BSD-3-Clause-angle.txt`](licenses/LICENSE-BSD-3-Clause-angle.txt)
- Copyright 2018 The ANGLE Project Authors. All rights reserved.

The binaries are not committed to this repository; they are fetched at build time from a pinned
[Electron](https://github.com/electron/electron) release (SHA-256 verified) by
`decorated-window-tao/src/main/native/windows/fetch-angle.sh`. The same BSD 3-Clause text also
covers the vendored Khronos/ANGLE EGL headers used at build time
(`decorated-window-tao/src/main/native/vendor/angle-headers/LICENSE.angle`).

---

## Build-time tools (not redistributed)

These are resolved on the build machine and are **not** shipped inside any Nucleus artifact. They
are listed for transparency, not as a redistribution notice.

- **electron-builder** 26.15.5 (MIT) — packaging backend for 17 of the 18 target formats. The
  plugin embeds only a pinned `package.json` / `package-lock.json` pair
  (`plugin-build/plugin/src/main/resources/nucleus/electron-builder/`) and installs the tree with
  `npm ci --ignore-scripts` into a build-local directory. See
  `scripts/update-electron-builder-lock.sh`.
- **GraalVM** (GraalVM CE: GPLv2 with Classpath Exception; Oracle GraalVM: GFTC) — downloaded on
  demand when `graalvm { isEnabled = true }`. Selecting `GraalvmDistribution.ORACLE` makes the
  plugin emit a licensing warning, because the GFTC restricts charging a fee in connection with
  redistributing the program.

## Transitive Rust crates

`libnucleus_tao` statically links the full Rust dependency tree of the `nucleus_tao` crate (`jni`,
`once_cell`, the `gtk-rs` stack, `zbus`, `windows`, `x11-dl`, …), which is permissively licensed
(predominantly MIT and Apache-2.0). The authoritative, version-exact list is
`decorated-window-tao/src/main/native/Cargo.lock`; regenerate a per-crate license inventory with
`cargo license` or `cargo about` from `decorated-window-tao/src/main/native/`. Sections 2 and 3
above cover the crates Nucleus has forked and therefore redistributes as source.
