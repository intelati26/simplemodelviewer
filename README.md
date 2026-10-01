# simplemodelview (SPC REFS viewer)

Minimalist Android / iOS viewer for SPC's REFS ensemble products. Scope and decisions: [docs/outline.md](docs/outline.md)
(source: `android-spc-refs-scope.md`).

**Data:** NOAA/NWS Storm Prediction Center (experimental guidance, not a warning or decision-support tool).
**iOS** is best effort and untested on a real device (no Apple Developer membership); CI builds unsigned simulator builds only.
**Licence:** GPLv3 while code derived from wxqt is included (decision pending, see outline section 6).

## Layout
| Path | State |
|---|---|
| `core/` | C++ core with a C API: blosc (lz4 + zstd) decode, dtype conversion, Lambert grid, rasteriser. Builds and passes tests on Linux. |
| `shared/` | Platform-independent Kotlin: cycle probing, Zarr metadata, ~120 SPC product titles and colour tables, repository, read-out text, sectors. Unit tests pass (`./gradlew :shared:jvmTest`). |
| `androidApp/` | Jetpack Compose app + JNI bridge to the core: product picker (search, favourites), cycle / member / sector menus, **GREFS** probability products (second grid), **globe view** (drag to turn, pinch to zoom), time slider and loop with prefetch, pinch-zoom, tap for value, saved places (bookmarks), **export** of the screen area, the whole map, any meso sector or the globe as a PNG or MP4 loop, home-screen widget, a "new run available" banner, two-pane tablet layout, settings (Wi-Fi-only loops, data saver, cache size). Builds and lints in CI; exercised on an emulator against live SPC data. |
| `iosApp/` | Not started. |
| `.github/workflows/` | `core-tests`, `android` (debug APK as artifact; attached to a release on `v*` tags), `android-ui` (emulator smoke test with screenshots), `ios` (core on macOS + iOS Simulator). |
| `scripts/` | `emu.sh` (drive an emulator with adb), `ui_smoke.sh` (the CI smoke test). |

## Build and test the core
```
cmake -S core -B build && cmake --build build && ctest --test-dir build --output-on-failure
```

## Build the Android app
Needs the Android SDK + NDK (GitHub's runners have them; locally set `ANDROID_HOME`):
```
./gradlew :androidApp:assembleDebug     # -> androidApp/build/outputs/apk/debug/simplemodelview-debug.apk
```
Distribution is GitHub only for now (Releases / Actions artifacts). Requests carry a `simplemodelview/0.1` User-Agent plus the contact email you set in Settings (optional). Before wider use, write to SPC (see outline, risk 1).
