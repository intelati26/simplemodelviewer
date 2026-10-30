// *****************************************************************************
// * This file is part of simplemodelview (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************
#include "grids.h"
#include <algorithm>
#include <cmath>
#include <deque>
#include <memory>
#include <mutex>
#include <tuple>
#include <vector>
#include "latlon_grid.h"

namespace Grids {
    namespace {
        std::mutex lock;
        // a deque of owned grids: references stay valid as it grows. `key` is the definition (equal definitions share an id).
        struct Entry {
            std::unique_ptr<GridBase> grid;
            Window win;
            std::vector<double> key;
        };
        std::deque<Entry> dynamic;

        const Entry * dynamicEntry(int id) {
            std::lock_guard<std::mutex> guard(lock);
            const int index = id - SPC_GRID_FIRST_DYNAMIC;
            return index >= 0 && index < static_cast<int>(dynamic.size()) ? &dynamic[static_cast<size_t>(index)] : nullptr;
        }

        int addEntry(std::unique_ptr<GridBase> grid, Window win, std::vector<double> key) {
            std::lock_guard<std::mutex> guard(lock);
            for (size_t i = 0; i < dynamic.size(); i += 1) {
                if (dynamic[i].key == key) return SPC_GRID_FIRST_DYNAMIC + static_cast<int>(i);
            }
            dynamic.push_back(Entry{std::move(grid), win, std::move(key)});
            return SPC_GRID_FIRST_DYNAMIC + static_cast<int>(dynamic.size()) - 1;
        }

        constexpr double kParallel = 38.5, kCentral = 262.5;   // REFS's projection

        // the lat / lon box of a grid: its cells' extent, sampled along the edges, with a margin
        Window boundsOf(const GridBase& g) {
            double latMin = 90, latMax = -90, lonMin = 1e9, lonMax = -1e9;
            const int n = 40;
            for (int i = 0; i <= n; i += 1) {
                for (int side = 0; side < 4; side += 1) {
                    const double t = static_cast<double>(i) / n;
                    const double c = side < 2 ? t * (g.columns - 1) : (side == 2 ? 0.0 : g.columns - 1.0);
                    const double r = side < 2 ? (side == 0 ? 0.0 : g.rows - 1.0) : t * (g.rows - 1);
                    double lat, lon;
                    g.toLatLon(c, r, lat, lon);
                    latMin = std::min(latMin, lat); latMax = std::max(latMax, lat);
                    lonMin = std::min(lonMin, lon); lonMax = std::max(lonMax, lon);
                }
            }
            return Window{latMin - 0.5, latMax + 0.5, lonMin - 0.5, lonMax + 0.5};
        }

        int defineLambert(int cols, int rows, double cell, double parallel, double central, double firstLat, double firstLon) {
            auto grid = std::make_unique<LambertGrid>(cols, rows, cell, parallel, central, firstLat, firstLon);
            const Window win = boundsOf(*grid);
            return addEntry(std::move(grid), win, {2.0, static_cast<double>(cols), static_cast<double>(rows), cell, parallel, central, firstLat, firstLon});
        }
    }

    const GridBase& get(int id) {
        static const LambertGrid refs{SPC_GRID_COLUMNS, SPC_GRID_ROWS, 3000.0, 38.5, 262.5, 21.138123, 237.280472};
        static const LambertGrid grefs{185, 129, 40635.0, 25.0, 265.0, 12.19, 226.541};
        if (id >= SPC_GRID_FIRST_DYNAMIC) {
            if (const Entry * e = dynamicEntry(id)) return *e->grid;
        }
        return id == SPC_GRID_GREFS ? grefs : refs;
    }

    int defineLambertProjected(int columns, int rows, double cellMeters, double parallel, double centralMeridian, double firstX, double firstY, double radius) {
        auto grid = std::make_unique<LambertGrid>(columns, rows, cellMeters, parallel, centralMeridian, LambertGrid::Projected{firstX, firstY}, radius);
        const Window win = boundsOf(*grid);
        return addEntry(std::move(grid), win, {3.0, static_cast<double>(columns), static_cast<double>(rows), cellMeters, parallel, centralMeridian, firstX, firstY, radius});
    }

    int defineLatLon(int columns, int rows, double southLat, double westLon, double dLat, double dLon) {
        const Window win{southLat - dLat, southLat + (rows + 1) * dLat, westLon - dLon, westLon + (columns + 1) * dLon};
        return addEntry(std::make_unique<LatLonGrid>(columns, rows, southLat, westLon, dLat, dLon), win,
                        {1.0, static_cast<double>(columns), static_cast<double>(rows), southLat, westLon, dLat, dLon});
    }

    int planLambertBox(double west, double south, double east, double north, double cellMeters, int * columns, int * rows) {
        const LambertGrid ref{1, 1, cellMeters, kParallel, kCentral, 21.138123, 237.280472};   // only for its projection
        double cMin = 1e18, cMax = -1e18, rMin = 1e18, rMax = -1e18;
        const int n = 60;
        for (int i = 0; i <= n; i += 1) {
            const double t = static_cast<double>(i) / n;
            const double lons[4] = {west + t * (east - west), west + t * (east - west), west, east};
            const double lats[4] = {south, north, south + t * (north - south), south + t * (north - south)};
            for (int k = 0; k < 4; k += 1) {
                double c, r;
                ref.toGrid(lats[k], lons[k], c, r);
                cMin = std::min(cMin, c); cMax = std::max(cMax, c); rMin = std::min(rMin, r); rMax = std::max(rMax, r);
            }
        }
        double lat, lon;
        ref.toLatLon(cMin, rMin, lat, lon);
        *columns = std::max(2, static_cast<int>(std::ceil(cMax - cMin)) + 1);
        *rows = std::max(2, static_cast<int>(std::ceil(rMax - rMin)) + 1);
        return defineLambert(*columns, *rows, cellMeters, kParallel, kCentral, lat, lon);
    }

    int planLambertCrop(int baseId, double colMin, double rowMin, double colMax, double rowMax, double cellMeters, int * columns, int * rows) {
        const auto * base = dynamic_cast<const LambertGrid *>(&get(baseId));
        if (base == nullptr) return -1;
        // base cell edges are half a cell out from the centers; the first new center sits half a new cell inside the corner
        const double c0 = colMin - 0.5, r0 = rowMin - 0.5;
        const double widthM = (colMax - colMin + 1.0) * base->cell, heightM = (rowMax - rowMin + 1.0) * base->cell;
        *columns = std::max(2, static_cast<int>(std::ceil(widthM / cellMeters)));
        *rows = std::max(2, static_cast<int>(std::ceil(heightM / cellMeters)));
        const double half = 0.5 * cellMeters / base->cell;      // in base cells
        double lat, lon;
        base->toLatLon(c0 + half, r0 + half, lat, lon);
        return defineLambert(*columns, *rows, cellMeters, base->parallel, base->centralMeridian, lat, lon);
    }

    Window window(int id) {
        if (id >= SPC_GRID_FIRST_DYNAMIC) {
            if (const Entry * e = dynamicEntry(id)) return e->win;
        }
        return id == SPC_GRID_GREFS ? Window{0.0, 75.0, -170.0, -20.0} : Window{15.0, 60.0, -140.0, -55.0};
    }
}
