# simplemodelview (SPC REFS viewer)

Minimalist Android / iOS viewer for model and radar data from NOAA, built around SPC's REFS ensemble products. Scope and decisions:
[docs/outline.md](docs/outline.md) (source: `android-spc-refs-scope.md`). Current version: 0.3.0.

**Five pages** (the page menu in the header), one renderer and one core behind all of them:

| Page | Data | Section below |
|---|---|---|
| REFS | SPC's REFS and GREFS ensemble stores (Zarr), ~120 products | Colors, Contour lines, Wind barbs |
| MRMS | NOAA's MRMS radar and derived products (GRIB2) | MRMS page, Overlay, Alerts |
| NBM | National Blend of Models to 11 days (GeoTIFF, with a GRIB fallback) | NBM page |
| GFS | GFS upper air and surface fields to 16 days (GRIB2 byte ranges) | GFS page |
| Extended | A saved place's forecast for the next 11 days: graphs and a table from the NBM text bulletins | Extended page |

All pages have sectors, tap read-out, a loop, share and the data cache; the model pages (REFS, NBM, GFS) also have a **Time** drop list beside the slider that lists every valid time of the run, grouped by day, to jump straight to one; the SPC outlook overlay is on the REFS, MRMS and NBM pages (see below).

**Data:** NOAA/NWS Storm Prediction Center (experimental guidance, not a warning or decision-support tool).
**iOS** is best effort and untested on a real device (no Apple Developer membership); CI builds unsigned simulator builds only.
**License:** GPLv3 (see `COPYING`); third-party code and data credits are in `NOTICE`.

**Also in this project's family:** a fork of the Android app *wX* with the REFS and MRMS pages added, [intelati26/wx-refs](https://github.com/intelati26/wx-refs)
(private). It copies `shared/`, the Android JNI and map-line classes and `core/` and gives MRMS a car app with a live view; see that repo's README. A fix here
has to be carried over there by hand.

**Android Auto:** an initial car app (weather category: a radar picture and a pane to step products and sectors), see [docs/android-auto.md](docs/android-auto.md).

