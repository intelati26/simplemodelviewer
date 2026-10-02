# NOMADS `spc_post`: SPC products as GRIB2

Researched 2026-10-02. **Nothing here is built.** The owner is holding off and will look again in a couple of weeks, when the new `prod` data arrives
(the `para` area is parallel / pre-operational, so names and folders may change).

## Where
`https://nomads.ncep.noaa.gov/pub/data/nccf/com/spc_post/{prod,para}/spc_post.<yyyymmdd>/<folder>/` — today's and yesterday's dates are kept.
Plain `curl` gets an empty body from the directory pages; use `curl --compressed` (or a browser).
Files are tiny (0.4-21 KB), one per forecast hour; NOMADS throttles heavy use, so fetch only what is shown and cache.

## What is there

### `prod` (HREF- and GEFS-based)
| Folder | Contents | Notes |
|---|---|---|
| `severe` | `href_cal_{hail,wind,tor}` 4 h and 24 h calibrated probabilities, and `href_cal_gefs_*` blends (cycle tags 00/06/18) | 0.4-1 KB per file |
| `thunder` | `hrefct_{1hr,4hr,full}` thunder probability | about 8 KB |
| `ltgdensity` | `hrefld_4hr`, f004-f048 | about 21 KB; GDAL labels the bands "thunderstorm probability > 25 / 50 / 100 / 200" (`ProbTSTM04`), which looks like flash-count thresholds; the units are **not confirmed** |
| `gefs_mlp` | machine-learning `{severe,wind,tor,hail}_{std,sig}` probabilities, out to about day 8 | lat/lon grid 139 x 56 (0.47 degrees), packing template 5.3 |

### `para` (parallel)
| Folder | Contents | Format |
|---|---|---|
| `grefs` | `grefs_<gefs cycle 00/06/12/18>.{4hr,16hr,20hr,23hr,24hr}`: hail, tornado and wind probabilities, 3 messages per file | GRIB2, about 2.6 KB per file |
| `refsct` | `refsct_{1hr,4hr,full}` thunder probability | GRIB2, about 5.5 KB per file |
| `gefsct` | GEFS thunder probability | GRIB2 |
| `gefs_mlp` | as in `prod` | GRIB2 |
| `rrfsd` | `rrfs_d`, about 480 files | **NetCDF** |
| `sfcoa-hrrr` | `sfcoa_hrrr.{final,firstguess}`, hourly | **NetCDF** |

## What it means for the app
- `para/grefs` and `para/refsct` are the **same products** as the Guidance bucket the app reads from SPC's Zarr stores (GREFS 4 h / 24 h
  tornado, wind and hail; REFSCT thunder). The REFS fields themselves (composite reflectivity, updraft helicity, the member viewer, ...) are **not**
  here: they stay on the SPC Zarr site.
- New content the SPC site does not have: GEFS thunder (`gefsct`), the machine-learning probabilities (`gefs_mlp`), and `ltgdensity` (the lightning
  product; REFS itself has no lightning field).
- The HREF, GREFS and REFSCT files are on the 185 x 129 NCEP grid 212 (Lambert, 40.635 km, parallel 25, 265 E): the grid the core already has for
  GREFS (`core/src/grids.h`). GDAL reports the same false origin (-95 degrees) and 40635 m pixels.
- **Packing: JPEG2000** (GRIB2 data representation template 5.40, confirmed by the owner). That needs a JPEG2000 decoder in the core; the realistic
  option is vendoring OpenJPEG (BSD), about 60 source files to build for Android and iOS. Speed does not matter at 185 x 129 cells.
  `gefs_mlp` uses template 5.3 (complex packing with spatial differencing, about 200 lines) on a lat/lon grid (the grid kind built for MRMS).
- The NetCDF folders (`rrfsd`, `sfcoa-hrrr`) would need a separate, heavy reader: skipped.

## Idea for later
An optional "NOMADS" source for the Guidance bucket plus the GEFS thunder / machine-learning probabilities and the lightning product, reusing the
lat/lon grid, the GRIB section reader and the GDAL-checked fixtures from the MRMS work (`docs/mrms-site-outline.md`, `scripts/make_mrms_fixtures.py`).
It would lighten the load on SPC's site only for that bucket.

## To check before building
- Whether the `para` values and timing match SPC's Zarr stores (not compared).
- Whether `prod` files change names or move when the new data arrives, and whether the HREF products are retired as REFS takes over.
- The units of `ltgdensity`, from NCEP's documentation.
- NOMADS' request-rate limits for a mobile client (and the User-Agent / contact-email policy already in the app).
