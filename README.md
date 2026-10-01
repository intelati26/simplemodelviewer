# SPC REFS (working title)

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
| `androidApp/` | Jetpack Compose app + JNI bridge to the core: product picker with favourites and search, cycle / member / sector menus, time slider and loop with prefetch, pinch-zoom, tap for value, share as PNG, saved places, a point-forecast strip (value at a place for every hour), a "new run available" banner, and settings (Wi-Fi-only loops, data-saver half-resolution, cache size). **Builds and passes lint in CI; not yet run on a device or emulator.** |
| `iosApp/` | Not started. |
| `.github/workflows/` | `core-tests` and `android` (debug APK as artifact; attached to a release on `v*` tags). |

## Build and test the core
```
cmake -S core -B build && cmake --build build && ctest --test-dir build --output-on-failure
```

## Build the Android app
Needs the Android SDK + NDK (GitHub's runners have them; locally set `ANDROID_HOME`):
```
./gradlew :androidApp:assembleDebug
```
Before any release: put a real contact in `OkHttpHttp.USER_AGENT` and write to SPC (see outline, risk 1).
