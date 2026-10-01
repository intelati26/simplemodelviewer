// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// * See the COPYING file for the full license text.
// *****************************************************************************

#ifndef LAMBERTGRID_H
#define LAMBERTGRID_H

#include <cmath>
#include <utility>

// The Lambert conformal conic grid of SPC's REFS data (the same 3 km CONUS grid as HRRR): 1799 x 1059 points, standard
// parallels 38.5, central meridian 262.5 E, sphere of radius 6371229 m, first (south-west) point 21.138123 N 237.280472 E.
// The formulas reproduce the latitude / longitude arrays in the Zarr store to better than 1e-10 degrees.
struct LambertGrid {
    static constexpr int columns = 1799;
    static constexpr int rows = 1059;
    static constexpr double cell = 3000.0;   // metres

    LambertGrid() {
        const double phi1 = degrees(38.5);
        n = std::sin(phi1);
        f = std::cos(phi1) * std::pow(std::tan(pi / 4.0 + phi1 / 2.0), n) / n;
        rho0 = rho(phi1);
        const auto first = project(21.138123, 237.280472);
        x0 = first.first;
        y0 = first.second;
    }

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
        lon = (degrees(262.5) + theta / n) * 180.0 / pi;
        lon = std::fmod(lon + 540.0, 360.0) - 180.0;
    }

private:
    static constexpr double pi = 3.14159265358979323846;
    static constexpr double radius = 6371229.0;
    static double degrees(double d) { return d * pi / 180.0; }
    double rho(double phi) const { return radius * f / std::pow(std::tan(pi / 4.0 + phi / 2.0), n); }
    std::pair<double, double> project(double lat, double lon) const {
        double dl = degrees(lon) - degrees(262.5);
        dl = std::fmod(dl + 3.0 * pi, 2.0 * pi) - pi;
        const double r = rho(degrees(lat));
        const double theta = n * dl;
        return {r * std::sin(theta), rho0 - r * std::cos(theta)};
    }
    double n{0.0};
    double f{0.0};
    double rho0{0.0};
    double x0{0.0};
    double y0{0.0};
};

#endif  // LAMBERTGRID_H
