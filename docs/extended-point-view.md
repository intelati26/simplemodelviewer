# Extended point view: a saved place's 11-day forecast from the NBM text bulletins

A summary of the "Extended" page of simplemodelview, written so it can be re-implemented in **wxqt-grib** (Qt / C++). The reference implementation is
in this repository (Kotlin; paths below), written and checked on 2026-11-12 against the live bulletin files. Everything marked *verified* was checked on
real data; *not verified* is stated as such.

## 1. What it is

For one saved place: graphs and a table of the forecast for the next 11 days, hours 0 to 264, from NOAA's National Blend of Models (NBM) text bulletins for
the place's nearest station. The graphs show how uncertain each value is (a band that widens with the lead time). One time column can be picked, and a
button then opens the **synoptic set up** (500 mb heights and winds) for that valid time: on a REFS run if one has the time, otherwise on GFS at the nearest frame.

| Hours after the NBM run | Source | Spacing | Elements |
|---|---|---|---|
| 0 to the first NBS column (6 h) | NWS gridded forecast (`api.weather.gov` gridpoints) | 3 h (sampled) | temperature, dewpoint, sky, wind direction / speed / gust |
| 6 to 72 | NBS bulletin | 3 h | everything below |
| 84 to 192 | NBE bulletin | 12 h | everything below |
| 204 to 264 | NBX bulletin | 12 h | everything below |

wxqt-grib already has the pieces around this (found by reading the source, not run): `misc/ForecastPointViewer.{h,cpp}` (a weekly table and an hourly chart of a
point), `misc/UtilityForecastPoint.{h,cpp}` (already fetches `api.weather.gov/points` and `/gridpoints`, so the gap source exists), `models/RefsPointGraph`,
`ModelViewer::openAt(model, validUtc, runStart)` / `selectModel("SPCREFS")` with `HistoryCursor`, and `URL::getBytesRangeManaged(url, start, end)` for range reads.
This feature is the long-range half of the forecast-point page plus the hand-off to the model viewer.

## 2. The data (all verified 2026-11-12)

**Where.** The bucket the NBM GRIB pages already use: `https://noaa-nbm-grib2-pds.s3.amazonaws.com/blend.<yyyymmdd>/<HH>/text/blend_<kind>.t<HH>z`, with kind
`nbstx` (short range), `nbetx` (extended), `nbxtx` (super-extended); also `nbhtx` (hourly) and `nbptx` (probabilistic), not used. The same files are on NOMADS under
`pub/data/nccf/com/blend/prod/` (kept 1 to 2 days per the card; plain `curl` requests to that directory and its files got HTTP 403 here, so the AWS copy is the one used). Newest file: ask the bucket's
`?list-type=2&max-keys=1&prefix=blend.<day>/<HH>/text/blend_nbxtx.t<HH>z` for the hour, going back hour by hour (up to 12) until a key matches; the reply has `<Size>`.

**Size.** One file holds **all 9,589 stations**: NBX about 10.6 MB, NBE 20.9 MB, NBS about 29.7 MB, per cycle. Too big to download for one place: see section 4.

**Hours (as the NBM bulletins give them; they vary by cycle).** NBS: FHR 6 to 72 every 3 h (starts at hour 6, there is nothing for hours 0 to 5). NBE: 24 to 192 every 12 h. NBX
(12Z run, the file read): 204 to 264 every 12 h. **The 13Z run's NBX listed columns out to 28 Nov (about 372 h) that had no temperature, sky, wind or chance** (seen as empty columns in the app; I did not
read that file's text, and the card says a 24-hour chance, P24, is shown there at the 13Z cycle): drop columns that have none of those. The NBM v4.3 card (<https://vlab.noaa.gov/web/mdl/nbm-textcard-v4.3>) says the same; v5.0 adds WBG and was not read in full.

**Format.** Plain ASCII; one block per station, a blank line between. Example (KOKC, NBX, 12Z cycle of 2026-11-12):

```
 KOKC    NBM  NBX GUIDANCE    11/12/2026  1200 UTC
      SAT 21| SUN 22| MON 23
 UTC 00  12 |00  12 |00  12
 FHR 204 216|228 240|252 264
 TXN  80  74| ...
 TMP  63  56| 60  46| 52  41
 ...
```

* Header regex: `^\s*(\S+)\s+NBM\s+(NB[HSEXP])\s+GUIDANCE\s+(\d+)/(\d+)/(\d+)\s+(\d\d)(\d\d)\s+UTC` = station id, kind, month, day, year, hour, minute. Valid time of a column = cycle + FHR hours.
* **Read values by column position, not by splitting on whitespace.** On the `FHR` line, take the index of the **last digit** of each number; the value of every element row in that column is
  the **3 characters ending at that index**, trimmed. This handles blanks (e.g. `Q24` has a value only under 24, 48, 72, ...) and the short-range bulletin, whose columns have no separators
  (`SLV 120120120`: three values). `-99` = missing; 999 or more prints as 998.
