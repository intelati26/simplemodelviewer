// *****************************************************************************
// * This file is part of simplemodelview.  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef GEOTIFF_DECODE_H
#define GEOTIFF_DECODE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// NOAA's National Blend of Models is published (bucket noaa-nbm-pds) as one GeoTIFF per field and forecast hour: a single band of 8 / 16 / 32-bit
// integers or 32-bit floats on a Lambert conformal grid, in 256 x 256 tiles (or strips) compressed with LZW (or deflate, or not at all) and the
// horizontal (2) or floating-point (3) predictor, with the grid's georeferencing in the GeoTIFF tags. Only that is supported: little-endian
// classic TIFF, one sample per pixel, chunky data.
namespace Geotiff {
    struct Info {
        int columns = 0, rows = 0;
        bool hasNodata = false;
        double nodata = 0;
        // the georeferencing: a Lambert conformal conic grid with one standard parallel equal to the origin's latitude
        double parallel = 0, centralMeridian = 0;       // degrees (west-negative longitude)
        double radius = 6371229.0;                      // sphere, meters
        double cellMeters = 0;
        double firstX = 0, firstY = 0;                  // projected position of the south-west cell's center
    };

    // decodes the whole raster into floats, row 0 = south (the file's rows run north to south, so they are flipped), NaN where the cell is the
    // file's nodata value (or a float NaN)
    bool decode(const uint8_t * data, size_t size, std::vector<float>& out, Info& info, std::string& error);

    // exposed for tests: TIFF-flavor LZW (MSB-first codes, early change). Returns false on damaged data. `out` is filled up to `capacity` bytes.
    bool lzwDecode(const uint8_t * in, size_t size, uint8_t * out, size_t capacity, size_t& produced);
}

#endif
