# How the MRMS site works (NSSL QVS "Op Product Viewer")

Researched 2026-10-02 by reading the viewer's own HTML / JavaScript and making one request to each endpoint with the page's own parameters. Nothing here
is built. Companion to `grib-companion-scope.md`.

## 1. Three different things share the name "MRMS"

| Thing | URL | What it is | Usable by an app? |
|---|---|---|---|
| **QVS Op Product Viewer** | `mrms.nssl.noaa.gov/qvs/product_viewer/` | A web page. A server renders PNG pictures from MRMS data. | Not as a data source (pictures only, internal API) |
| **NCEP MRMS server** | `mrms.ncep.noaa.gov/2D/<product>/` | Plain directory listings of GRIB2 files; the operational feed | Yes, last ~26 hours |
| **AWS Open Data bucket** | `noaa-mrms-pds.s3.amazonaws.com` | Same GRIB2 files, S3 listing API, no login, archive back to 2020-10-14 | Yes, the best source |

## 2. The viewer page (client side)

- A static HTML page, the Leaflet 0.7.3 library for the base map, and the lab's own JavaScript (`sci_*.js`, `product_viewer*.js`, written 2018-2019). No
  framework, no build step. Google Tag Manager is embedded.
- The page's initial state is baked into the HTML by PHP as JavaScript variables: `product`, `product_type`, `img_year/month/day/hour/minute`,
  `zoom`, `clon`, `clat`, `opacity`, `num_frames` (6), `seconds_step` (600), `looping_active`, `readout`. The "Permalink" button just reloads
  `index.php?...` with the same fields as query parameters, so every view is a URL.
- Time: UTC only; the picker snaps to the product's **minute resolution** (2, 5, 10, 15, 30 or 60 minutes, set per product in the menu file);
  "Current Time" and the Auto Update tick ask for "now minus 120 minutes" rounded down, i.e. it deliberately lags real time. The calendar starts at
  2020-10-01 (hard-coded: "no data before 2020").
- The viewer never receives gridded data. The browser stacks: Leaflet base map (OSM or NSSL's own terrain tiles
  `mrms.nssl.noaa.gov/tile_server/terrain/{z}/{x}/{y}.png`), a server-drawn **overlay** PNG (states, counties, ...), the server-drawn **product** PNG on a
  `<canvas>` with an opacity slider, and an optional mPING report layer.

## 3. The server endpoints (what the page calls)

| Purpose | Request (GET) | Returns |
|---|---|---|
| Menu | `shared/fetch_menu_json_from_config.php?menu=<server path>menu_config.txt` | JSON: top-level product types and sub-products with labels and minute resolution |
| Title | `shared/fetch_product_title_via_config.php?config_file=...&product=CREF` | plain text ("Composite Reflectivity") |
| Legend | `shared/fetch_svg_legend_via_config.php?...&product=` | an SVG color bar (QPE products have fixed legend files by scale) |
| **Product picture** | `local/render_multi_domain_product_layer.php?prod_root=CREF&year&month&day&hour&minute&clon&clat&zoom&width=920&height=630&qpe_pal_option&qperate_pal_option` | **PNG, 920 x 630, RGBA**, already projected to the current Leaflet view and colored (about 50 KB) |
| **Point value** | `local/get_multi_domain_rect_binary_value.php?prod_root&lon&lat&year...&minute` | JSON, see below |
| Overlays | `shared/fetch_overlay_image_from_geo_data-conus.php` | PNG of lines for the view |
| mPING | `shared/fetch_mping_json_on_demand.php?...&num_minutes=` | JSON reports (not MRMS data) |

How a frame is made: the page computes the Leaflet view (center, zoom, size) and asks for a picture. A PHP script runs a C++ program (the
`cpp_exec_dir` parameter points at `/home/metop/web/specific/opv/`) that reads the product's binary file for that minute, resamples it to the
requested view with the palette and returns the PNG. **Looping** is just N such requests (default 6 frames, 10 minutes apart) kept in `Image` objects
and flipped on a timer; nothing is cached by the app beyond the browser's image objects.

Point value reply (one request per mouse pause of 250 ms), example at 35.4 N, 97.5 W:

```json
{"file_type":"rect","column":3249,"row":1959,"raw_value":-990,"units":"dBZ","scale":10,
 "scaled_value":-99.0,"missing_flag":-99,"qvs_value":-99.0,"qvs_units":"dBZ"}
```

So the server's own store is a **plain rectangular grid of scaled integers** (value x scale, with a missing flag, -88888 = outside the domain). It
tells us the grid: column 3249 / row 1959 for 97.5 W / 35.4 N matches a 0.01 degree grid whose first cell is at 54.995 N, 129.995 W.

### Things to know about those endpoints

- The page passes **server file-system paths** (`cpp_exec_dir`, `web_resources_dir`) as request parameters. This is an internal interface for the page
  itself, not a published API: it can change without notice and is not something to build on (and not something to poke at).
- A plain `curl` gets `410 Gone` for the whole host; the same URL with a browser User-Agent returns 200. The host also sends an **incomplete TLS
  certificate chain** (no intermediate), which some clients reject. Both are consistent with a host not meant for programmatic use.
- The HTML contains a commented-out `product_viewer_shutdown_banner.css` (dated 2025-10-01) and a `<div id="shutdown_banner">`. The viewer is working
  today, but a shutdown banner exists; do not depend on it.

## 4. Product catalog (the menu file)

`menu_config.txt` is a tiny text format: a `start_top ... end_top` block of top-level types (`code|label|default minute resolution|number of options|`), then one
`start_sub ... end_sub` block per type. 20 top-level types, about 300 sub-products, each with its own cadence:

