**To:** nodd@noaa.gov
**Subject:** noaa-nbm-pds (NBM GeoTIFFs) has had no new data since 2026-11-03 11:51Z; noaa-nbm-grib2-pds is current

Hello,

The GeoTIFF bucket for the National Blend of Models, `s3://noaa-nbm-pds` (anonymous access, us-east-1), stopped receiving new objects on 2026-11-03. The GRIB2 bucket, `s3://noaa-nbm-grib2-pds`, has kept updating normally. I checked both on 2026-11-07 at about 17:45Z.

**What I see in `noaa-nbm-pds`**

- The newest object is
  `blendv5.0/conus/2026/11/03/1100/temp/blendv5.0_conus_temp_2026-11-03T11:00_2026-11-14T06:00.tif`,
  LastModified 2026-11-03T11:51:18Z. The 11Z run on 2026-11-03 is the last one present.
- The same is true of every region folder: `conus`, `alaska`, `hawaii`, `guam`, `oceanic`, `puertoRico` and `global`. None has a day after `2026/11/03`.
- There is no `blendv5.1` folder (or any other new version folder) at the top level. The only version folders are `blendv3.2` through `blendv5.0`.
- Before this, a new run appeared every hour for years.

**What I see in `noaa-nbm-grib2-pds`**

- `blend.20261107/` has a folder for every hour from 00 to 17 (for example `17/core/blend.t17z.core.f001.co.grib2`, written 2026-11-07T17:41Z). Runs for 11-04, 11-05 and 11-06 are present as well.

So the NBM itself is running; the step that produces the GeoTIFFs appears to have stopped. The stop falls around the time of the NBM v5.1 / RRFS-related changes, but I do not know whether those are connected.

**Questions**

1. Is the GeoTIFF feed paused, discontinued, or expected to move (for example to a new version folder or a new bucket)?
2. If it is meant to resume, is there an expected date?
3. If it is being retired, where should GeoTIFF users go, or is GRIB2 the only supported format?

I maintain an open-source viewer (https://github.com/intelati26/simplemodelviewer) that reads the GeoTIFFs and now falls back to GRIB2 when they stop. A short note on the status would help me decide whether to keep both paths.

Thank you for running these buckets.

[your name]
