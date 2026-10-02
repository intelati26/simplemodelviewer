// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef GRIDS_H
#define GRIDS_H

#include "lambert_grid.h"
#include "spcrefs_core.h"

namespace Grids {
    // the grids of SPC's REFS data (SPC_GRID_REFS) and GREFS probabilities (SPC_GRID_GREFS); an unknown id is REFS
    inline const LambertGrid& get(int id) {
        static const LambertGrid refs{SPC_GRID_COLUMNS, SPC_GRID_ROWS, 3000.0, 38.5, 262.5, 21.138123, 237.280472};
        static const LambertGrid grefs{185, 129, 40635.0, 25.0, 265.0, 12.19, 226.541};
        return id == SPC_GRID_GREFS ? grefs : refs;
    }

    // a latitude / longitude window that surely contains the grid (a cheap reject before the Lambert projection)
    struct Window { double latMin, latMax, lonMin, lonMax; };
    inline Window window(int id) {
        return id == SPC_GRID_GREFS ? Window{0.0, 75.0, -170.0, -20.0} : Window{15.0, 60.0, -140.0, -55.0};
    }
}

#endif
