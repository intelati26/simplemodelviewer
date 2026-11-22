# MRMS rain archive for the desktop program

Status: proposal. Nothing here is built. It is the Android day cache (`RainDay.kt`, quadtree at
0.5 mm or 5 %) with the 90-day limit removed.

## Goal

Keep every finished UTC day of multi-sensor rainfall, with no retention limit, so any rainfall total
(7, 14, 30, 90, 365 days, or a custom span) is a local read after the first download.

"100%" here means every day is kept (100 % day coverage, no expiry), stored in the same compact
adaptive-quadtree form the Android app already uses: 0.1 mm quantization, a block collapses to one value
when its spread is within 0.5 mm or 5 % of its mean, fine detail kept where rain varies. The raw NOAA file
is not kept.

## What gets stored

One `.rd` file per UTC day, built from `MultiSensor_QPE_24H_Pass2_00.00` at `YYYYMMDD-000000` (the 24 h
total ending 00:00 UTC). Same builder as Android: `RainDayFile.build`, strips of 880 rows, deflated.

- About 1.5 to 1.9 MB per day measured on 13 real days: roughly **0.6 GB per year**, about 4 GB for
  everything since late 2020 (about 6 years), about 6 GB per decade.
- No cap by default. Android's 400 MB safety cap becomes an optional user limit.
- Partial-day edges (today and hours before the first full day) stay in the HTTP cache, as now.

## Layout on disk

```
<archive root>/rain-days/day-<endEpoch>.rd
```

- Same file names and format as `RainDayStore`, so Android and desktop days are interchangeable.
- No index needed: the directory listing is the index (count, oldest, newest, gaps from file names).
- Writes are `.tmp` then atomic rename (already how `RainDayStore.write` works); stray `.tmp` removed at start.
- Default root: the OS user-data directory (`~/.local/share/<app>/`, `%APPDATA%`,
  `~/Library/Application Support`), overridable in Settings to point at a big drive.
- The file header carries a format version (magic `0x52443031`). A future tolerance or format change bumps
  it and the old days are rebuilt lazily when next needed.

## Change from Android

`RainDayStore` already does most of this. Required changes:

1. `keepDays` becomes nullable (default `null` = forever); `expire()` no-ops when null.
2. `capBytes` becomes nullable (default `null` = unlimited); `trim()` no-ops when null.
3. Move it to `shared` (or a desktop source set) with a directory path and clock as constructor arguments;
   `DeflatePacker` is `java.util.zip`, so it works on any JVM target.
4. `dayFrame` in `MrmsViewModel` moves to a shared helper so desktop and Android use one code path.

Nothing changes in the planner, `RainDayFile`, or `loadFrameFromDay`.

## Read and write path

1. `RainSince.plan` gives pieces; for each `isDay` piece, `store.read(dayEnd)`.
2. Hit: `RainDayFile.window` at the requested box and step, then place and add as `loadFrameFromDay` does.
3. Miss: download the midnight Pass 2 file, `RainDayFile.build`, `store.write`, then use it.
4. Any failure falls back to the normal per-directory loop (current behavior), and a day NOAA does not have
   is remembered for 24 h (in memory) instead of retried each frame.
5. A day is built only when complete: the stamp is `-000000` of a day strictly before today UTC.

## Backfill

- Lazy by default (first total that needs a day builds it).
- Optional "Archive in the background": on launch and daily while open, build yesterday and walk back
  filling gaps. 2 parallel connections, pausable, resumable.
- "Backfill from…": pick a start date (default earliest available, about Oct 2020), shows "days stored /
  days wanted" and estimated size (about 1.7 MB per day).
- Cold cost per day: download 2.9 MB plus build (decode 4 strips and encode); the 13-day Android run took
  about 63 s, so about 5 s per day, about 30 min per year of backfill.

## Storage page

- Rainfall archive row: size, day count, oldest and newest day, gap count, folder, Open folder.
- Buttons: Backfill…, Clear (confirm; says it can be re-downloaded from NOAA).
- Retention: "Keep forever" (default), optional "Keep last N days" and "Stop if free disk under X GB".
- Rainfall dialog notes when a total used fewer days than asked.

## Accuracy and the tolerance

Totals read from the archive are within 0.5 mm or 5 % per block (whichever is larger), and 0.1 mm
quantization. Sums over many days carry the per-day errors, so a 365-day total can differ from the raw-file
sum by a few mm in busy areas; this is the trade for about half the size. If the 1 mm / 10 % setting is
wanted later (about half again), it is only the constants in `RainDayCodec`, plus a version bump.

## Implementation steps

1. Make `RainDayStore` retention optional; move to shared/desktop; keep tests for expiry on and off.
2. Extract the Android `dayFrame` logic into a shared helper.
3. Desktop storage root setting and Storage page row.
4. Background top-up and Backfill worker.
5. Tests: unlimited store never expires or trims, gap listing from file names, interrupted write recovery,
   a long span total matches the sum of day reads.

## Open questions

- Desktop target: the wX fork (`~/Claude/wx`) or a new JVM/Compose Desktop app? The archive code is plain
  Kotlin either way.
- Keep the tolerance at 0.5 mm / 5 %, or loosen to 1 mm / 10 % for a six-year archive?
