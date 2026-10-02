// *****************************************************************************
// * This file is part of simplemodelview.  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef MRMS_DECODE_H
#define MRMS_DECODE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>
#include "spcrefs_core.h"

// MRMS products are GRIB2 files (usually gzip-compressed) holding one field on a regular latitude / longitude grid, packed with GRIB2
// data representation template 5.41: the data section is an embedded 8- or 16-bit grayscale PNG whose samples X give the value
// (R + X * 2^E) / 10^D. There is no bitmap: "no coverage" / "no echo" are stored codes (-999, -99, -3, ...).
// Only what the MRMS files use is supported (grid template 3.0 scanning north to south and west to east, template 5.41, no bitmap).
namespace Mrms {
    // what the file's sections say
    struct Info {
        int columns = 0, rows = 0;
        double northLat = 0, westLon = 0;   // centre of the first (north-west) cell, degrees, west-negative longitude
        double dLat = 0, dLon = 0;          // degrees per cell
        int64_t validTime = 0;              // epoch seconds
        double reference = 0, scale = 1;    // value = reference + sample * scale
        int bitDepth = 0;
        const uint8_t * png = nullptr;      // the PNG inside the data section (points into the GRIB buffer)
        size_t pngSize = 0;
    };

    // gunzips a buffer that starts with the gzip magic; any other buffer is copied unchanged
    bool gunzip(const uint8_t * data, size_t size, std::vector<uint8_t>& out, std::string& error);

    // reads the sections of an (uncompressed) GRIB2 message
    bool readInfo(const uint8_t * grib, size_t size, Info& info, std::string& error);

    // the whole job: gunzip if needed, read the sections, cut the requested window out of the PNG a row at a time (the full grid is never
    // held in memory) and combine step x step blocks. `out` is result.columns x result.rows floats, row 0 = south, NaN where there is no value.
    bool decode(const uint8_t * data, size_t size, const spc_mrms_request& request, std::vector<float>& out, spc_mrms_result& result,
                std::string& error);
}

#endif
