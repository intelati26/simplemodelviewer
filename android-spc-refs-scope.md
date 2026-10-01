# SPC REFS for Android and iOS - project scope (draft, 2026-10-01)

A separate, minimalist Android and iOS app (developed and built entirely on GitHub) that shows SPC's REFS ensemble products. It would not live in the wxqt repo; this note is
the scoping, written from what building the desktop viewer showed.

## 1. What it is

One purpose: pick an SPC REFS product, see it on a phone-sized map, step or loop through the forecast, tap for the value,
share the picture. No account, no server of ours, no ads, no other weather features.

### Must have (v1)
1. **Product picker** - SPC's own product titles in groups (Severe storms, Instability and shear, Precipitation, ...), with
   favourites and the last choice remembered. About 40 curated products, the rest under "Other".
2. **Cycle and time** - newest cycle by default, older cycles available; a time slider and Play (loop).
3. **Map** - the 3 km CONUS grid, pinch-zoom and pan, state / county lines, colour key. A choice of the standard mesoscale
   sectors (snap-zoom, 4x4 smoothed pixels per 3 km cell, as on the desktop).
4. **Tap for a value** - crosshair on the cell, popup with coordinates and value (members exceeding, for paintball products).
5. **Share / save** - the picture (or the loop as animated WebP / MP4) with the product / run / valid-time border.
6. **Member picker** for single-member fields; dark and light themes.

### Nice to have (v1.x)
- Saved places and a "point forecast" strip (value at your point for every hour of the chosen product).
- Alerts: a notification when a chosen probability (e.g. 4-h UH or tornado/hail probability) near a saved place passes a threshold
  at a new cycle (needs background work and a battery-friendly design; no push server, so it is poll-on-new-cycle only).
- Home-screen widget with the latest picture for a favourite product.
- GREFS (4-h / 24-h tornado, wind, hail probability) and REFSCT thunder probability - separate stores, not yet read on the desktop.
- Tablet layout.

### Not in scope
Radar, observations, forecasts, other models, accounts / sync, a backend of any kind.

## 2. What the data is (verified)
- Open Zarr v2 stores, one per cycle: `https://www.spc.noaa.gov/exper/refs/data/<yyyy/MM/dd>/refs-spc_<yyyymmdd_HHMM>.zarr`.
  Consolidated metadata at `/.zmetadata` (130-170 KB JSON, lists every array, its shape, dtype and attributes).
- Cycles 00 / 06 / 12 / 18 UTC. The folder cannot be listed, so the newest cycles are found by probing for `/.zgroup`.
  The 00 and 12 UTC cycles also carry the 24-h and 48-h products.
