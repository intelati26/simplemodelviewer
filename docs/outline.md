# SPC REFS Mobile - outline

Condensed from `android-spc-refs-scope.md` (draft 2026-10-01). Open items are marked **[decide]**.

## 1. Purpose
One job: pick an SPC REFS product, see it on a phone map, step or loop the forecast, tap for a value, share the picture.
No account, server, ads or other weather features.

## 2. Features
- **v1 (must):** product picker (groups, favourites, last choice, ~40 curated + "Other"); cycle and time (newest default,
  slider, Play); map (3 km CONUS, pinch-zoom/pan, state/county lines, colour key, mesoscale sectors); tap-for-value
  (paintball: members exceeding); share/save (image, or loop as WebP/MP4, with product/run/valid border); member picker;
  dark/light.
- **v1.x (nice):** saved places + point strip; threshold alerts (poll-on-new-cycle); widget; GREFS and REFSCT stores; tablet layout.
- **Out:** radar, observations, other models, accounts/sync, any backend.

## 3. Data (verified)
- Zarr v2 store per cycle: `.../exper/refs/data/<yyyy/MM/dd>/refs-spc_<yyyymmdd_HHMM>.zarr`; `.zmetadata` lists 136 arrays.
- Cycles 00/06/12/18Z (00/12 carry 24-h and 48-h products); found by probing `/.zgroup`, newest first.
- One grid: Lambert conformal 1799x1059, 3 km. One chunk per time (and member), 0.4-1 MB, blosc (zstd/lz4) + byte shuffle.
- A frame is one ~0.5 MB request; a 24-frame loop is ~12-20 MB.

## 4. Architecture
- C++ core with C API (`core/`): blosc decode, dtype conversion, Lambert grid, rasteriser. Identical on both platforms.
- Kotlin Multiplatform + Compose Multiplatform UI (`shared/`), Ktor/OkHttp networking, DataStore settings.
- Android via JNI/NDK; iOS via cinterop (static lib). Fallback if Compose on iOS is rough: SwiftUI shell over shared Kotlin logic.
- No server; phone talks to spc.noaa.gov directly.

## 5. Targets
APK < ~8 MB; frame decode+render < 150 ms; first frame < 2 s on LTE; loop prefetch with progress and Wi-Fi-only option;
LRU disk cache 100-200 MB; data-saver (half-res) mode.

## 6. Risks and decisions
1. Unofficial data source -> read metadata not hard-coded names, schema check, **write to SPC before release**.
2. Server load -> HTTP cache/ETags, no polling faster than new cycles, User-Agent with contact, limited parallelism.
3. **[decide] Licence:** GPLv3 (reuse wxqt code, as this first pass does) vs clean re-implementation under a permissive licence.
4. Branding: no NOAA/NWS/SPC marks or implied endorsement; credit data; "experimental guidance" disclaimer.
5. Mobile data cost -> data-saver, Wi-Fi-only prefetch.
6. Play Store scrutiny for weather apps (disclaimers, no alert claims).
7. iOS: **decided** - no Apple membership; best effort; unsigned builds; CI proves build + simulator launch + screenshots only.

## 7. Milestones
| | Content | Effort |
|---|---|---|
| M0 | repo + CI; fetch/decode/draw one product on phone and iOS Simulator | 1 wk |
| M1 | product list, cycle/time, map + lines + key, tap popup, zoom, settings, caching | 2-3 wk |
| M2 | loop + prefetch, sectors, share/export, favourites, themes, tablet | 2 wk |
| M3 | disclaimers/credits, opt-in crash reports, accessibility, listing, beta | 1-2 wk |
| M4 (opt) | places + point strip, widget, alerts, GREFS/thunder | 3-4 wk |

## 8. Open questions
Name and pricing; licence; distribution (F-Droid/GitHub only vs Play); alerts in v1?; first-screen products
(default: 4-h max UH, composite reflectivity ensemble max, CAPE/shear/STP means).