- 2 min: reflectivity (base, composite, SHSR, lowest altitude, layer/temperature-level), echo tops, VIL / VII, rotation (azimuthal shear and tracks),
  hail (SHI, POSH, MESH, swaths), lightning probability, radar quality, precipitation flag / bright band, FLASH recurrence and ratio products, evaporation rate.
- 5 min: ZDR / RhoHV 3D levels. 10 min: FLASH CREST / SAC flow and soil saturation.
- 15 / 30 / 60 min: longer-window swaths (2 / 4 / 6 / 24 h), the gauge influence index, QPE (1 h to 72 h, Pass 1 / Pass 2 and radar-only / multi-sensor),
  model-derived fields (2 m temperature, wet bulb, freezing height, tropical rain probability).
- The 3D mosaic has 33 levels from 0.5 to 19 km, each with reflectivity, ZDR and RhoHV.

Product codes in the viewer (`CREF`, `MESH`, `Q3...`) are **not** the file names on the public servers (`MergedReflectivityQCComposite`, `MESH`,
`MultiSensor_QPE_01H_Pass2`); a mapping table would be needed.

## 5. The public data (what an app could actually use)

**NCEP:** `https://mrms.ncep.noaa.gov/2D/<ProductName>/` is an Apache-style listing (132 product directories at the time of writing: CONUS products
at the top, plus `ALASKA/`, `CARIB/`, `GUAM/` regions). Files are `MRMS_<Product>_<level>_<yyyymmdd>-<HHMMSS>.grib2.gz`; there is also an
`MRMS_<Product>.latest.grib2.gz` alias. About 777 files (about 26 hours) are kept for the 2 minute products. The server sets a short `session` cookie (10 minute
expiry), which hints at rate limiting; keep requests few and polite.

**AWS Open Data:** `s3://noaa-mrms-pds`, listing over HTTPS (`?list-type=2&delimiter=/&prefix=`), no account needed.

```
CONUS/<Product>_<level>/<yyyymmdd>/MRMS_<Product>_<level>_<yyyymmdd>-<HHMMSS>.grib2.gz
```

- Top prefixes: `CONUS/` (243 product directories), `CONUS_5KM/`, `ALASKA/`, `HAWAII/`, `CARIB/`, `GUAM/`, `ANC/`, `ConvectProb/`, `ProbSevere/`, `unsupported/`.
- Archive from 2020-10-14 to today, one directory per day, one file per product per scan (386 files by 12:52 UTC for a 2 minute product).
- A listing returns at most 1000 keys, so a "newest date" lookup has to use `start-after`, not just read the first page.
- **Upload delay measured on 20 consecutive CREF files:** the object appears 41 to 47 seconds after the file's valid time (average 44 s).
- `Last-Modified` works as the file-upload time, exactly as in simplemodelview.

## 6. File format (verified from real bytes)

A real CREF file was read (only the first 8 KB, decompressed) and decoded by hand:

| Item | Value |
|---|---|
| Container | GRIB2, gzip-compressed (`.grib2.gz`), one field per file |
| Grid (section 3, template 3.0) | regular **latitude / longitude**, **7000 x 3500** = 24,500,000 points, 0.01 degree spacing |
| Extent | 54.995 N to 20.005 N, 230.005 E to 299.995 E (130.0 W to 60.0 W), rows run north to south |
| Packing (section 5, template **5.41**) | **PNG**: the data section is an embedded 16-bit PNG, 7000 x 3500 |
| Reference time | in section 1 (matches the file name) |
| Size on disk (gzip of the PNG-packed GRIB) | CREF 1.4-1.5 MB; echo top 1.1 MB; precipitation rate 0.9 MB; precipitation flag 0.2 MB; rotation track 0.3 MB; **MESH 35 KB** |

Consequences: PNG packing means a decoder needs only zlib / libpng-style inflate plus the GRIB2 scaling (reference value, binary and decimal scale
factors), not JPEG2000. Decoded as floats a field is about 98 MB, so an app should keep it as 16-bit integers (49 MB) or decimate / crop to the
viewed sector before drawing.

## 7. What this means for a companion app

| Question | Answer |
|---|---|
| Can the QVS viewer be the data source? | No: server-rendered pictures through an internal, undocumented interface; likely headed for retirement. |
| Is the data in Zarr / blosc like REFS? | No: GRIB2 with PNG packing (and gzip over it). Different decoder (see below). |
| Where from? | AWS bucket first (listing API, archive, stable), NCEP's `2D/` listing as the fallback. |
| Run structure? | None: one file per scan, every 2 minutes for most products, then 5, 10, 15, 30 or 60 minutes. A loop is "the last N files". |
| Decoder work | Section parsing, template 3.0 (lat/lon), template 5.41 (PNG), a bitmap if present, gunzip. Small compared with the general GRIB2 problem; the PNG part can reuse a small inflate. |
| Grid | Fixed lat/lon grid; the core's per-pixel inverse projection becomes a trivial index lookup. A coarser regional product (`CONUS_5KM/`) exists. |
| Product list | Take the menu structure above (types, sub-products, cadence) but key products by their public directory names. |
| Delay display | Valid time vs `Last-Modified` (about 44 s on S3); the "age" line replaces "run done +hh:mm". |
| Value read-out | Local: index into the decoded grid (the viewer's own read-out shows the same fields: raw integer, scale, missing flag, units). |

## 8. Open points

- The shutdown banner: worth asking NSSL whether the QVS viewer is being retired; it does not affect the public GRIB2 data.
- NCEP's `.latest` alias and the session cookie: confirm what rate NCEP expects before polling.
- Template 5.41 details (bits per value, how missing and no-coverage are coded) still need a full decode checked against a GDAL reference.
- Palettes: the QVS legends are SVG and the color tables live on the server; wxqt's own MRMS palettes are the practical source.
