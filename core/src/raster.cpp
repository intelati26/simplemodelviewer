// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************

#include "raster.h"
#include <algorithm>
#include <cmath>
#include "grids.h"

namespace Raster {
    namespace {
        uint32_t argb(int r, int g, int b) {
            return 0xFF000000u | (static_cast<uint32_t>(r) << 16) | (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
        }
    }

    uint32_t colorFor(const spc_style& p, double shown) {
        if (std::isnan(shown) || shown < p.hide_below || shown > p.hide_above || p.stop_count <= 0 || p.stops == nullptr) {
            return 0;
        }
        const spc_stop * stops = p.stops;
        const int n = p.stop_count;
        if (p.kind == SPC_BANDED) {
            if (shown < stops[0].value - 1e-9) {
                return 0;
            }
            int band = 0;
            while (band + 1 < n && shown >= stops[band + 1].value - 1e-9) {
                band += 1;
            }
            return argb(stops[band].r, stops[band].g, stops[band].b);
        }
        if (shown <= stops[0].value) {
            return argb(stops[0].r, stops[0].g, stops[0].b);
        }
        for (int i = 1; i < n; i += 1) {
            if (shown <= stops[i].value) {
                const double t = (shown - stops[i - 1].value) / (stops[i].value - stops[i - 1].value);
                return argb(static_cast<int>(stops[i - 1].r + t * (stops[i].r - stops[i - 1].r)),
                            static_cast<int>(stops[i - 1].g + t * (stops[i].g - stops[i - 1].g)),
                            static_cast<int>(stops[i - 1].b + t * (stops[i].b - stops[i - 1].b)));
            }
        }
        return argb(stops[n - 1].r, stops[n - 1].g, stops[n - 1].b);
    }