## 9. First pass status (this repo)
| Done | Not done |
|---|---|
| Repo layout, GPLv3 notice, README | Android / iOS app modules and Compose UI |
| `core/`: blosc decode (lz4+zstd) ported from wxqt, dtype conversion, Lambert grid, rasteriser (blended / banded / paintball), C API | JNI / cinterop bindings, map line geometry |
| Core unit tests incl. byte-identical decode vs libblosc - pass on Linux | Real-chunk fixtures and golden images |
| `shared/` and `androidApp/`: app builds and lints in CI; shared tests pass; run on an emulator against live data. Done beyond v1: GREFS 4-h / 24-h tornado, wind and hail probabilities and REFSCT thunder probability (own 40.6 km grid = NCEP 212; see lambert_grid.h), globe, export (screen area / meso sector / globe, PNG or MP4 loop), widget, saved places, new-run banner, settings, tablet layout | iOS app, golden images, accessibility pass |
| CI: `core-tests` workflow; `android` skeleton | `android-ui`, `ios`, `release` workflows |

Next: (1) fetch real chunks as fixtures and add golden tests, (2) Gradle Android + iOS targets and the JNI/cinterop hello app, (3) port SPC titles
and colour tables from wxqt `UtilitySpcRefs.cpp`, (4) M0 spike on a phone.

## 10. Notes
- **No easy MRMS (checked 2026-10-01).** NSSL's QVS product viewer (mrms.nssl.noaa.gov/qvs/product_viewer/) does not serve data
  grids: a server-side C++ renderer returns finished map pictures per request (product, time, centre, zoom, pixel size). It is
  a rendering service for their page, not an API, and gives no values to tap. Real MRMS values are `.grib2.gz` on NCEP's server
  and the `noaa-mrms-pds` AWS bucket (needs a gunzip + GRIB2 decoder; radar is out of scope for v1). Not pursued.
- **Decision (owner): no location-based alerts or point forecasts.** A saved-place point-forecast strip and threshold notifications were built and
  verified on the emulator, then removed; saved places remain as map bookmarks. Products (including GREFS / thunder probability later) are not affected.
- **Decision (owner): GitHub only, for now.** Distribution is the GitHub repository plus APKs from GitHub Releases / Actions (CI artifacts are named
  `simplemodelview-*`); no Google Play or F-Droid listing, so the Play-specific risks above do not apply yet. The contact address sent to the data
  provider is user-set in Settings (stored on the device, empty by default), not hard-coded.
- **Contact email (owner):** Settings has an optional email field; it is appended to the User-Agent (`simplemodelview/0.1 (...) contact: <email>`) so
  the data provider can reach the user. It is optional but a dialog asks for it at every launch (and after 30 min away) until one is saved; "Not now"
  closes it and the app works without. The dialog states that the address is sent to SPC's server. The address is stored only on the device.
- **Name (owner): simplemodelview.** The app, application ID (`io.github.intelati26.simplemodelview`), packages, JNI library, workflows and artifacts use it.
  Name check (2026-10-01): no app by this name was found; the exact name `SimpleModelView` is a small Unity UI package (EpistasisLab/SimpleModelView), a code
  library in an unrelated domain, and "model viewer" mostly returns 3D-model viewers, so the name is not self-explanatory for weather: say "SPC REFS" in
  the description. The SPC name is not used in the app's name or icon (no implied NOAA / NWS / SPC endorsement).
- **Lightning (searched 2026-10-02):** the REFS data contain no lightning-flash product. The viewer's script and the 175 arrays of the REFS store have no flash / lightning
  fields; the nearest thing is REFSCT **thunder probability** (stores `refsct-1hr`, `refsct-4hr`, `refsct-full`), which is now in the app under Guidance.
- **GREFS grid correction:** the GREFS and REFSCT stores share NCEP grid 212 (Lambert, parallel 25, central meridian 265 E, 40.635 km). The GREFS store's own latitude /
  longitude arrays are inconsistent with its data (correlation with storm reflectivity 0.08 vs 0.37 on the grid-212 mapping), so the app uses the grid-212 parameters and
  ignores that store's coordinate arrays. GREFS values are in percent already (not fractions).
