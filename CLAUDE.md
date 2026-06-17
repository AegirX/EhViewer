# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build default flavor (minSdk 26, arm64-v8a + x86_64)
./gradlew assembleDefaultRelease

# Build marshmallow flavor (minSdk 23, all 3 ABIs)
./gradlew assembleMarshmallowRelease

# Build debug
./gradlew assembleDefaultDebug

# Format check (ktlint via Spotless)
./gradlew spotlessCheck

# Lint check
./gradlew lintMarshmallowRelease

# Check build-logic convention plugins
./gradlew :build-logic:convention:check

# Rust checks (run from app/src/main/rust)
cargo fmt --all -- --check
cargo clippy --target aarch64-linux-android --all-features -- -D warnings
```

Requires: Java 25 (Eclipse Temurin), Rust toolchain with `thumbv7neon-linux-androideabi` target, Android NDK 29.0.14206865.

## Architecture

### Module Dependency Hierarchy

```
:app  →  :core:data, :core:i18n, :core:ui
:core:data  →  :core:common
:core:ui  →  :core:common
:core:i18n  →  (independent KMP library for Moko resources)
```

All `:core:*` modules are Kotlin Multiplatform libraries (Android + common source sets). The `:app` module is an Android application with two product flavors: `default` (minSdk 26) and `marshmallow` (minSdk 23).

### Native Code

The app ships two native libraries built via CMake:
- **C** (`app/src/main/cpp/`): `archive.c`, `gifutils.c`, `hash.c`, `strnatcmp.c` — wraps libarchive, libwebp, nettle, and lzma for archive extraction (ZIP/RAR/7z), WebP decoding, hashing, and natural sorting.
- **Rust** (`app/src/main/rust/`): Compiles via Corrosion as a static library (`ehviewer_rust`). Handles HTML parsing (`parser.rs`), image processing (`img.rs`), CBOR serialization, QR code reading, and JNI bridging (`ffi.rs`). The JNI calls are declared in `com.hippo.ehviewer.jni.*`.

### App Source Structure

All app Kotlin code lives under `com.hippo.ehviewer` in `app/src/main/kotlin/`:

| Package | Purpose |
|---|---|
| `client/` | E-Hentai API client — URL builders, parsers (`client/parser/`), data types, error handling |
| `coil/` | Coil image loader interceptors and decoders (animated WebP, GIF, merge, crop borders, QR code detection) |
| `download/` | Download manager, background `DownloadService` |
| `gallery/` | Page loading abstraction (`PageLoader`, `EhPageLoader`, `ArchivePageLoader`) |
| `image/` | Image model |
| `jni/` | JNI extern declarations for native C/Rust functions |
| `ktor/` | Ktor HTTP client configuration (Cronet, OkHttp extensions) |
| `spider/` | SpiderQueen — crawling engine for gallery metadata and downloads |
| `ui/` | All Compose UI |
| `ui/screen/` | Top-level screen composables (GalleryListScreen, GalleryDetailScreen, FavoritesScreen, DownloadsScreen, HistoryScreen, etc.) |
| `ui/main/` | Reusable gallery UI components (GalleryList, GalleryDetail, GalleryTag, TorrentList, etc.) |
| `ui/settings/` | Settings screens |
| `ui/reader/` | Image reader: PagerViewer, WebtoonViewer, GalleryPager, key events, reader settings |
| `ui/tools/` | UI utilities (Dialog, State helpers, Paging utils, BBCode rendering) |
| `ui/theme/` | Material 3 theming |
| `util/` | General utilities (AppConfig, CrashHandler, FileUtils, etc.) |
| `legacy/` | Compatibility shims for old SpiderInfo format |

### Navigation

Uses **Compose Destinations** with KSP code generation. Destinations are declared via annotations; generated code goes to `com.hippo.ehviewer.ui.destinations`. The `navWithUrl()` function in `MainNav.kt` maps URL strings to destinations. Each Screen composable provides a `context(ScreenContext)` with `MainActivity`, `SnackbarHostState`, `DialogState`, `SharedTransitionScope`, and `DestinationsNavigator`.

### Key Libraries

- **Arrow-kt** — functional programming (Either, Ior, etc.)
- **Ktor** — HTTP client (with OkHttp engine on Android)
- **Coil 3** — image loading with custom interceptors
- **Room** — SQLite persistence (entities, DAOs in `core:data`)
- **DataStore** — preferences (in `core:data`)
- **Telephoto** — zoomable image composable for the reader
- **Compose Material 3** — UI toolkit (with JetBrains Compose Multiplatform forks)
- **Moko Resources** — multiplatform string/asset resources (`core:i18n`)

### Build Logic

Custom Gradle convention plugins in `build-logic/convention/`:
- `AndroidApplicationConventionPlugin` (`ehviewer.android.application`) — Android app defaults
- `MultiplatformLibraryConventionPlugin` (`ehviewer.multiplatform.library`) — KMP library defaults + Lint + Spotless
- `MultiplatformLibraryComposeConventionPlugin` (`ehviewer.multiplatform.library.compose`) — above + Compose Multiplatform

All Kotlin compilation uses `progressiveMode = true`, context parameters (`-Xcontext-parameters`), and experimental Compose/Material3 APIs.

### CI

CI runs on GitHub Actions (`.github/workflows/ci.yml`):
- `check` job: Rust formatting + clippy, build-logic check, Spotless check, Lint check (PR only)
- `default` / `marshmallow` jobs: Build release APKs for all ABIs, upload artifacts
- Baseline profile generation in `baseline-profile.yml`
