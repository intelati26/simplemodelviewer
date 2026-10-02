# Scope: GRIB / MRMS companion app (working title `simplegribview`)

Scoping only; nothing here is built. It uses this repository (simplemodelview) and the desktop viewer wxqt (`~/Claude/wxqt-grib`) as scaffolding.
Written 2026-10-02.

## What it is

A minimalist viewer for the most recent **MRMS** products and for operational **model fields** (HRRR, RRFS, NAM, GFS): pick a field, see it on a
phone map or globe, loop it, tap for a value, export. Same shell as simplemodelview.

**Not in scope:** alerts or location-based forecasts (owner decision, see `outline.md`), contours / soundings in v1, accounts, any backend of ours.

## What wxqt tells us

- Its MRMS viewer (`src/mrms/`, about 1,300 lines) lists `https://mrms.ncep.noaa.gov/2D/<product>/`, picks files named
  `MRMS_<product>_<level>_<date>-<time>.grib2.gz`, and carries a hand-built product list and palettes.
- Its model viewers (`src/models/UtilityGrib.cpp`, about 2,400 lines, plus interfaces for NCEP, ESRL, NSSL WRF, SPC HREF / HRRR / SREF and RRFS on
  NOMADS) call **external GDAL binaries** (`gdal_translate`, `gdalwarp`, `gdaldem`, `gdal_contour`, `ogr2ogr`) for decoding, reprojection,
  colouring and contours.
- GDAL cannot ship in a phone app, so the one large new piece is an **in-app GRIB2 decoder**. wxqt's own GDAL output is the test oracle: decode a
  file with both and compare.
- The NSSL QVS viewer is not a data source (it serves finished pictures); see `outline.md` section 10.

## Reused unchanged from simplemodelview

The rasteriser, globe, views / sectors and screen-area export (picture and MP4), the HTTP cache and politeness settings, the user-set contact email
in the User-Agent, the file-time / delay line, the line geometry, the widget, the CI (builds, emulator smoke test with screenshots) and the Compose
UI shell.

## New work

1. **GRIB2 decoder in the C++ core** (the main cost)
   - Section parsing (0-7), a bitmap section, gunzip for MRMS (`.grib2.gz`).
   - Grid templates: lat/lon (MRMS, GFS), Lambert conformal (HRRR / RRFS; this is the same grid as REFS), polar stereographic (NAM Alaska).
   - Packing: simple, complex with spatial differencing, JPEG2000, PNG. Which of these each product really uses has to be confirmed from real files.
   - Vendor NCEP's public-domain `g2c` with OpenJPEG (BSD) and zlib rather than eccodes (large). Unit-test against GDAL-produced reference values.
2. **Generic grids in the core.** Today the core has two compiled-in grids (`SPC_GRID_REFS`, `SPC_GRID_GREFS`). It needs "grid as data" with
   lat/lon and Lambert kinds. This is step 2 of `docs/separating-spc.md`.
3. **Providers** (step 1 of the same doc)
   - **MRMS:** no cycles; a "run" is the latest frame. Listing from the NCEP directory or the AWS `noaa-mrms-pds` bucket (S3 listing works).
   - **NCEP models:** runs every cycle; fetch only the fields wanted by byte range using each file's `.idx` index (NOMADS, or AWS
     `noaa-hrrr-bdp-pds` for HRRR).
4. **Catalogue and palettes.** Port wxqt's MRMS product list and palettes as data. Model fields are keyed by GRIB discipline / category /
   parameter / level, with titles from a table.

## How it differs from REFS

- MRMS updates about every 2 minutes and has no run structure: the slider covers the last 30-60 minutes, and the "delay" line becomes data age.
- The MRMS CONUS grid is about 7000 x 3500 cells (to verify). As floats that is roughly 100 MB, so frames need a smaller storage type, cropping to a
  sector, or decimation (the existing data-saver step helps).
- Frames are bigger and more numerous: the loop budget, the Wi-Fi-only default and the cache cap need retuning.
- No SPC-specific parts: titles, colours and branding come from the provider.

## wxqt tool -> companion replacement

| wxqt (external GDAL) | In the companion |
|---|---|
| `gdal_translate` (decode) | GRIB2 decoder in the core |
| `gdalwarp` (reproject to lat/lon) | per-pixel inverse projection in the rasteriser (already how the flat map and globe work) |
| `gdaldem` (colour relief) | the palette rasteriser (already exists) |
| `gdal_contour` / `ogr2ogr` | not in v1 (later: marching squares in the core) |

## Phases (one developer, with the reuse above)

| Phase | Content | Effort |
|---|---|---|
| M0 | GRIB2 decode spike: one MRMS file and one HRRR field, matched to GDAL on CI; speed and memory on a phone | about 2 weeks |
| M1 | MRMS viewer: product list, newest frame, loop, map / globe, tap value, export | 2-3 weeks |
| M2 | Model fields (HRRR / RRFS / NAM / GFS) via `.idx` byte ranges, runs and forecast hours | about 3 weeks |
| M3 | Widget, accessibility, polish, release | 1-2 weeks |

Roughly 8-10 weeks. M0 comes first and decides whether the rest is feasible.

## Risks

- **GRIB2 coverage:** per-product template surprises; JPEG2000 decode speed on mid-range phones is unmeasured.
- **Server etiquette:** NOMADS throttles heavily and MRMS has no SLA; prefer the AWS buckets where they can list. Keep one request per frame,
  caching, and the contact-email User-Agent.
- **Memory:** see the grid-size point above (index + palette is 1 byte per cell instead of 4).
- **Source churn:** wxqt's code comments already note operational dates that move (RRFS on NOMADS); read metadata, do not hard-code layouts.
- **Name:** another "model viewer" style name, so the same collision check applies.
- **Licence:** code ported from wxqt is GPLv3, as for simplemodelview.

## Repository strategy

Do not fork. Finish the provider split first (`separating-spc.md` steps 1-4), then add the GRIB provider and decoder in the same repository as a
**second app module** that shares the core and the Kotlin code. Starting the companion before the split would copy SPC-specific assumptions into it.

## Open questions for the owner

1. Which sources first: MRMS, or HRRR / RRFS?
2. Separate identity (own name, icon, application ID) or a mode inside simplemodelview?
3. Contours in a later version? They are the biggest functional gap to wxqt.
4. Same distribution rule (GitHub only) and the same optional contact-email prompt?

## Lesson carried over from simplemodelview
Do not trust a store's own coordinate arrays blindly: check a projection against the data (correlate a field with another field on a known grid) before shipping it.
GREFS's latitude / longitude arrays disagreed with its data; the grid its GRIB attributes described was right.
