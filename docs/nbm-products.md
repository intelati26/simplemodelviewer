# National Blend of Models page

Surveyed 2026-10-30 against the public bucket `noaa-nbm-pds` (GeoTIFF) and `noaa-nbm-grib2-pds` (GRIB2).

## Data
- **GeoTIFF bucket** (used): `blendv5.0/<region>/<yyyy>/<MM>/<dd>/<HH>00/<field>/blendv5.0_<region>_<field>_<run>_<valid>.tif`, e.g.
  `blendv5.0/conus/2026/10/30/1600/appttemp/blendv5.0_conus_appttemp_2026-10-30T16:00_2026-11-10T12:00.tif` (colons are `%3A` in links).
  One file per field and forecast time; regions `conus`, `alaska`, `hawaii`, `puertoRico`, `guam`, `oceanic`, `global`. The app reads `conus`.
- **GRIB2 bucket** (not used): `blend.<yyyymmdd>/<HH>/core/blend.t<HH>z.core.f<FFF>.<region>.grib2` plus `.idx`; about 172 MB per forecast hour for CONUS,
  with the `core/` folder of a run stopping at f016. The GeoTIFFs hold the same fields, one small file each, so no GRIB decoder is needed.
- **Encoding:** classic little-endian TIFF, 256 x 256 tiles, one band, LZW (compression 5) with predictor 1, 2 (integers) or 3 (floats), 16-bit integers or 32-bit
  floats (`SampleFormat` 2 / 3), GDAL nodata -9999 (tag 42113) on most fields. Georeferencing: PixelIsArea, tie point + pixel scale + a Lambert conformal conic
  2SP definition (std parallels 25 / 25, false origin 25 N 95 W, sphere 6371200 m). Grid 2345 x 1597, 2539.703 m cells, first (south-west) cell center 19.229 N
  126.2766 W (NCEP's NBM CONUS grid). `core/src/geotiff_decode.cpp` reads all of that; its tests compare 400 sampled cells of four real files
  (`fixtures/nbm`, `scripts/make_nbm_fixtures.py`) with Pillow's decode.
- **File size:** 0.1 MB (thunderstorm probability) to 0.9 MB (temperature, QPF) each. A full 108-frame temperature loop is therefore about 90 MB (cached by the
  HTTP cache; the Wi-Fi-only setting applies).

## Units (checked against the GRIB2 of the same run)
Temperature, dewpoint, apparent temperature, percentiles: degrees F. Wind and gusts: knots. QPF, snow, precipitable water: inches. Visibility: miles.
Ceiling: hundreds of feet (1075 = no ceiling). CAPE: J/kg. Probabilities and cloud / humidity: percent. `qpf06total` / `snowamt06total` are totals since the
run's start.

## Runs and range
- A run starts every hour. Complete runs (checked for all 00-16Z of 2026-10-30) reach 259-264 h (about 11 days): hourly frames for the first 36 h (a few
  fields to 72 h), then every 3 h, then every 6 h; 6-hourly fields (`qpf06`, `pop12`) every 6 h; daily extremes (`maxt`, `mint`) every 24 h.
- A run in upload has only some of its fields and frames (at 17:43Z the 17Z run had 19 of ~700 fields). The page therefore takes the newest run whose frame
  count is at least 90 % of the one before it, and says so when it falls back.
- The run picker marks runs that reach a week or more as **extended** ("extended, to day 11"). All complete runs seen qualify, so the mark mostly tells a
  finished run from one that is still arriving ("short range, to N h" / "not ready"). The lead is found with one small listing per run
  (`start-after` the frame at +167 h), see `NbmRepository.lastLead`.

## Loop timing
Frames are not evenly spaced, so Play holds each frame for **base x the forecast hours it stands for** (base 150 ms): 1 x for hourly frames, 3 x for 3-hourly,
6 x for 6-hourly. A day of forecast then takes the same time to play at every spacing (`nbmDwellMillis`). The loop video holds each frame for the same
number of slots. "Loop: N" chooses how much of the run, from its start (12 h, 24 h, 2 / 3 / 5 / 7 days, all).
