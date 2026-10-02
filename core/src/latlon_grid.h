// *****************************************************************************
// * This file is part of simplemodelview (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef LATLON_GRID_H
#define LATLON_GRID_H

#include <cmath>
#include "grid_base.h"

// A regular latitude / longitude grid (MRMS): cell centers at southLat + row * dLat and westLon + column * dLon (degrees, row 0 = south,
// west-negative longitudes). Used for windows cut out of (and decimated from) the 0.01 / 0.005 degree MRMS grids.
struct LatLonGrid : GridBase {
    LatLonGrid(int cols, int rows_, double southLat_, double westLon_, double dLat_, double dLon_)
        : GridBase(cols, rows_), southLat(southLat_), westLon(westLon_), dLat(dLat_), dLon(dLon_) {}

    const double southLat, westLon, dLat, dLon;

    void toGrid(double lat, double lon, double& column, double& row) const override {
        double d = lon - westLon;
        if (d < -180.0) d += 360.0;
        if (d > 180.0) d -= 360.0;
        column = d / dLon;
        row = (lat - southLat) / dLat;
    }

    void toLatLon(double column, double row, double& lat, double& lon) const override {
        lat = southLat + row * dLat;
        lon = std::fmod(westLon + column * dLon + 540.0, 360.0) - 180.0;
    }
};

#endif
