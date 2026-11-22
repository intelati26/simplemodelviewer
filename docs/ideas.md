# Ideas and open items

Written 2026-11-22. Things talked about and not built yet, newest thinking first. "Built" items are in the git log, not here.

## Compare page (radar against REFS members) — next steps

Built: reflectivity, side by side / overlay / difference, members ranked by neighborhood score over f01 to f04 (`docs/mrms-refs-compare-mobile.md` has the design).

- **Score what is on screen.** Rescore the members for the zoomed-in area instead of the whole domain; the ranking can differ a lot between the Plains and the Southeast. `CompareScore.score` already takes a region.
- **Long-press probe** reading the radar, the shown member and the difference at one point.
- **Hit / miss view:** both over 35 dBZ, model only, radar only.
- **Member strip:** small thumbnails of every member's difference, tap to open.
- **Tap a ranking row to see its best hour;** per-hour scores already exist.
- **Warn in the page** that matching radar at the start does not mean a better forecast later; show how the same member did in the previous two runs.
- **Newest run first:** when the newest run's time axis has gaps, use its early hours anyway (read the time chunks one by one) instead of falling back to the previous run.
- **Rain next:** radar-only or Pass 1 one-hour rain against REFS one-hour QPF, then totals within the run. Pass 2 lags about an hour, so it does not pair with "now".
- **Entry points:** a button on the MRMS and REFS pages that opens Compare at the same sector and time.
- **Landscape and tablets:** side by side with the controls in a column.

## Desktop program

- **Rain archive:** every finished UTC day kept forever, in the Android quadtree form (0.1 mm values, blocks collapse within 0.5 mm or 5 %), about 1.5 to 1.9 MB a day, about 0.6 GB a year. `RainDayStore` already does it; retention and the size cap become optional. Plan: `docs/mrms-rain-archive-desktop.md`.
- **Which program:** the wX fork (`~/Claude/wx`) or a new JVM / Compose Desktop app is not decided.
- **Backfill** from late 2020 as a background, resumable, rate-limited job; about half an hour per year of data.
- **Looser tolerance** (1 mm / 10 %) would about halve the archive again; measure it on a real full day before choosing.

## Rainfall totals

- Hide the radar header and the loop row while a rainfall total is shown (they still show "frame 30/30 · live").
- Favorites for the 3, 7, 14 and 30 day totals in the product list.
- A day older than 90 days is stored and expired in the same run, so it downloads every time; skip storing those.
- Verify a 30 day total (time, size) and the 90 day expiry with real old days.

## Globe and map

- On a return to the map from the globe, trigger the finer zoom-in picture even when most of CONUS is in view (today it waits until the view is under about half the picture).
- Test the "Map of this view" path on the REFS, NBM, GFS and Analysis pages (only MRMS was run on the emulator).

## Releases and housekeeping

- Cut 0.4.1 so the APK is under Releases (versionName is still 0.4.0 and the release workflow checks it).
- Rerun iOS for v0.3.2 (earlier note).
- Sync the wX fork with the REFS / MRMS port (branch `refs-mrms`).
- NOMADS `spc_post` feeds (GRIB2 / JPEG2000 SPC products): the owner is revisiting this in about two weeks; do not build yet (`docs/nomads-spc-post.md`).
- Step arrows on the MRMS, NBM and GFS pages were only compiled, never run.
