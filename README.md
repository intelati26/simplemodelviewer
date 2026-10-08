# simplemodelview (SPC REFS viewer)

Minimalist Android / iOS viewer for SPC's REFS ensemble products. Scope and decisions: [docs/outline.md](docs/outline.md)
(source: `android-spc-refs-scope.md`).

**Data:** NOAA/NWS Storm Prediction Center (experimental guidance, not a warning or decision-support tool).
**iOS** is best effort and untested on a real device (no Apple Developer membership); CI builds unsigned simulator builds only.
**License:** GPLv3 while code derived from wxqt is included (decision pending, see outline section 6).

## Layout
| Path | State |
|---|---|
| `core/` (C++; C API names still `spc_*`) | C++ core with a C API: blosc (lz4 + zstd) decode, dtype conversion, Lambert grid, rasteriser. Builds and passes tests on Linux. |
| `shared/` | Platform-independent Kotlin: cycle probing, Zarr metadata, ~120 SPC product titles and color tables, repository, read-out text, sectors. Unit tests pass (`./gradlew :shared:jvmTest`). |
| `androidApp/` | Jetpack Compose app + JNI bridge to the core: product picker (search, favorites), cycle / member / sector menus, **GREFS** probability products (second grid), **globe view** (drag to turn, pinch to zoom), time slider and loop with prefetch, pinch-zoom, tap for value, saved places (bookmarks), **export** of the screen area, the whole map, any meso sector or the globe as a PNG or MP4 loop, home-screen widget, a "new run available" banner, two-pane tablet layout, settings (Wi-Fi-only loops, data saver, cache size). Builds and lints in CI; exercised on an emulator against live SPC data. |
| `iosApp/` | Not started. |
| `.github/workflows/` | `core-tests`, `android` (debug APK as artifact; attached to a release on `v*` tags), `ios` (core on macOS + iOS Simulator). |
| `scripts/` | `emu.sh` (drive an emulator with adb), `ui_smoke.sh` (the CI smoke test). |

## MRMS page
The "MRMS" button in the header opens a second page over the same renderers: 136 products in nine buckets (reflectivity, echo tops and VIL, rotation, hail, lightning, rainfall, flash flooding, quality, model fields; see `docs/mrms-products.md`), from the NOAA open data bucket (`noaa-mrms-pds`). The files are GRIB2 with PNG packing; the core decodes them itself (no GDAL), cutting the sector's window out a row at a time at 1 km (rotation: 500 m) or a coarser step for wide areas. The page follows new scans by itself, has selectable units (in / mm, F / C, kft / km), a categorical precipitation-type map, and has what the SPC page has: product buckets with favorites, meso sectors and a globe, tap read-out, saved places, a loop, share / export of a picture or loop video (a screen-area export is decoded afresh at the finest step that fits, so it keeps 1 km detail), settings (shared with the SPC page, including export / import) and the optional contact email. The decoder is checked against GDAL (`scripts/make_mrms_fixtures.py`, `core/tests`). How the NSSL viewer and the data are laid out: `docs/mrms-site-outline.md`.

## Colors
The SPC page uses the color tables of SPC's own viewer (levels, band colors, the colors beyond both ends and the 0.9 opacity), captured from its script: see `docs/spc-colormaps.md`.

## Play and loop length
Play starts at once on the frames that have already arrived and widens the cycle as the rest download (1-5, then 1-10, then 1-20, ...), going back to frame 1 at the end. "Loop: N" next to Play chooses how many frames it covers: the first N of a model run, or the newest N radar scans (6 / 12 / 24 / 36 or all); only those frames are downloaded, and a loop video export covers the same frames. The choice is remembered per page.

## Overlay
On the MRMS page, "Overlay" draws other products over the first. Choose **no overlay**, the **lightning nest**, or **one product** of your choice (any MRMS product: strikes, lightning probability, any of the 14 rotation products, hail size, ...). A chosen product has a **minimum value** ("show only rotation of at least 10") picked from its own band edges. The overlays are cut from the same area and matched to each radar frame by the nearest valid time (strike files come every minute, radar every 2); loops prefetch them too, exports include them, and the tap read-out shows every layer's value.

The **lightning nest** stacks strike density by recency, drawn in order so the freshest is on top: the last 30 minutes as a 20 % shadow, the last 5 minutes at 50 %, the last minute solid. Each strike is a dot (one strike is a single 1 km cell, too small to see) and the older windows get larger dots, so the layers read as nested rings: a solid core, a half-strength ring, a faint halo, each with a thin dark rim. Overlay files are small: strike density 1-2 KB, rotation tracks about 100-160 KB (30 min to 1 h), against about 1.4 MB for a radar frame. Overlays are not drawn on the globe.

## Alerts
On the MRMS page, "Alerts" outlines NWS products over the radar. Three layers, all **off by default**: **Warnings** (tornado, severe thunderstorm, flash flood, extreme wind, special marine, snow squall; storm-based polygons from NOAA's public watch/warning map service), **Watches** (tornado, severe thunderstorm, flash flood) and **Discussions** (SPC mesoscale discussions and WPC precipitation discussions, dashed, read from their public pages). Outlines use the NWS colors with a dark casing, and are drawn on the **newest frame only** with an "as of" time; they refresh about once a minute while the page is open. Tap inside an outline for a card with the type and times, and **Read** for the full text. Alerts are not tied to your location, and this is an unofficial copy that can be late or incomplete: the dialog says so, and weather.gov is the official source. Not drawn on the globe.

## Contour lines
On the SPC REFS page, heights (500 / 700 / 850 mb), mean sea-level pressure and temperatures (850 / 700 / 500 mb, 2 m temperature and dewpoint) also get contour lines over the colors: 6 dam for 500 mb heights, 3 dam for 700 / 850 mb, 4 hPa for pressure, 5 C or 10 F for temperatures; every fifth height / pressure line and the freezing line are heavier, and the longest line of each level carries a label (heights in decameters). Lines come from marching squares on the field itself (`shared/.../Contour.kt`, tested), so they follow the data in every sector and in shared pictures. Settings has a "Contour lines" switch (on by default). The MRMS page draws them for the model fields (freezing level every 2 kft or 0.5 km, surface and wet-bulb temperature). Not yet on the globe.

## UI tests (device or emulator)
```
./gradlew :androidApp:connectedDebugAndroidTest
```
`ZoomableMapTest` checks pinch and double-tap zoom on pictures of every shape, including a picture that changes shape while the screen is up. `AppZoomTest` pinches the real screen on CONUS and on a regional sector and needs the network; neither runs in CI.

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
