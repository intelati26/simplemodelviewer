# SPC site color tables

The SPC page draws each field with the color table of SPC's own REFS viewer (https://www.spc.noaa.gov/exper/refs/viewer/), so the colors match the site.

## How the viewer draws a field
- It is a WebGL app. A field is a texture of the values on the grid, **magnified with linear filtering** (bilinear interpolation of the values by the GPU), and a
  fragment shader then looks the color up in the table **with nearest-neighbor filtering**. The result is a smooth, banded "filled contour" look, with band edges
  that are the iso-lines of a bilinear surface. The core's renderer does the same (bilinear on the values, then banded colors; see `core/src/raster.cpp`).
- A table is `levels` (band edges, one more than there are colors), one color per band, an optional color below the first level and one above the last (none: transparent),
  and the layer's opacity (0.9 for most fields). NaN cells are not drawn.

## Where the tables come from
`fixtures/spc-site/primary-colormaps.json` has, for each stored variable, the table of the viewer's main filled layer. It was captured by serving a local copy of
`spc-refs-viewer.js` in a browser with three constructors instrumented (the colormap class, the layer class and the layer-set class) to record their arguments, then taking, for
each variable, the first non-overlay filled layer of the layer set that shows it. `fixtures/spc-site/readout-samples.json` has the viewer's read-out formats (units and digits), used
to check that its units are ours. To refresh after the viewer changes, repeat the capture and run:

```
python3 scripts/gen_spc_colormaps.py > shared/src/commonMain/kotlin/simplemodelview/SpcColormaps.kt
```

`SpcStyles.kt` applies a table to a product's style (`siteStyle`): bands, the top level, the colors below and above, and the opacity; units, digits and the stored-to-shown factor stay the app's own.
Two exceptions follow the viewer: 10-m hourly maximum wind is shown in mph, and negative helicity is shown as negative values.

## Known details
- 66 variables have a table; 54 of our 121 curated products had none in the static capture (22 are not in the viewer at all, the rest are drawn as contour lines there, for example heights and
  MSLP), and keep the app's own palettes.
- The viewer's 1-h QPF mean has four tables (rain, snow and the other precipitation types, chosen by a mask the app does not use); the app uses the first (rain).
- The viewer's helicity tables contain a typo (`..., 380, 520, 460, 500`). The generator replicates the shader's lookup for such a table and writes the bands it really draws.
- The 3-h and 24-h freezing-rain QPF fields use the ordinary QPF table on the site (the app used a 4-color table before).
