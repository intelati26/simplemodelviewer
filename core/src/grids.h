// *****************************************************************************
// * This file is part of simplemodelview (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef GRIDS_H
#define GRIDS_H

#include "grid_base.h"
#include "lambert_grid.h"
#include "spcrefs_core.h"

namespace Grids {
    // SPC_GRID_REFS and SPC_GRID_GREFS (an unknown id is REFS), or a lat / lon grid made by defineLatLon (ids from SPC_GRID_FIRST_DYNAMIC)
    const GridBase& get(int id);

    // registers a regular lat / lon grid (see LatLonGrid) and returns its id; the same definition returns the same id. Never freed (small)
    int defineLatLon(int columns, int rows, double southLat, double westLon, double dLat, double dLon);

    // a latitude / longitude window that surely contains the grid (a cheap reject before the projection)
    struct Window { double latMin, latMax, lonMin, lonMax; };
    Window window(int id);
}

#endif
