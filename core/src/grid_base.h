// *****************************************************************************
// * This file is part of simplemodelview (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef GRID_BASE_H
#define GRID_BASE_H

// A grid the rasteriser can draw on: a size and the mapping between latitude / longitude and fractional (column, row) positions.
// Column counts from the west, row from the south; cell centres are at whole numbers.
struct GridBase {
    GridBase(int cols, int rows_) : columns(cols), rows(rows_) {}
    virtual ~GridBase() = default;

    const int columns;
    const int rows;

    // fractional grid position of a latitude / west-negative longitude
    virtual void toGrid(double lat, double lon, double& column, double& row) const = 0;
    // latitude / longitude (east-positive, -180..180) of a fractional grid position
    virtual void toLatLon(double column, double row, double& lat, double& lon) const = 0;
};

#endif