- 136 arrays per cycle, all on one grid: Lambert conformal, 1799 x 1059 cells, 3 km, standard parallels 38.5, central meridian
  262.5 E, sphere radius 6371229 m (reproduces SPC's own latitude / longitude arrays to 1e-10 degrees).
- One file ("chunk") per forecast time and per member, named like `cref_max/3.0.0` or `cref/<member>.<time>.0.0`:
  - 0.4 to 1 MB each, blosc-compressed (zstd for most fields, lz4 for coordinates and some others), byte shuffle.
  - dtypes half / single float, int32 bit masks ("paintball": bit i = member i over the threshold), uint8.
- Each frame is therefore one HTTP request of about 0.5 MB; a 24-frame loop is about 12-20 MB of data.
- Colour tables and product titles are in SPC's viewer JavaScript; the desktop version uses SPC's published tables for
  reflectivity and QPF and its own for the rest.

## 3. Architecture (recommended)
**Kotlin + Jetpack Compose UI, with a small C++ core through the NDK (JNI).**
- The C++ core is a straight lift from the desktop viewer: `Blosc` (blosc frame + lz4 + the vendored zstd decoder),
  `ZarrStore` (metadata, chunk index, dtype conversion), `LambertGrid`, and the rasteriser (value -> colour, bilinear smoothing for
  sectors, paintball compositing). About 1,500 lines, already verified byte-identical to libblosc on real chunks.
- Kotlin side: networking (OkHttp, HTTP cache and ETags), product catalogue, UI state, share / export, settings (DataStore).
- Rendering: the core returns an `ARGB_8888` Bitmap (or a 1-byte-per-cell index plus palette, which keeps a 24-frame loop to ~45 MB
  of RAM instead of ~180 MB); a zoomable image view draws it. Map lines are vectors drawn at render time from bundled geometry.
- No server: the phone talks to spc.noaa.gov directly.

Alternatives considered:
| Option | For | Against |
|---|---|---|
| Qt for Android, reuse the whole viewer | Fastest; one codebase | 30-50 MB APK, desktop-style widgets are not minimalist on a phone, Qt licensing / packaging overhead |
| Pure Kotlin (port the decoder to Kotlin / use zstd-jni + lz4-java) | No NDK; smaller toolchain | Rewrite and re-verify; slower rasterising |
| Wrap SPC's web viewer (WebView / PWA) | Almost no work | Heavy (WebGL, 2 MB script), awkward on phones, ties us to SPC's page; not "our" app |
| Flutter / KMP | Could target iOS later | Native-code bridge anyway; more moving parts |

## 4. Size and performance targets
- APK under about 8 MB (zstd decoder ~100 KB of code, line geometry ~2-3 MB, no maps SDK).
- Decode + render one CONUS frame under 150 ms on a mid-range phone (1.9 M cells; the desktop does it in a few tens of ms).
- A frame appears within 2 s on LTE (one 0.5 MB request). A loop prefetches in the background with progress, optionally Wi-Fi only.
- Cache: decoded-chunk disk cache (LRU, ~100-200 MB cap, user adjustable); current cycle's metadata kept until a newer cycle appears.

## 5. Risks and decisions
1. **Unofficial data source.** The Zarr files are what SPC's *experimental* viewer reads; the layout is not documented as an API and could
   change. Mitigation: read the metadata (not hard-coded names) wherever possible, show a clear message if a cycle has an unknown layout,
   keep a minimum-supported-schema check. **Recommend writing to SPC (the viewer lists tim.supinie@noaa.gov and israel.jirak@noaa.gov)
   before release**: say what the app does, ask whether they are comfortable with direct reads, and ask about preferred rate limits.
2. **Load on SPC's server.** Be a good client: HTTP caching, no polling faster than new cycles, a descriptive User-Agent with a contact
   address, one request per frame, a politeness limit on parallel downloads.
3. **Licence.** wxqt is GPLv3, so a build that reuses its code must also be GPLv3 (fine for F-Droid / GitHub releases; Google Play allows
   GPL apps but needs the source offer and attention to the store terms). The alternative is a clean re-implementation (the algorithms are
   public and small) under a permissive licence. **Decision needed.** The vendored zstd is BSD / GPLv2 dual-licensed - fine either way.
4. **Branding.** Name and icon must not imply NOAA / NWS / SPC endorsement or use their marks; credit "Data: NOAA/NWS Storm Prediction Center"
   in the app and store listing. Not a warning or decision-support tool: show a disclaimer and "experimental guidance" on the product pages.
5. **Mobile data cost.** A loop is ~15 MB. Offer a data-saver (half-resolution frames: decimate the grid in the core, ~4x less memory; the
   download size is unchanged because chunks are whole frames) and Wi-Fi-only prefetch.
6. **Play Store.** Weather apps get extra scrutiny (accuracy / disclaimer language, no emergency-alert claims). Target API level and 64-bit
   requirements are routine.

## 6. Work breakdown and rough effort
Effort is for one experienced developer and assumes the C++ core is reused.

| Milestone | Content | Effort |
|---|---|---|
| M0 spike | Repo + CI for core / Android / iOS; fetch + decode + draw one product on a phone and in the iOS Simulator (screenshot in CI); confirm speed and memory | 1 week |
| M1 MVP | Product list, cycle / time, map with lines and key, tap popup, pinch-zoom, settings, caching | 2-3 weeks |
| M2 | Loop with prefetch, sectors, share / export (image, animated WebP / MP4), favourites, themes, tablet-friendly layout | 2 weeks |
| M3 release | Disclaimers and credits, crash reporting (opt-in), accessibility, store listing, beta test | 1-2 weeks |
| M4 (optional) | Saved places + point strip, widget, threshold notifications, GREFS / thunder probability | 3-4 weeks |

Total to a first public release: roughly 6-8 weeks; with the optional features 10-12.

## 7. Living on GitHub: repo, Actions, and iOS you cannot test
Premise: the project is developed and shipped entirely from GitHub (no local build machine needed), for Android and iOS, with one
person able to test Android only.

### Consequences for the design
- **Share as much as possible, so what is tested on Android covers iOS.** Two shared layers:
  1. the C++ core (decoder, projection, rasteriser) - identical on both platforms, unit-tested on Linux in CI;
  2. the UI: **Kotlin Multiplatform + Compose Multiplatform** (one UI codebase for Android and iOS). A UI bug found on the phone is
     then the same bug on iOS. The alternative (SwiftUI written separately) doubles the UI work and leaves a UI nobody can test.
  Fallback if Compose Multiplatform on iOS proves rough in the spike: keep the shared Kotlin logic and write a thin SwiftUI shell.
  (Assumption to verify in M0: Compose Multiplatform's iOS target is stable enough for a one-screen app.)
- The core is plain C++ with a C interface, built by CMake for Android (NDK) and compiled into an iOS static library; Kotlin reaches it
  through JNI on Android and cinterop on iOS. Nothing in it touches a platform API (networking stays on the Kotlin side).
- No Mac needed: iOS builds run on GitHub's macOS runners.

### Repository layout
```
core/            C++ core + tests (CMake; also built by CI on Linux)
shared/          Kotlin Multiplatform: catalogue, state, networking (Ktor), export, Compose UI
androidApp/      Android entry point and resources
iosApp/          Xcode project (thin SwiftUI host for the shared Compose UI)
fixtures/        real SPC chunks + expected decoded values / golden images
.github/workflows/
docs/
```

### Actions workflows
| Workflow | Runner | Does | Output |
|---|---|---|---|
| `core-tests` | ubuntu | build the core, decode the fixtures, compare with golden values and golden PNGs | pass / fail |
| `android` | ubuntu | Gradle assemble (debug on every push, signed release on tags), lint, unit tests | APK + AAB as artifacts; a GitHub Release with the APK on a tag |
| `android-ui` | ubuntu + KVM emulator | install the debug APK on an emulator, run UI tests, take screenshots | screenshot artifacts (so a UI change can be eyeballed without a device) |
| `ios` | macos (arm64) | build the app for the simulator, run the shared tests, run the same UI tests on a simulator, take screenshots | screenshot artifacts; an unsigned `.app` |
| `release` (on tag) | both | Android: sign + upload to GitHub Releases (optional Play upload). iOS: unsigned `.app` / `.ipa` only; the signing / TestFlight job is secret-gated and off | release assets |
Nightly / per-commit **debug APKs as release assets** let the Android tester update with a release-watching installer (Obtainium or
similar) - no store needed during development. Secrets (signing keystore, Play service account, Apple key) live in GitHub encrypted
secrets and are only available to tag builds.

### iOS: best effort, no Apple membership (decided)
**Decision: no Apple Developer Program membership.** Consequences, accepted:
- iOS is **best effort / untested on a real device**, and says so in the README, release notes and the app's About screen.
- No App Store, no TestFlight, no signed IPA. CI produces an **unsigned** simulator build and an unsigned device `.app` / `.ipa`
  as release assets; anyone with a Mac can build from source, and others can self-sign and sideload (AltStore, Sideloadly, or Xcode
  with a free Apple ID - the free-account signing expires after 7 days and is limited to a few apps).
- The iOS promise is therefore: "the code builds, the shared tests pass, it launches in the iOS Simulator, and the screenshots
  look right", not "it works on your iPhone". Real-device feedback from users is welcome and tracked in issues.
- **What CI can prove:** it compiles, the shared logic passes, the app launches in the Simulator, the UI tests pass, and the
  screenshots look right. It does not prove touch feel, performance on a real phone, or App Store acceptability.
- Keep iOS-only code to a minimum and list it in `docs/ios-notes.md` so a tester knows where to look.
- Revisit only if the project gains a sponsor or contributor who has the membership; the release workflow already has an optional,
  secret-gated signing / TestFlight job that stays off until the secrets exist.

### Cost
- If the repository is **public**, GitHub-hosted runners (including macOS) are free for it; private repositories pay a multiplier on macOS
  minutes. So: public repo (fits the GPL option and the "literally living on GitHub" idea).
- Costs outside CI: none required (no Apple membership). Optional: Google Play registration (one-time USD 25).

## 8. Open questions for the owner
1. Name, and free / donation / paid? (No ads and no tracking is assumed.)
2. Licence: GPLv3 (reuse the desktop code) or clean re-implementation under a permissive licence?
3. Distribution: F-Droid / GitHub APK only, or Google Play too?
4. Is the alert / notification feature wanted in v1? It is the biggest differentiator and the biggest source of complexity and liability.
5. ~~iOS / Apple membership~~ - decided: no membership, iOS best effort (unsigned builds, Simulator-tested in CI).
6. Which products matter most for the first screen? (Suggested default: 4-h max 2-5 km updraft helicity, composite reflectivity ensemble
   max, and the CAPE / shear / STP means.)

## 9. First concrete steps
1. Ask SPC (section 5.1) while the spike is built.
2. Create the public GitHub repo and the `core-tests`, `android` and `ios` workflows first (a "hello" app that decodes one chunk), so
   both platforms build and screenshot in CI before any real UI exists. This de-risks Compose Multiplatform on iOS in days.
3. Extract the C++ core (`zarr/`, `spcrefs/`) as a standalone library with its own unit tests: the real chunks used
   to verify the decoder (`cref_max`, `qpf_01h_acc_mean`, `uh25_04h_max_overlap_max`, a member field, a coordinate array) become fixtures.
4. M0 spike on a physical phone; record decode time, memory and a screenshot per product kind (blended, banded, paintball, probability).
5. Decide licence and name, then M1. (Apple membership: decided against; iOS is best effort.)