* NBE has an extra `CLIMO` pair of columns after the last FHR column (on `TXN`, `P12`, `Q24`): ignored.
* Element rows are labelled by a 3-letter code (the first 4 characters of the line, trimmed). Units are the bulletin's own (card v4.3):

| Code | Meaning | Unit | Code | Meaning | Unit |
|---|---|---|---|---|---|
| TMP | temperature | F | WSP | wind speed | knots |
| TSD | std. dev. of TMP | F | WSD | std. dev. of WSP | knots |
| TXN | 18-h max / min | F | GST / GSD | gust / std. dev. | knots |
| XND | std. dev. of TXN | F | P06 (NBS) / P12 | precipitation chance, 6 h / 12 h | % |
| DPT / DSD | dewpoint / std. dev. | F | Q06 (NBS) / Q12 / Q24 | rain amount | 1/100 inch |
| SKY / SSD | sky cover / std. dev. | % | T03 / T06 / T12 | thunderstorm chance (NBS has all three, NBE has T12) | % |
| WDR | wind direction (FROM) | tens of degrees (36 = north, 9 = east) | PZR / PSN / PPL / PRA | share of the precipitation as freezing rain / snow / sleet / rain | % |
| S12 / S24 | snow amount | 1/10 inch | I12 | ice amount | 1/100 inch |
| SLV | snow level (MSL) | hundreds of feet | WBG | wet-bulb globe temperature | F (v5.0) |
| SOL | 12-h mean solar radiation | tens of W/m2 | SWH | significant wave height | feet |

`TXN` is both the maximum and the minimum in one row: the **minimum is reported at 12Z, the maximum at 00Z** of the following day (Guam is the other way round; not handled).

## 3. Merging the sources into one table

Columns are listed by valid time, earliest first, each tagged with its source ("API", "NBS", "NBE", "NBX") and a map code -> value:

1. the gap table (section 6), columns before the first NBS column and not before "now minus 3 h";
2. all NBS columns;
3. NBE columns **after the last NBS column**;
4. NBX columns **after the last NBE column**.

A later source only adds columns after the last column of the earlier ones, so no time appears twice and the change of spacing (3 h, then 12 h) falls where the sources change.
Drop columns whose TMP, SKY, WSP and P12 are all missing. `fromEpoch = now - 3 h` so the page does not open on the past. Reference: `shared/.../PointTable.kt` (`buildPointTable`), tests `PointTableTest.kt`.

## 4. Reading one station out of a 10 to 30 MB file (the hard part)

The stations are sorted, **but the file is several sorted runs one after another** (verified: the main run of 8,353 stations, `086092 ... ZVLT2`, then runs that start at `46001`, `51000`,
`41051`, `APRP7` and `41040`: buoys and other lists appended). A plain bisection on the id missed about 9 % of stations (including `PASA`, `PAOM`). What works (and found **all 9,589
stations of both the real NBX and NBE files**, in about 7 reads (NBX) to 10 reads (NBE) once the file was indexed; worst case 38 to 42 reads including the one-time index):

1. **Index (once per file, kept in memory):** read 32 windows of 4,096 bytes at offsets `n * (size / 32)`, `n = 0..31` (4 at a time); in each window take the first header line (a header is `"\n " + id + spaces + "NBM  NBx GUIDANCE"`), giving a list of (file offset, station id) sorted by offset. Need at least 16 hits.
2. **Where the station can be**, for each interval `[sample i, sample i+1)` (the last one runs to the end of the file):
   * *ascending through it* (`id_i <= wanted <= id_(i+1)`, or the last sample with `id_i <= wanted`): **bisect** between the two offsets: read a 4,096-byte window at the midpoint, take its first header, compare ids, narrow; stop when the bracket is at most 3 windows; then read the bracket plus 2 windows and cut the block out;
   * *a break lies inside* (`id_i > id_(i+1)`, or it is the last interval): **read the interval in 96 KB chunks** (overlapping by 2 windows, 4 at a time) until a chunk holds the block;
   * if the bisection did not find it and the interval is at most 1 MB, read it through as well (a break can hide inside an ascending interval).
3. **Cutting a block out:** from its header line to the *next* header line, so the text read must include the next header. When a read reaches the end of the file append an imaginary header (`"\n ~END~ NBM  <kind> GUIDANCE\n"`) so the last station has an end too.
4. A bug found on the way: the chunk read of a break interval must extend one window **past** the end offset, or a station just before the next run's first header has no end (`CPHW`).