    float sampleAt(const float * values, int w, int h, double column, double row, bool smooth) {
        if (!smooth) {
            const int c = std::clamp(static_cast<int>(std::lround(column)), 0, w - 1);
            const int r = std::clamp(static_cast<int>(std::lround(row)), 0, h - 1);
            return values[static_cast<size_t>(r) * w + c];
        }
        const double cc = std::clamp(column, 0.0, w - 1.0);
        const double rr = std::clamp(row, 0.0, h - 1.0);
        const int c0 = std::min(static_cast<int>(cc), w - 2);
        const int r0 = std::min(static_cast<int>(rr), h - 2);
        const double fx = cc - c0;
        const double fy = rr - r0;
        const float a = values[static_cast<size_t>(r0) * w + c0];
        const float b = values[static_cast<size_t>(r0) * w + c0 + 1];
        const float c = values[static_cast<size_t>(r0 + 1) * w + c0];
        const float d = values[static_cast<size_t>(r0 + 1) * w + c0 + 1];
        if (std::isnan(a) || std::isnan(b) || std::isnan(c) || std::isnan(d)) {
            return fx < 0.5 ? (fy < 0.5 ? a : c) : (fy < 0.5 ? b : d);
        }
        return static_cast<float>((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy);
    }

    namespace {
        uint32_t shadeOpaque(const float * values, int w, int h, double column, double row, bool smooth, const spc_style& style);
    }

    // the cell's color with the style's opacity applied (a transparent cell stays transparent)
    uint32_t shade(const float * values, int w, int h, double column, double row, bool smooth, const spc_style& style) {
        const uint32_t c = shadeOpaque(values, w, h, column, row, smooth, style);
        if (c == 0 || style.opacity >= 1.0) {
            return c;
        }
        const uint32_t alpha = static_cast<uint32_t>(std::lround(255.0 * std::clamp(style.opacity, 0.0, 1.0)));
        return alpha == 0 ? 0u : (alpha << 24) | (c & 0x00FFFFFFu);
    }

    namespace {
    uint32_t shadeOpaque(const float * values, int w, int h, double column, double row, bool smooth, const spc_style& style) {
        if (style.kind == SPC_CATEGORICAL) {
            const float raw = sampleAt(values, w, h, column, row, false);   // category codes are never interpolated
            if (std::isnan(raw) || style.stops == nullptr) {
                return 0;
            }
            const double shown = raw * style.factor + style.offset;
            for (int i = 0; i < style.stop_count; i += 1) {
                if (std::fabs(shown - style.stops[i].value) < 0.5) {
                    return argb(style.stops[i].r, style.stops[i].g, style.stops[i].b);
                }
            }
            return 0;
        }
        if (style.kind == SPC_PAINTBALL) {
            const float raw = sampleAt(values, w, h, column, row, false);
            const int mask = std::isnan(raw) ? 0 : static_cast<int>(raw);
            if (mask == 0) {
                return 0;
            }
            double r = 255, g = 255, b = 255;
            const double alpha = 0.55;   // each member paints over the ones before it
            for (int bit = 0; bit < 10; bit += 1) {
                if (mask & (1 << bit)) {
                    const uint32_t c = bit < style.member_count && style.member_rgb ? style.member_rgb[bit] : 0x808080u;
                    r = r * (1 - alpha) + ((c >> 16) & 255) * alpha;
                    g = g * (1 - alpha) + ((c >> 8) & 255) * alpha;
                    b = b * (1 - alpha) + (c & 255) * alpha;
                }
            }
            return argb(static_cast<int>(r), static_cast<int>(g), static_cast<int>(b));
        }
        const float raw = sampleAt(values, w, h, column, row, smooth);
        return colorFor(style, std::isnan(raw) ? std::nan("") : raw * style.factor + style.offset);
    }
    }   // namespace

    void render(const float * values, const spc_view& view, const spc_style& style, uint32_t * out) {
        const GridBase& gridInfo = Grids::get(view.grid);
        const int gw = gridInfo.columns;
        const int gh = gridInfo.rows;
        const int step = std::max(view.step, 1);
        const int scale = step > 1 ? 1 : std::max(view.scale, 1);
        const int outW = view.cols * scale / step;
        const int outH = view.rows * scale / step;
        for (int y = 0; y < outH; y += 1) {
            const double row = view.row0 + view.rows - (y + 0.5) * step / scale - 0.5;   // north at the top
            for (int x = 0; x < outW; x += 1) {
                const double column = view.col0 + (x + 0.5) * step / scale - 0.5;
                out[static_cast<size_t>(y) * outW + x] = shade(values, gw, gh, column, row, scale > 1, style);
            }
        }
    }

    void renderGlobe(const float * values, const spc_globe& globe, const spc_style& style, uint32_t * out) {
        const GridBase& grid = Grids::get(globe.grid);
        const Grids::Window win = Grids::window(globe.grid);
        const double pi = 3.14159265358979323846;
        const double lat0 = globe.center_lat * pi / 180.0;
        const double lon0 = globe.center_lon * pi / 180.0;
        const double sinLat0 = std::sin(lat0);
        const double cosLat0 = std::cos(lat0);
        const double cx = globe.width / 2.0;
        const double cy = globe.height / 2.0;
        const int stride = std::max(globe.stride, 1);   // >1: coarse preview while the globe is being dragged (stride x stride blocks)
        const int blocksX = (globe.width + stride - 1) / stride;
        const int blocksY = (globe.height + stride - 1) / stride;
        for (int by = 0; by < blocksY; by += 1) {
            for (int bx = 0; bx < blocksX; bx += 1) {
                const double px = bx * stride + stride / 2.0;
                const double py = by * stride + stride / 2.0;
                const double x = (px - cx) / globe.radius;
                const double y = (cy - py) / globe.radius;
                const double rho2 = x * x + y * y;
                uint32_t color = 0;
                if (rho2 <= 1.0) {
                    const double rho = std::sqrt(rho2);
                    const double c = std::asin(rho);
                    const double sinC = std::sin(c);
                    const double cosC = std::cos(c);
                    double lat = lat0;
                    double lon = lon0;
                    if (rho > 1e-12) {
                        lat = std::asin(cosC * sinLat0 + y * sinC * cosLat0 / rho);
                        lon = lon0 + std::atan2(x * sinC, rho * cosLat0 * cosC - y * sinLat0 * sinC);
                    }
                    const double latDeg = lat * 180.0 / pi;
                    if (latDeg > win.latMin && latDeg < win.latMax) {   // quick reject before the Lambert projection
                        double lonDeg = std::fmod(lon * 180.0 / pi + 540.0, 360.0) - 180.0;
                        if (lonDeg > win.lonMin && lonDeg < win.lonMax) {
                            double column = 0.0, row = 0.0;
                            grid.toGrid(latDeg, lonDeg, column, row);
                            if (column >= 0.0 && column <= grid.columns - 1 && row >= 0.0 && row <= grid.rows - 1) {
                                color = shade(values, grid.columns, grid.rows, column, row, true, style);
                                if (color == 0) {
                                    color = globe.domain_argb;   // inside the REFS grid but nothing to show
                                }
                            }
                        }
                    }
                }
                for (int dy = 0; dy < stride; dy += 1) {
                    const int yy = by * stride + dy;
                    if (yy >= globe.height) break;
                    for (int dx = 0; dx < stride; dx += 1) {
                        const int xx = bx * stride + dx;
                        if (xx >= globe.width) break;
                        out[static_cast<size_t>(yy) * globe.width + xx] = color;
                    }
                }
            }
        }
    }
}
