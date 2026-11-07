// *****************************************************************************
// * This file is part of simplemodelview.  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef GRIB_DECODE_H
#define GRIB_DECODE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// A GRIB2 reader for NOAA's National Blend of Models files (bucket noaa-nbm-grib2-pds), one message at a time (a byte range of the file, found with its
// .idx): a Lambert conformal grid with one standard parallel (grid template 3.30) or a regular latitude / longitude grid (3.0, GFS), and simple (5.0) or
// complex packing, with or without spatial differencing (5.2 / 5.3). Anything else is refused with a message.
namespace Grib {
    struct Info {
        int columns = 0, rows = 0;
        double parallel = 0, centralMeridian = 0;       // degrees (west-negative longitude)
        double radius = 6371229.0;                      // sphere, meters
        double cellMeters = 0;
        double firstX = 0, firstY = 0;                  // projected position of the south-west cell's center
        bool latLon = false;                            // a regular latitude / longitude grid (GFS) instead of a Lambert one
        double southLat = 0, westLon = 0, dLat = 0, dLon = 0;   // its south-west cell's center and its spacing, degrees (west-negative longitude; a global grid starts at -180)
        int64_t referenceTime = 0;                      // the run's time (section 1), epoch seconds
        int forecastHours = 0;                          // octet 19-22 of section 4 (when the unit is hours), else 0
        int category = -1, number = -1;                 // parameter category / number (section 4)
        int levelType = -1;                             // type of the first fixed surface: 100 isobaric (value in Pa), 103 height above ground (m), 101 mean sea level, 1 surface, 200 whole atmosphere
        int levelValue = 0;                             // its value, in the unit of that type
        int64_t valuesDecoded = 0;                      // how many of the message's values were decoded (all, unless [partial] was asked and the data stops short)
    };

    // decodes the first message of [data, data + size) into floats, row 0 = south, columns west to east, NaN where the message has no value
    // With [partial], a message cut off after its group tables is decoded as far as the bytes go (whole groups, in scan order: the top rows of a GFS field);
    // the cells after that are NaN and Info::valuesDecoded says how many values there are. Complex packing only.
    bool decode(const uint8_t * data, size_t size, std::vector<float>& out, Info& info, std::string& error, bool partial = false);
}

#endif