## Layout
| Path | State |
|---|---|
| `core/` (C++; C API names still `spc_*`) | C++ core with a C API: blosc (lz4 + zstd) decode, dtype conversion, MRMS and NBM / GFS GRIB2 decoding (packings 5.0 / 5.2 / 5.3 / 5.41 and the Lambert and lat / lon grid templates; partial decode of a message's first rows), Lambert grids and reprojection, rasteriser. Builds and passes tests on Linux; the GRIB fixtures are checked against GDAL / pygrib. |
| `shared/` | Platform-independent Kotlin: cycle probing, Zarr metadata, ~120 SPC product titles and color tables, the MRMS, NBM and GFS catalogs and repositories, read-out text, sectors, contours, wind barbs, alerts, SPC outlooks, LightningCast and ProbSevere parsing. Unit tests pass (`./gradlew :shared:jvmTest`). |
| `androidApp/` | Jetpack Compose app + JNI bridge to the core: the four pages above with product pickers (search, favorites), sector menus, a globe view on the REFS and MRMS pages, time slider and loop with prefetch, pinch-zoom, tap for value, saved places (bookmarks), export of the screen area, the whole map or a meso sector as a PNG or MP4 loop, a home-screen widget, a "new run available" banner, a two-pane tablet layout, settings (Wi-Fi-only loops, data saver, cache size) and a car app (`docs/android-auto.md`). Builds and lints in CI; exercised on an emulator against live data. |
| `iosApp/` | Not started. |
| `.github/workflows/` | `core-tests`, `android` (debug APK as artifact; attached to a release on `v*` tags), `ios` (core on macOS + iOS Simulator). |
| `scripts/` | `make_nbm_fixtures.py` (real NBM GeoTIFFs for the core tests), `emu.sh` (drive an emulator with adb), `ui_smoke.sh` (the CI smoke test). |

## MRMS page
The "MRMS" button in the header opens a second page over the same renderers: 134 products in nine buckets (reflectivity, echo tops and VIL, rotation, hail, lightning, rainfall, flash flooding, quality, model fields; see `docs/mrms-products.md`), from the NOAA open data bucket (`noaa-mrms-pds`). The files are GRIB2 with PNG packing; the core decodes them itself (no GDAL), cutting the sector's window out a row at a time at 1 km (rotation: 500 m) or a coarser step for wide areas. The page follows new scans by itself, has selectable units (in / mm, F / C, kft / km), a categorical precipitation-type map, and has what the SPC page has: product buckets with favorites, meso sectors and a globe, tap read-out, saved places, a loop, share / export of a picture or loop video (a screen-area export is decoded afresh at the finest step that fits, so it keeps 1 km detail), settings (shared with the SPC page, including export / import) and the optional contact email. The decoder is checked against GDAL (`scripts/make_mrms_fixtures.py`, `core/tests`). How the NSSL viewer and the data are laid out: `docs/mrms-site-outline.md`.

## NBM page
The "NBM" button opens a third page over the same renderers: NOAA's National Blend of Models out to 11 days, 29 fields in six buckets (temperature, wind, rain and snow, sky and visibility, storms, probabilities such as "chance of 90 F or more"), from the open data bucket `noaa-nbm-pds`. The files are small GeoTIFFs, one per field and forecast hour, on NCEP's 2.5 km Lambert grid; the core decodes them itself (no GRIB). **Runs:** a new run every hour; the "Run" button lists them newest first and marks the **extended** ones (a week or more) with a star and how far each reaches; the default is the newest run that has arrived in full. **Loop:** frames come every hour, then every 3, then every 6 h, so Play holds each frame for as long as the forecast hours it stands for (a day of forecast takes the same time at any spacing); "Loop: N" picks how much of the run to play. Tap for a value, contour lines on temperatures, sectors, share / export of a picture or loop video. Details and measurements: `docs/nbm-products.md`.

## SPC outlook overlay
"SPC outlook" on the REFS, MRMS and NBM pages draws the Storm Prediction Center's outlook areas: pick a kind (categorical risk, or the tornado / wind / hail chance) and the **day follows the valid time of the frame shown**, Day 1 to Day 8 (an NBM frame two days out shows Day 3), so **the polygons move as a loop plays**. One choice for all pages (saved), faint fill with outline and a color key, tap for what it says at the point, included in exports, not on the globes. The comparison with wxqt's MRMS overlays, and what in the SPC file format is unverified from the build environment: `docs/spc-outlooks.md`.

## Data cache
Model data (GRIB messages, Zarr chunks, MRMS scans, NBM rasters) is kept for 24 hours from the model run or from the download, then dropped. Settings has the cache size
(50 MB, 150 MB, 300 MB or 1 GB); when it is full the least recently used data goes first. GRIB messages of one source file are kept together on disk as one GRIB2 file
with a small SQLite table of what is stored (source, field, place, run, download and last use), and Settings shows the figures with a Clear for each model. There is no
daily download limit: one was added and then removed in favor of the cache.

## Colors
The SPC page uses the color tables of SPC's own viewer (levels, band colors, the colors beyond both ends and the 0.9 opacity), captured from its script: see `docs/spc-colormaps.md`.

## Play and loop length
Play starts at once on the frames that have already arrived and widens the cycle as the rest download (1-5, then 1-10, then 1-20, ...), going back to frame 1 at the end. "Loop: N" next to Play chooses how many frames it covers: the first N of a model run, or the newest N radar scans (6 / 12 / 24 / 36 or all); only those frames are downloaded, and a loop video export covers the same frames. The choice is remembered per page.

## GFS page
Upper-air heights and winds (250 to 850 mb) over a color of your choice, and surface fields, from NOAA's GFS on the public bucket `noaa-gfs-bdp-pds`: a run every six
hours, hourly frames to 120 h and then every 3 h to 16 days. Each forecast hour is one very large GRIB2 file with a text index of its messages' byte ranges, so only the few
fields wanted are fetched (about 0.5 to 0.8 MB each). **Cropping:** for a sector the page asks NOMADS's grib filter for just that box (one request per frame, two at a time);
if NOMADS does not answer it falls back to AWS byte ranges, then to a truncated download of the top rows of each message (the core decodes a message's first rows), and whole
fields are the last resort. A Settings switch controls the NOMADS crop and the header line says where the data came from. It has runs, loop, sectors, tap read-out and share like
the others. Its first commit calls the page a sketch.

## Extended page
A saved place's forecast for the next 11 days in one view, from the NBM's text bulletins for the place's nearest station: **NBS** (every 3 hours to 72 h), **NBE** (every 12 hours to 192 h) and **NBX** (to 264 h), with the NWS gridded forecast (`api.weather.gov`) in front of them for the hours before the first NBS column (every 3 hours, temperature, dewpoint, sky, wind and gust only). The page menu's "Extended" opens it; a drop list at the top picks the place from the saved places.

- **Graphs and table:** temperature and dewpoint with a band of one standard deviation (it widens with the lead time: the point of an extended view), the 32 F line, wind speed, gust and direction arrows, a bar for the chance of precipitation (the short-range bulletin's 6-hour chances lighter, the 12-hour ones darker), and rows under them for the values, the day's high and low, sky cover, rain, snow and ice amounts and the thunderstorm chance where the bulletins have them. The columns are of equal width, so the spacing of the times changes where the sources change. Units are the bulletins' own: F, knots, inches, snow level in thousands of feet.
- **Pick a time:** tap a column (a graph or the table). A bar shows its time and one button, **Heights & winds (REFS, else GFS)**. Pressing it asks the REFS runs rather than guessing: the newest run (of the last few) that has the time is opened on the 500 mb heights and winds at that time step (a run still being written that has not reached it is passed over for the run before). If no REFS run has the time, the GFS page opens on the same product at the frame nearest it.
- **How one station is read:** the bulletin files hold all 9,589 stations (NBX 10.6 MB, NBE 20.9 MB), so one station is found with small byte-range reads: a coarse index of 32 windows over the file (the file is several sorted runs one after another, not one), then a bisection where the sampled ids ascend through the station and a read-through where a break lies inside. On the real files every station was found, in about 7 to 10 reads once the file was indexed (`NbmText.kt`).
- **Privacy:** the first time a place is picked, its coordinates are sent to `api.weather.gov` to find the nearest bulletin station (the first of the nearest observing stations that the blend carries) and the grid link; the answer is kept on the device and the coordinates are not sent again for that place.
- **Not done:** wind in mph, a map thumbnail of the synoptic set up on the page itself, precipitation type, sectors other than CONUS for the hand-off.

## Wind barbs
The REFS page's **Heights & Winds** product has a level (500 / 700 / 850 mb) and a color (wind speed, 2 m temperature, level temperature, 2 m dewpoint, 2 m relative humidity)
and draws the ensemble-mean wind as barbs with the level's height as contours. A **stack** option draws the 500 mb height with the 850, 700 and 500 mb barbs in three colors.
The GFS page has the same two. Barb density has levels from 12 to 56 barbs across the picture (the default is 42, about what SPC's mesoanalysis charts draw across the country),
and sectors keep the same count. Barbs are shorter and thinner than the first version, closer to SPC's charts.

## Projection
The REFS page draws the model's own Lambert conformal grid. The MRMS files are plain latitude / longitude grids, which look stretched when drawn as they are, so the MRMS page **reprojects each decoded window onto a Lambert conformal grid** (REFS's projection: parallel 38.5, central meridian 97.5 W) in the core (`spc_plan_lambert_box`, `spc_reproject`, nearest cell). A sector becomes the bounding rectangle of its box in that projection, filled to the corners. Overlay products and the share / export crops are put onto the same grid (`spc_plan_lambert_crop` for a zoomed crop), so map lines, alert outlines, contours, places and the tap read-out all use one projection. Cells are a little smaller than the file's, so no cell is skipped. The MRMS globe still uses the file's own grid.

## Overlay
On the MRMS page, "Overlay" draws other products over the first. Choose **no overlay**, the **lightning nest**, or **one product** of your choice (any MRMS product: strikes, any of the 14 rotation products, hail size, ...). A chosen product has a **minimum value** ("show only rotation of at least 10") picked from its own band edges. The overlays are cut from the same area and matched to each radar frame by the nearest valid time (strike files come every minute, radar every 2); loops prefetch them too, exports include them, and the tap read-out shows every layer's value.

The **lightning nest** stacks strike density by recency, drawn in order so the freshest is on top: the last 30 minutes as a 20 % shadow, the last 5 minutes at 50 %, the last minute solid. Each strike is a small cross (one strike is a single 1 km cell, too small to see) and the older windows get larger dots, so the layers read as nested rings: a solid core, a half-strength ring, a faint halo, each with a thin dark rim. Overlay files are small: strike density 1-2 KB, rotation tracks about 100-160 KB (30 min to 1 h), against about 1.4 MB for a radar frame. Overlays are not drawn on the globe.

**Halo.** Hail swaths and rotation tracks (as the base product or as an overlay) are edged in black with a white halo outside, as wxqt draws its swaths, so they read over any reflectivity color. **LightningCast.** The chance of lightning in the next hour comes from NOAA / CIMSS LightningCast (the GOES-East placefile wxqt also reads), not from MRMS grids: a "LightningCast" row on the MRMS page switches it on once you paste the placefile link from CIMSS's page (the app carries no link of its own and requests nothing until you do) (it is a now-and-next-hour product, so the REFS and NBM model pages do not have it), drawn as contour lines at 10 / 30 / 50 / 70 / 90 % for the newest scan (on the MRMS page, the scan nearest the radar frame shown); it replaces the two MRMS lightning-probability products.

**ProbSevere.** NOAA / CIMSS ProbSevere storm outlines (the MRMS storm objects with their chances of severe weather) are an overlay beside LightningCast, read from a placefile link you paste from CIMSS's ProbSevere page. They are drawn only on the radar frames inside each set's valid times, so they move as a loop plays.

## Alerts
On the MRMS page, "Alerts" outlines NWS products over the radar. Three layers, all **off by default**: **Warnings** (tornado, severe thunderstorm, flash flood, extreme wind, special marine, snow squall; storm-based polygons from NOAA's public watch/warning map service), **Watches** (tornado, severe thunderstorm, flash flood) and **Discussions** (SPC mesoscale discussions and WPC precipitation discussions, dashed, read from their public pages). Outlines use the NWS colors with a dark casing, and are drawn on the **newest frame only** with an "as of" time; they refresh about once a minute while the page is open. Tap inside an outline for a card with the type and times, and **Read** for the full text. Alerts are not tied to your location, and this is an unofficial copy that can be late or incomplete: the dialog says so, and weather.gov is the official source. Not drawn on the globe.

## Contour lines
On the SPC REFS page, heights (500 / 700 / 850 mb), mean sea-level pressure and temperatures (850 / 700 / 500 mb, 2 m temperature and dewpoint) also get contour lines over the colors: 6 dam for 500 mb heights, 3 dam for 700 / 850 mb, 4 hPa for pressure, 5 C or 10 F for temperatures; every fifth height / pressure line and the freezing line are heavier, and the longest line of each level carries a label (heights in decameters). Lines come from marching squares on the field itself (`shared/.../Contour.kt`, tested), so they follow the data in every sector and in shared pictures. Settings has a "Contour lines" switch (on by default). The MRMS page draws them for the model fields (freezing level every 2 kft or 0.5 km, surface and wet-bulb temperature). Not yet on the globe.

## Docs
| File | What |
|---|---|
| `docs/outline.md` | scope, features, risks and open decisions (condensed from `android-spc-refs-scope.md`) |
| `docs/mrms-products.md`, `docs/mrms-site-outline.md` | the MRMS products surveyed from the bucket; how NSSL's own viewer works |
| `docs/nbm-products.md` | the NBM page: products, runs, the GRIB fallback |
| `docs/spc-colormaps.md`, `docs/spc-outlooks.md` | SPC's color tables; the outlook overlay and the comparison with wxqt's MRMS overlays |
| `docs/android-auto.md` | the car app |
| `docs/extended-point-view.md` | the Extended page, written up so it can be re-implemented (for wxqt-grib): data formats, the one-station fetch, the merge, the hand-off |
| `docs/loop-video-size.md` | why a loop video is several MB and what shrinks it |
| `docs/separating-spc.md`, `docs/grib-companion-scope.md`, `docs/nomads-spc-post.md` | suggestions and research, **not built** |

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
