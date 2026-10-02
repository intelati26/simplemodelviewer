# simplemodelview (SPC REFS viewer)

Minimalist Android / iOS viewer for SPC's REFS ensemble products. Scope and decisions: [docs/outline.md](docs/outline.md)
(source: `android-spc-refs-scope.md`).

**Data:** NOAA/NWS Storm Prediction Center (experimental guidance, not a warning or decision-support tool).
**iOS** is best effort and untested on a real device (no Apple Developer membership); CI builds unsigned simulator builds only.
**Licence:** GPLv3 while code derived from wxqt is included (decision pending, see outline section 6).

## Layout
| Path | State |
|---|---|
| `core/` (C++; C API names still `spc_*`) | C++ core with a C API: blosc (lz4 + zstd) decode, dtype conversion, Lambert grid, rasteriser. Builds and passes tests on Linux. |
| `shared/` | Platform-independent Kotlin: cycle probing, Zarr metadata, ~120 SPC product titles and colour tables, repository, read-out text, sectors. Unit tests pass (`./gradlew :shared:jvmTest`). |
| `androidApp/` | Jetpack Compose app + JNI bridge to the core: product picker (search, favourites), cycle / member / sector menus, **GREFS** probability products (second grid), **globe view** (drag to turn, pinch to zoom), time slider and loop with prefetch, pinch-zoom, tap for value, saved places (bookmarks), **export** of the screen area, the whole map, any meso sector or the globe as a PNG or MP4 loop, home-screen widget, a "new run available" banner, two-pane tablet layout, settings (Wi-Fi-only loops, data saver, cache size). Builds and lints in CI; exercised on an emulator against live SPC data. |
| `iosApp/` | Not started. |
| `.github/workflows/` | `core-tests`, `android` (debug APK as artifact; attached to a release on `v*` tags), `android-ui` (emulator smoke test with screenshots), `ios` (core on macOS + iOS Simulator). |
| `scripts/` | `emu.sh` (drive an emulator with adb), `ui_smoke.sh` (the CI smoke test). |

## MRMS page
The "MRMS" button in the header opens a second page over the same renderers: 136 products in nine buckets (reflectivity, echo tops and VIL, rotation, hail, lightning, rainfall, flash flooding, quality, model fields; see `docs/mrms-products.md`), from the NOAA open data bucket (`noaa-mrms-pds`). The files are GRIB2 with PNG packing; the core decodes them itself (no GDAL), cutting the sector's window out a row at a time at 1 km (rotation: 500 m) or a coarser step for wide areas. The page follows new scans by itself, has selectable units (in / mm, F / C, kft / km), a categorical precipitation-type map, and has what the SPC page has: product buckets with favourites, meso sectors and a globe, tap read-out, saved places, a loop, share / export of a picture or loop video (a screen-area export is decoded afresh at the finest step that fits, so it keeps 1 km detail), settings (shared with the SPC page, including export / import) and the optional contact email. The decoder is checked against GDAL (`scripts/make_mrms_fixtures.py`, `core/tests`). How the NSSL viewer and the data are laid out: `docs/mrms-site-outline.md`.

## Play and loop length
Play starts at once on the frames that have already arrived and widens the cycle as the rest download (1-5, then 1-10, then 1-20, ...), going back to frame 1 at the end. "Loop: N" next to Play chooses how many frames it covers: the first N of a model run, or the newest N radar scans (6 / 12 / 24 / 36 or all); only those frames are downloaded, and a loop video export covers the same frames. The choice is remembered per page.

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

## Identity
Application ID / package: `io.github.intelati26.simplemodelview` (Kotlin app code); shared Kotlin: package `simplemodelview`; APK: `simplemodelview-debug.apk`.
