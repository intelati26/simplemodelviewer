// *****************************************************************************
// * This file is part of simplemodelview (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************
#include "grids.h"
#include <deque>
#include <mutex>
#include "latlon_grid.h"

namespace Grids {
    namespace {
        std::mutex lock;
        std::deque<LatLonGrid> dynamic;   // a deque: references stay valid as it grows

        const LatLonGrid * dynamicGrid(int id) {
            std::lock_guard<std::mutex> guard(lock);
            const int index = id - SPC_GRID_FIRST_DYNAMIC;
            return index >= 0 && index < static_cast<int>(dynamic.size()) ? &dynamic[static_cast<size_t>(index)] : nullptr;
        }
    }

    const GridBase& get(int id) {
        static const LambertGrid refs{SPC_GRID_COLUMNS, SPC_GRID_ROWS, 3000.0, 38.5, 262.5, 21.138123, 237.280472};
        static const LambertGrid grefs{185, 129, 40635.0, 25.0, 265.0, 12.19, 226.541};
        if (id >= SPC_GRID_FIRST_DYNAMIC) {
            if (const LatLonGrid * g = dynamicGrid(id)) return *g;
        }
        return id == SPC_GRID_GREFS ? grefs : refs;
    }

    int defineLatLon(int columns, int rows, double southLat, double westLon, double dLat, double dLon) {
        std::lock_guard<std::mutex> guard(lock);
        for (size_t i = 0; i < dynamic.size(); i += 1) {
            const LatLonGrid& g = dynamic[i];
            if (g.columns == columns && g.rows == rows && g.southLat == southLat && g.westLon == westLon && g.dLat == dLat && g.dLon == dLon) {
                return SPC_GRID_FIRST_DYNAMIC + static_cast<int>(i);
            }
        }
        dynamic.emplace_back(columns, rows, southLat, westLon, dLat, dLon);
        return SPC_GRID_FIRST_DYNAMIC + static_cast<int>(dynamic.size()) - 1;
    }

    Window window(int id) {
        if (id >= SPC_GRID_FIRST_DYNAMIC) {
            if (const LatLonGrid * g = dynamicGrid(id)) {
                return Window{g->southLat - g->dLat, g->southLat + (g->rows + 1) * g->dLat, g->westLon - g->dLon, g->westLon + (g->columns + 1) * g->dLon};
            }
        }
        return id == SPC_GRID_GREFS ? Window{0.0, 75.0, -170.0, -20.0} : Window{15.0, 60.0, -140.0, -55.0};
    }
}
