# SPC outlook overlay, and a comparison with wxqt's MRMS overlays

## The outlook overlay (all pages)
"SPC outlook" on the REFS, MRMS and NBM pages draws the Storm Prediction Center's outlook for a day over the map: the same choice on every page (it is
saved), lowest level first so the higher ones lie on top, faint fill with an outline, hatched "significant" areas dashed. A color key and the valid times
sit above the legend; tapping the map adds "SPC Day 1 categorical risk: Slight Risk" (or the probability) to the read-out; exports include it. Not drawn
on the globes. The file is read again when a page returns to the screen and the data is older than 15 minutes.

| Day | Choices |
|---|---|
| 1, 2 | categorical risk; tornado, damaging wind, hail probabilities |
| 3 | categorical risk; severe chance |
| 4-8 | severe chance |

Source: SPC's public GIS data, `https://www.spc.noaa.gov/products/outlook/<name>.lyr.geojson` with names `day1otlk_cat`, `day1otlk_torn` / `_wind` / `_hail`, `day3otlk_prob`,
`day4prob` ... `day8prob`. One feature per level with DN, LABEL, LABEL2, VALID / EXPIRE / ISSUE (yyyyMMddHHmm UTC) and `stroke` / `fill` colors; polygons and
multipolygons, the first ring of each polygon its outer ring and the others holes (`Outlooks.kt`, tested with built samples).
**Not checked against the live files:** this build environment cannot reach spc.noaa.gov, so the file names and property names are from SPC's published
GIS documentation, not from a download. The parser skips anything it does not understand; if SPC's files differ, the overlay shows "No risk areas" or a
"could not be read" note instead of drawing, and `Outlooks.kt` is the one place to adjust. The colors fall back to wxqt's day 1 map (MRGL dark green,
SLGT yellow, ENH orange, MDT red, HIGH magenta) when a feature has no `stroke`.

## wxqt's MRMS overlays against ours (read from wxqt-grib `src/mrms/MrmsOverlays.*`, `src/mapkit/MrmsOverlayLayers.*`)
| | wxqt | simplemodelview |
|---|---|---|
| Lightning strikes | CG strikes of the last 1 / 5 / 15 / 30 min as crosses by age, dark casing | the "lightning nest": last 30 min (20 %), 5 min (50 %), 1 min (solid) as crosses (a little larger than wxqt's); no 15 min step |
| Lightning chance | MRMS next 30 / 60 min **contoured** at 10 / 30 / 50 / 70 % over shaded bands (wxqt also has the LightningCast layer, which is the better one) | **LightningCast** placefile contours (10-90 %), the same feed and parser as wxqt's LightningCastLayer; the MRMS probability grids were removed |
| Hail | swaths of the last 30 min, 1, 2, 6, 24 h, bands at 1 / 1.75 / 2.5 / 4+ in, black edge (wxqt gives only rotation the white halo) | MESH and swath products as overlays, own palette from 0.25 in, minimum chosen from its bands, now with the black edge **and** white halo |
| Rotation | 0-2 km tracks 30 min-24 h and 3-6 km 1-6 h in **grays to black** from 8e-3 /s, edged black with a white halo, so a track reads over any reflectivity color | the 14 rotation products as colored fills with a minimum value, now **edged in black with a white halo** (wxqt's edge / halo code ported; its gray palette is not) |
| Thin features | the field is max-pooled to 4 x 4 and 16 x 16 cell blocks so a thin track still shows when zoomed out | reprojection writes unsampled cells to the nearest target cell; single cells are drawn one pixel bolder |
| Scope | master map: the layers go over **any** map layer, with legends, refreshed every 120 s | MRMS product overlays on the MRMS page only; alerts on the MRMS page only; the SPC outlook now on all three pages |
| Anything else | four fixed overlays | any of the 134 products, a stack, opacity |

Done since: the strike cross, the LightningCast overlay (all pages), the halo on rotation and hail. Still open: wxqt's gray rotation palette, a 15 min strike step, the max-pooled coarse levels for thin tracks, and the MRMS product overlays on the NBM and REFS pages.
