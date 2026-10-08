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

    // registers a Lambert conformal grid (REFS's projection and sphere) covering the lat / lon box with square cells of `cellMeters`
    // (window edges follow the box in the projection, so the cut is a curved quadrilateral). Returns its id.
    int planLambertBox(double west, double south, double east, double north, double cellMeters, int * columns, int * rows);

    // registers a grid of the projection of `baseId` (a Lambert grid) covering base cells colMin..colMax x rowMin..rowMax (fractional, cell edges
    // at half numbers) with cells of `cellMeters`. Returns its id, or -1 when `baseId` is not a Lambert grid.
    int planLambertCrop(int baseId, double colMin, double rowMin, double colMax, double rowMax, double cellMeters, int * columns, int * rows);

    // a latitude / longitude window that surely contains the grid (a cheap reject before the projection)
    struct Window { double latMin, latMax, lonMin, lonMax; };
    Window window(int id);
}

#endif
