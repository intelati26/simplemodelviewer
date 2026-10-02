# MRMS products in the app

Surveyed 2026-10-02 from the newest real file of each directory of `noaa-mrms-pds/CONUS/` (243 directories; the 99 per-level 3D-mosaic ones
`MergedReflectivityQC_*`, `MergedRhoHV_*`, `MergedZdr_*` and the `ANC_*` ones, which had no files, are not offered). The app's catalogue
(`shared/.../MrmsCatalogue.kt`) holds 135 products in nine buckets. Every one decodes with the same core decoder
(`core/src/mrms_decode.cpp`); the 24 / 32 bit ones were checked bit for bit against GDAL on the full files.

## File format facts
- Always GRIB2 template 5.41 (PNG packing), no bitmap. The PNG is grayscale 8 / 16 bit, or **8-bit RGB (24 bit) / RGBA (32 bit)**: the sample is
  the pixel's bytes in order (R first). Value = (R + sample * 2^E) / 10^D. The FLASH, thickness, lightning-probability and model-temperature
  products are the 24 / 32 bit ones.
- Grid: 7000 x 3500 at 0.01 degrees, except rotation tracks and azimuthal shear (14000 x 7000 at 0.005).
- GDAL does the scaling in float32, so for files with a large offset (R about -10000) its values are off by up to about 1e-3; the core's are exact.
- "Missing" is stored as codes, which differ by family (the catalogue's `missingAtOrBelow`):

| Family | Codes | Treated as missing |
|---|---|---|
| Reflectivity (all kinds, layers, isothermal, 1 h maxima) | -999 no coverage, -99 no echo (and real negative dBZ) | <= -90 |
| Echo tops, VIL, VII, MESH, SHI, POSH | -3 no coverage, -1 no echo | <= -0.5 |
| QPE, precipitation rate, rotation, quality, warm rain, heights | -3 (and -1) | <= -0.5 |
| Gauge influence index | -3 and a negative filler (-0.696 / -1) | <= -0.0005 |
| FLASH, thickness, model temperature (24 / 32 bit) | -999 / -9999 | <= -500 |
| Lightning probability | -99900 | <= -50 |
| NLDN lightning density | -1 | <= -0.5 |

## Cadence (gaps between files, measured over a day)
2 min: reflectivity, echo tops, VIL, MESH, SHI, POSH, azimuthal shear, rotation tracks up to 1 h, precipitation rate, FLASH ARI / FFG, radar quality,
bright band, lightning probability. 1 min: NLDN. 10 min: FLASH streamflow / soil saturation. 15 min: 15-minute QPE. 30 min: swaths and rotation tracks of
2 h or more. 60 min: QPE of 3 h or more, multi-sensor QPE, gauge influence, accumulation quality, model fields, 1 h maxima, warm rain probability.
Upload delay after the valid time: about 0.5-1 min for the 2-minute products, 3-5 min for QPE and swaths, 17 min for Pass 1 and about 1 h for Pass 2
multi-sensor, and 1.4 h for the model fields.

## Buckets
- **Reflectivity (18):** composite (QC and raw), base (QC and raw), seamless hybrid scan, lowest altitude (QC and raw), low-level composite, layer composites
  (low / high / super), 1 h maxima of composite and base, isothermal reflectivity at 0 / -5 / -10 / -15 / -20 C.
- **Echo tops and VIL (15):** echo tops 18 / 30 / 50 / 60 dBZ (stored in km, shown in kft), enhanced echo top, VIL (and high resolution), VIL density, VII, VIL swaths (2 h, 24 h), thickness (50 / 60 dBZ top above 0 / -20 C).
- **Rotation (14):** azimuthal shear (0-2, 3-6 km, instantaneous), rotation tracks of 30 min, 1, 2, 4, 6 and 24 h at both layers.
- **Hail (9):** MESH (shown in inches), hail swaths 30 min to 24 h, probability of severe hail, severe hail index.
- **Lightning (6):** NLDN cloud-to-ground density 1 / 5 / 15 / 30 min, lightning probability next 30 / 60 min. (The lightning-jump grids and the `scale_1`
  naming of the probability directories are as the bucket has them.)
- **Rainfall (24):** precipitation rate, radar-only QPE (15 min, 1 / 3 / 6 / 12 / 24 / 48 / 72 h, since 12Z), multi-sensor QPE 1-72 h, Pass 2 (best) and Pass 1 (quicker).
- **Flash flooding (19):** QPE / flash-flood-guidance ratio, return period (30 min to 24 h, maximum), streamflow and unit streamflow (CREST, SAC-SMA, hydrophobic), soil saturation (CREST, SAC-SMA).
- **Quality (22):** radar quality index, accumulation quality 1-72 h, gauge influence 1-72 h (Pass 1 / 2).
- **Model and heights (8):** freezing level, surface and wet-bulb temperature (shown in F), warm rain probability, bright band top / bottom, heights of composite reflectivity.

## Not offered (yet)
- **Categorical fields:** `PrecipFlag` (surface precipitation type) and `SyntheticPrecipRateID` hold flag codes (0 none, 1 warm stratiform, 3 snow, 6 convective, 7 hail, ...) and need a
  labelled legend and read-out.
- **Lightning jump** (`LtgJumpGrid_*`, a non-dimensional change index) and **`ANC_*`** (empty at survey time).
- **3D mosaic levels** (reflectivity, ZDR, RhoHV at 33 heights each): a level picker would suit them better than 99 list entries.
- Palettes for the FLASH, quality and a few other families are first guesses (thresholds chosen from today's value ranges); tune them when there is a case to look at.
