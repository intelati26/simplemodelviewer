# GFS station files (BUFR) for the Extended page: one station

What the GFS publishes per station, and what reading it would cost against what the Extended page does now. Measured on the 06Z run of 12 Nov 2026 for
Oklahoma City (WMO 723530, the station the page uses for OKC).

## The file
- **Where:** `https://nomads.ncep.noaa.gov/pub/data/nccf/com/gfs/prod/gfs.<yyyymmdd>/<HH>/atmos/bufr.t<HH>z/bufr.<WMO id>.<yyyymmddHH>`: one file per station, about 2,200 stations,
  listed by the directory page. The AWS bucket has only the tarball of them all (`gfs.t<HH>z.bufrsnd.tar.gz`, 191 MB), which is not worth reading for one station.
- **Size:** 101,576 bytes for OKC, one request. 13 BUFR messages: three table messages (5,078 / 76 bytes and one more) and 10 data messages of 9,560 bytes.
- **Format:** NCEP's BUFR with its own table definitions in the first message (as text: `TABLE B ENTRY - ...`), so a decoder reads the tables from the file and needs
  no outside BUFR tables. The forecast hours are in the data messages (`FTIM`, seconds), hourly to 120 h, then every 3 h to 384 h.

## What is in it
| Group | Fields (BUFR name, unit as stored) |
|---|---|
| Header, once per hour | forecast time `FTIM` (s), station `STNM`, id `RPID`, latitude `CLAT`, longitude `CLON`, grid elevation `GELV` (m) |
| Profile, one per model level (about 64) | pressure `PRES` (Pa), temperature `TMDB` (K), wind `UWND` / `VWND` (m/s), specific humidity `SPFH`, vertical velocity `VVEL` |
| Surface | sea-level pressure `PMSL` (Pa), surface pressure `PRSS`, skin and soil temperature, 3-h total and convective precipitation, snow water equivalent, low / middle / high cloud (%) |
| 10 m and 2 m | `U10M`, `V10M`, `T2MS` (K), `Q2MS`, precipitation type flags (snow, ice pellets, freezing rain, rain) |

**Not in it: heights of pressure surfaces.** The profile is on model levels with their pressures, not heights. The 500 mb height has to be built (as BUFKIT does):
integrate the hydrostatic equation up from the station elevation and surface pressure with the virtual temperature (from `TMDB` and `SPFH`) to the level where pressure is 50,000 Pa.
The 850 mb temperature is an interpolation in log pressure between the two levels around 85,000 Pa. Sea-level pressure is read as it is (`PMSL`).

## The comparison (OKC, 40 times: every 3 h to 72 h, every 12 h to 264 h)
| | Now: GFS grid through NOMADS | GFS station file (BUFR) |
|---|---|---|
| Requests | 40 (one per time), 2 at a time | 1 |
| Bytes | 41,462 in all (about 1 KB each) | 101,576 |
| Time | about 0.45 s each, so 10 to 20 s for the 40 | 0.3 to 0.4 s |
| Covers | only the 40 times asked | every hour to 384 h, and the surface, wind, cloud and precipitation fields as well |
| Per run | refetched for each new run, per time | one file per run (every 6 h) |
| Values | pressure, 500 mb height and 850 mb temperature as the model has them on the grid, bilinear to the point | pressure as is; 500 mb height and 850 mb temperature **derived** from the profile; the model's own point (nearest grid cell) |
| Needs | a Lambert / lat-lon point sampler (done) | a BUFR decoder reading the embedded tables (not written) and a place to station (WMO id) lookup |
| Fails when | NOMADS is slow or refuses many small requests | the station is not in the list (about 2,200, mostly airports) |
| Fallback | none (whole AWS fields would be about 84 MB for the 40 times) | the grid rows above |

The grid fetch is already small in bytes; what the station file saves is the request count (40 to 1) and, in return, the page's other GFS data (cloud, precipitation, wind)
would come in the same file. Against that, the 500 mb height is derived, so it would be checked against the grid value before it replaced it.

## What it would take
1. A BUFR reader for this one layout: Section 0 to 5 framing, table messages parsed from their text, the data messages as a bit stream by the descriptors above
   (`HEADR` once, then `PROFILE` repeated by a delayed replication, then `CLS1` and `D10M`). Around 300 lines of Kotlin in `shared/`, tested against the OKC file kept in `fixtures/`.
2. The height integration and the log-pressure interpolation, tested against the grid's values for the same time (they should agree to a few meters and a degree).
3. The station: the NWS station id (`KOKC`) to a WMO id (723530) from the directory listing of the run, matched on the three letters or the nearest coordinates in the header.