Reference: `shared/.../NbmText.kt` (`NbmTextRepository`), tests `NbmTextTest.kt` (a stand-in file with the real file's shape: several runs, blocks of different lengths, the last station).
Per place and per product this is one listing request, up to 32 index reads, then about 7 to 10 reads. Range requests are not cached by an HTTP cache, so keep the index.

## 5. Finding the station of a place (api.weather.gov)

`GET https://api.weather.gov/points/<lat>,<lon>` (**four decimals at most, truncated**) gives `properties.gridId`, `gridX`, `gridY`, `observationStations` (a link), `forecastGridData`
(the gridpoints link) and `relativeLocation`. `GET <observationStations>` lists observing stations nearest first, each with `stationIdentifier` (`KOUN`, `KTIK`, `KOKC` for Norman, verified),
`name` and `geometry.coordinates` = `[lon, lat]`. The bulletin station is **the first of the nearest 6 whose id the file has** (use the section 4 lookup as the test). A User-Agent is required by the API.
Keep (id, name, gridpoints link) per place for good (the reference keeps them in preferences). **The place's coordinates are sent to `api.weather.gov` once** (say so in About). If the point is outside the NWS
area or no station is in the blend, show a message and no table.

## 6. The first hours: the NWS gridded forecast

`GET <forecastGridData>` (about 256 KB for a point; cache it about an hour; wxqt's `UtilityForecastPoint::fetch` already does this) has about 24 elements under `properties`, each `{uom, values:[{validTime, value}]}`.
Details that matter (verified on a real reply):

* `validTime` is `"2026-11-12T00:00:00+00:00/PT1H"`: a start and an **ISO-8601 duration** (`PT1H`, `PT6H`, `P1D`, `P1DT6H`); the API run-length encodes, so a value **stands** until its interval ends (durations 1 h to a day). Look a value up by `start <= t < start + duration`.
* Units are SI: `temperature`, `dewpoint` degC; `windSpeed`, `windGust` km/h; `windDirection` degrees; `skyCover` percent. Convert to the bulletin's units: F = C * 9/5 + 32; knots = km/h * 0.539957; direction tens = round(deg / 10) (0 becomes 36); sky as is. Round to integers.
* `weather` and `hazards` have lists (not numbers) as their `value`: skip them, or a JSON parser that expects numbers fails on the whole reply.
* The grid reaches about 8 days (`validTimes` was `2026-11-12T00:00:00+00:00/P8DT1H`).

The gap table samples the instants **every 3 hours** (UTC multiples of 3 h) from "now" up to the first NBS column, in the bulletin's codes (TMP, DPT, SKY, WDR, WSP, GST) so it merges like another bulletin. It has **no spreads, chances or amounts**: the first NBS column (6-hour values) carries the precipitation of those hours.
Reference: `shared/.../NwsGrid.kt` (`parseNwsGrid`, `nwsGapTable`), `NwsStations.kt` (`NwsPointClient`), tests `NwsGridTest.kt`, `NwsStationsTest.kt`.

## 7. The page

* **Header:** a drop list of the saved places (the last one used is remembered), the page menu, a refresh. A line under it: the station id and name, and which files the columns came from (e.g. `NWS grid to Thu 12 Nov 15Z · NBS 13Z · NBE 13Z · NBX 13Z`). Reload when more than 30 minutes old.
* **Layout:** a fixed label column on the left; everything else scrolls sideways in **equal-width columns** (46 dp in the app), so graphs and table line up although the spacing of the times changes. Day headings (UTC days) and a thin vertical line at each day start; hour labels (`18Z`), the gap columns in a different colour.
* **Rows, top to bottom:** day, UTC hour; **temperature graph** (see below); Temp, `+/- spread`, Dewpt, Hi / Lo (`TXN`: `H` at 00Z, `L` at 12Z; only if present), Sky % (cell shaded by the value); **wind graph** and Speed, Gust rows; **precipitation graph** and Chance, Rain in (`Q12` or `Q06` / 100 as `0.04`), Snow in (`S12` / 10), Ice in, T-storm % (T12 or T06), Snow kft (`SLV` / 10): the snow, ice, T-storm and snow-level rows only when some column is non-zero.
* **Temperature graph:** TMP line, DPT dashed line, a band of TMP +/- TSD (one standard deviation, drawn only where TSD exists; it grows from about +/-2 F at day 1 to +/-7..8 F at day 11), gridlines every 10 F with labels, a dashed blue line at 32 F when in range.
* **Wind graph:** WSP line, GST dashed line, gridlines every 10 kt, and along the bottom an arrow per column pointing **where the wind blows to** (WDR is the direction it comes *from*: arrow angle = WDR * 10 + 180); no arrow for calm.
* **Precipitation graph:** a bar per column of height P12, or P06 where the column has no P12 (6-hour chances in a lighter shade); green, or blue where PSN > 50.
* **Picking a time:** a click on a column (graph or table) selects it (a tinted band) and shows a bottom bar with the valid time and one button, "Heights & winds (REFS, else GFS)". A second click on the same column deselects.

## 8. The hand-off to the synoptic set up

The button **asks the REFS runs, it does not guess** (the first version guessed "within 54 h of now" and was wrong whenever the newest run was still being written):

1. If the valid time is more than about 66 h ahead (REFS reaches 60 h) or already past, no REFS: go to step 3 without any request.
2. Else take the newest REFS cycles (the app probes `<store>/.zgroup` back 6 h at a time, 8 cycles). For each cycle whose range `[cycle start, cycle start + 60 h]` contains the time, open it and read the **time coordinate** of the 500 mb wind product (`wind500_mean`; any product will do); the run **has** the time if a coordinate value is within 30 minutes of it. The first (newest) cycle that has it is the answer. A run still being written has only its first hours: it is passed over for the run before it.
3. REFS cycle found: show the REFS page on **that run**, product Heights & Winds, level 500 mb, at the time step nearest the time. Otherwise show the **GFS** page, product heights and winds 500 mb, at the **frame nearest the valid time** (GFS has 0.25 degree frames hourly to 120 h, then 3-hourly to 384 h, so the nearest is at most 1.5 h away).

wxqt-grib mapping (not run): `ModelViewer::selectModel("SPCREFS")` then `ModelViewer::openAt("SPCREFS", validUtc)` for step 3 on REFS (the run is chosen by the viewer from the time: check that it picks one that has the hour, and add the run check of step 2 if not),
and the GFS model id from `ModelScreen::sources` for GFS (id not checked). Choosing the 500 mb heights and winds product is the part that is specific to each viewer.
Reference: `PointViewModel.kt` (`openSynoptic`, `refsRunWith`), `RefsViewModel.showValid(valid, run)`, `GfsViewModel.showValid(valid)`.

## 9. Pitfalls met (so they need not be met again)

* The text files are **not one sorted list** (section 4). Test on the real files, not only on a tidy stand-in: the stand-in test passed while the real file still failed for 41 stations.
* NBS starts at hour 6; NBX of some cycles lists hours to 372 with no data past 264; NBE/NBX are 12-hourly but NBS 3-hourly, so the table is not evenly spaced in time.
* The NWS grid's `weather` / `hazards` values are lists; `windSpeed` and `windGust` are km/h, not knots; a value stands for its whole interval (`PT6H`, `P1D`), so do not take only the first hour.
* Wind direction in the bulletins is where the wind comes FROM in tens of degrees (36 = north).
* `api.weather.gov/points` wants at most four decimals (otherwise it redirects).
* Station ids of the bulletins and of the observing stations agree for land stations (`KOUN`, `KOKC`), so no table is needed; the first of the nearest few that the file has is the one.
* Range reads: S3 answers `206`; a read that reaches the end of the file is shorter than asked.

## 10. Not done / not verified

* Wind in mph, precipitation type as colours, a map thumbnail of the synoptic set up beside the table, hours 0 to 5 beyond the gap table's instants.
* The max / min convention for Guam; NBP (probabilistic) and NBH (hourly) bulletins; the v5.0 additions (WBG is shown in no row).
* The cycles' hour lists are as seen on 2026-11-12 (12Z and 13Z cycles); older cycles may differ.
* Tested on one place (Norman, OK) end to end; the station finder was checked on every station of the 12Z NBX and NBE files (all found), and the page itself on an emulator.

## 11. Reference implementation and tests

| File (this repo) | What |
|---|---|
| `shared/src/commonMain/kotlin/simplemodelview/NbmText.kt` | block parser, file lookup, one-station finder |
| `.../NwsStations.kt`, `.../NwsGrid.kt` | api.weather.gov client, station list, gridpoints parser, gap table |
| `.../PointTable.kt` | the merged table, day headings |
| `androidApp/src/main/kotlin/io/github/intelati26/simplemodelview/PointViewModel.kt`, `PointScreen.kt`, `PointCharts.kt` | the page |
| `shared/src/commonTest/kotlin/simplemodelview/NbmTextTest.kt` etc. | tests with real KOKC blocks (NBE, NBX and NBS) |

Commits: `7fcd5e9` (bulletins), `429fdb1` (NWS grid), `dd1dbb6` (the page), `8de8d43` (the REFS check). To rebuild test fixtures: take a block with `curl -r` ranges from the bucket as in section 4, or copy the KOKC blocks from `NbmTextTest.kt`.
