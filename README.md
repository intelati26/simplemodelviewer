# simplemodelview (SPC REFS viewer)

Minimalist Android / iOS viewer for SPC's REFS ensemble products. Scope and decisions: [docs/outline.md](docs/outline.md)
(source: `android-spc-refs-scope.md`).

**Data:** NOAA/NWS Storm Prediction Center (experimental guidance, not a warning or decision-support tool).
**iOS** is best effort and untested on a real device (no Apple Developer membership); CI builds unsigned simulator builds only.
**License:** GPLv3 (see `COPYING`); third-party code and data credits are in `NOTICE`.

## Layout
| Path | State |
|---|---|
| `core/` (C++; C API names still `spc_*`) | C++ core with a C API: blosc (lz4 + zstd) decode, dtype conversion, Lambert grid, rasteriser. Builds and passes tests on Linux. |
| `shared/` | Platform-independent Kotlin: cycle probing, Zarr metadata, ~120 SPC product titles and color tables, repository, read-out text, sectors. Unit tests pass (`./gradlew :shared:jvmTest`). |
| `androidApp/` | Jetpack Compose app + JNI bridge to the core: product picker (search, favorites), cycle / member / sector menus, **GREFS** probability products (second grid), **globe view** (drag to turn, pinch to zoom), time slider and loop with prefetch, pinch-zoom, tap for value, saved places (bookmarks), **export** of the screen area, the whole map, any meso sector or the globe as a PNG or MP4 loop, home-screen widget, a "new run available" banner, two-pane tablet layout, settings (Wi-Fi-only loops, data saver, cache size). Builds and lints in CI; exercised on an emulator against live SPC data. |
| `iosApp/` | Not started. |
| `.github/workflows/` | `core-tests`, `android` (debug APK as artifact; attached to a release on `v*` tags), `ios` (core on macOS + iOS Simulator). |
| `scripts/` | `make_nbm_fixtures.py` (real NBM GeoTIFFs for the core tests), `emu.sh` (drive an emulator with adb), `ui_smoke.sh` (the CI smoke test). |

## MRMS page
The "MRMS" button in the header opens a second page over the same renderers: 134 products in nine buckets (reflectivity, echo tops and VIL, rotation, hail, lightning, rainfall, flash flooding, quality, model fields; see `docs/mrms-products.md`), from the NOAA open data bucket (`noaa-mrms-pds`). The files are GRIB2 with PNG packing; the core decodes them itself (no GDAL), cutting the sector's window out a row at a time at 1 km (rotation: 500 m) or a coarser step for wide areas. The page follows new scans by itself, has selectable units (in / mm, F / C, kft / km), a categorical precipitation-type map, and has what the SPC page has: product buckets with favorites, meso sectors and a globe, tap read-out, saved places, a loop, share / export of a picture or loop video (a screen-area export is decoded afresh at the finest step that fits, so it keeps 1 km detail), settings (shared with the SPC page, including export / import) and the optional contact email. The decoder is checked against GDAL (`scripts/make_mrms_fixtures.py`, `core/tests`). How the NSSL viewer and the data are laid out: `docs/mrms-site-outline.md`.

## NBM page
The "NBM" button opens a third page over the same renderers: NOAA's National Blend of Models out to 11 days, 29 fields in six buckets (temperature, wind, rain and snow, sky and visibility, storms, probabilities such as "chance of 90 F or more"), from the open data bucket `noaa-nbm-pds`. The files are small GeoTIFFs, one per field and forecast hour, on NCEP's 2.5 km Lambert grid; the core decodes them itself (no GRIB). **Runs:** a new run every hour; the "Run" button lists them newest first and marks the **extended** ones (a week or more) with a star and how far each reaches; the default is the newest run that has arrived in full. **Loop:** frames come every hour, then every 3, then every 6 h, so Play holds each frame for as long as the forecast hours it stands for (a day of forecast takes the same time at any spacing); "Loop: N" picks how much of the run to play. Tap for a value, contour lines on temperatures, sectors, share / export of a picture or loop video. Details and measurements: `docs/nbm-products.md`.

## SPC outlook overlay
"SPC outlook" on the REFS, MRMS and NBM pages draws the Storm Prediction Center's outlook areas: pick a kind (categorical risk, or the tornado / wind / hail chance) and the **day follows the valid time of the frame shown**, Day 1 to Day 8 (an NBM frame two days out shows Day 3), so **the polygons move as a loop plays**. One choice for all pages (saved), faint fill with outline and a color key, tap for what it says at the point, included in exports, not on the globes. The comparison with wxqt's MRMS overlays, and what in the SPC file format is unverified from the build environment: `docs/spc-outlooks.md`.

## Colors
The SPC page uses the color tables of SPC's own viewer (levels, band colors, the colors beyond both ends and the 0.9 opacity), captured from its script: see `docs/spc-colormaps.md`.

## Play and loop length
Play starts at once on the frames that have already arrived and widens the cycle as the rest download (1-5, then 1-10, then 1-20, ...), going back to frame 1 at the end. "Loop: N" next to Play chooses how many frames it covers: the first N of a model run, or the newest N radar scans (6 / 12 / 24 / 36 or all); only those frames are downloaded, and a loop video export covers the same frames. The choice is remembered per page.

## Projection
The REFS page draws the model's own Lambert conformal grid. The MRMS files are plain latitude / longitude grids, which look stretched when drawn as they are, so the MRMS page **reprojects each decoded window onto a Lambert conformal grid** (REFS's projection: parallel 38.5, central meridian 97.5 W) in the core (`spc_plan_lambert_box`, `spc_reproject`, nearest cell). A sector becomes the bounding rectangle of its box in that projection, filled to the corners. Overlay products and the share / export crops are put onto the same grid (`spc_plan_lambert_crop` for a zoomed crop), so map lines, alert outlines, contours, places and the tap read-out all use one projection. Cells are a little smaller than the file's, so no cell is skipped. The MRMS globe still uses the file's own grid.

## Overlay
On the MRMS page, "Overlay" draws other products over the first. Choose **no overlay**, the **lightning nest**, or **one product** of your choice (any MRMS product: strikes, any of the 14 rotation products, hail size, ...). A chosen product has a **minimum value** ("show only rotation of at least 10") picked from its own band edges. The overlays are cut from the same area and matched to each radar frame by the nearest valid time (strike files come every minute, radar every 2); loops prefetch them too, exports include them, and the tap read-out shows every layer's value.

The **lightning nest** stacks strike density by recency, drawn in order so the freshest is on top: the last 30 minutes as a 20 % shadow, the last 5 minutes at 50 %, the last minute solid. Each strike is a small cross (one strike is a single 1 km cell, too small to see) and the older windows get larger dots, so the layers read as nested rings: a solid core, a half-strength ring, a faint halo, each with a thin dark rim. Overlay files are small: strike density 1-2 KB, rotation tracks about 100-160 KB (30 min to 1 h), against about 1.4 MB for a radar frame. Overlays are not drawn on the globe.

**Halo.** Hail swaths and rotation tracks (as the base product or as an overlay) are edged in black with a white halo outside, as wxqt draws its swaths, so they read over any reflectivity color. **LightningCast.** The chance of lightning in the next hour comes from NOAA / CIMSS LightningCast (the GOES-East placefile wxqt also reads), not from MRMS grids: a "LightningCast" row on every page switches it on, drawn as contour lines at 10 / 30 / 50 / 70 / 90 % for the newest scan (on the MRMS page, the scan nearest the radar frame shown); it replaces the two MRMS lightning-probability products.

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
