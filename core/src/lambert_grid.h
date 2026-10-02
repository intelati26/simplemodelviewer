// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// * See the COPYING file for the full license text.
// *****************************************************************************

#ifndef LAMBERTGRID_H
#define LAMBERTGRID_H

#include <cmath>
#include <utility>

// A Lambert conformal conic grid with one standard parallel on a sphere of radius 6371229 m (the grids of SPC's REFS data).
// REFS: 1799 x 1059 points, 3 km, parallel 38.5, central meridian 262.5 E, first (south-west) point 21.138123 N 237.280472 E; the
// formulas reproduce the store's own latitude / longitude arrays to better than 1e-10 degrees. GREFS / REFSCT: 185 x 129 points on NCEP
// grid 212 as the stores' GRIB attributes state it (Lambert conformal, 40.635 km, parallel 25, central meridian 265 E, first point
// 12.19 N 226.541 E). The REFSCT stores' latitude / longitude arrays agree with it. The GREFS store's own latitude / longitude arrays do NOT
// (they follow a different mapping), and its data do not follow them either: correlating GREFS wind probability with REFS storm
// reflectivity gives ~0.37 on this grid and ~0.08 on the arrays' mapping. Use these parameters, not that store's coordinate arrays.
struct LambertGrid {
    LambertGrid(int cols, int rows, double cellMetres, double parallelDeg, double centralMeridianDeg, double firstLat, double firstLon)
        : columns(cols), rows(rows), cell(cellMetres), lon0Rad(degrees(centralMeridianDeg)) {
        const double phi1 = degrees(parallelDeg);
        n = std::sin(phi1);
        f = std::cos(phi1) * std::pow(std::tan(pi / 4.0 + phi1 / 2.0), n) / n;
        rho0 = rho(phi1);
        const auto first = project(firstLat, firstLon);
        x0 = first.first;
        y0 = first.second;
    }

    const int columns;
    const int rows;
    const double cell;   // metres

    // fractional grid position (column from the west, row from the south) of a latitude / west-negative longitude
    void toGrid(double lat, double lon, double& column, double& row) const {
        const auto xy = project(lat, lon);
        column = (xy.first - x0) / cell;
        row = (xy.second - y0) / cell;
    }

    // latitude / longitude (east-positive, -180..180) of a fractional grid position
    void toLatLon(double column, double row, double& lat, double& lon) const {
        const double x = x0 + column * cell;
        const double y = y0 + row * cell;
        const double r = std::hypot(x, rho0 - y);
        const double theta = std::atan2(x, rho0 - y);
        lat = (2.0 * std::atan(std::pow(radius * f / r, 1.0 / n)) - pi / 2.0) * 180.0 / pi;
        lon = (lon0Rad + theta / n) * 180.0 / pi;
        lon = std::fmod(lon + 540.0, 360.0) - 180.0;
    }

private:
    static constexpr double pi = 3.14159265358979323846;
    static constexpr double radius = 6371229.0;
    static double degrees(double d) { return d * pi / 180.0; }
    double rho(double phi) const { return radius * f / std::pow(std::tan(pi / 4.0 + phi / 2.0), n); }
    std::pair<double, double> project(double lat, double lon) const {
        double dl = degrees(lon) - lon0Rad;
        dl = std::fmod(dl + 3.0 * pi, 2.0 * pi) - pi;
        const double r = rho(degrees(lat));
        const double theta = n * dl;
        return {r * std::sin(theta), rho0 - r * std::cos(theta)};
    }
    double lon0Rad{0.0};
    double n{0.0};
    double f{0.0};
    double rho0{0.0};
    double x0{0.0};
    double y0{0.0};
};

#endif  // LAMBERTGRID_H
