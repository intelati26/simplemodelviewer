// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************

#include "raster.h"
#include <algorithm>
#include <cmath>

namespace Raster {
    namespace {
        uint32_t argb(int r, int g, int b) {
            return 0xFF000000u | (static_cast<uint32_t>(r) << 16) | (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
        }
    }

    uint32_t colourFor(const spc_style& p, double shown) {
        if (std::isnan(shown) || shown < p.hide_below || p.stop_count <= 0 || p.stops == nullptr) {
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

    float sampleAt(const float * values, double column, double row, bool smooth) {
        const int w = SPC_GRID_COLUMNS;
        const int h = SPC_GRID_ROWS;
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

    void render(const float * values, const spc_view& view, const spc_style& style, uint32_t * out) {
        const int scale = std::max(view.scale, 1);
        const int outW = view.cols * scale;
        const int outH = view.rows * scale;
        const bool paintball = style.kind == SPC_PAINTBALL;
        for (int y = 0; y < outH; y += 1) {
            const double row = view.row0 + view.rows - (y + 0.5) / scale - 0.5;   // north at the top
            for (int x = 0; x < outW; x += 1) {
                const double column = view.col0 + (x + 0.5) / scale - 0.5;
                uint32_t & px = out[static_cast<size_t>(y) * outW + x];
                px = 0;
                if (paintball) {
                    const float raw = sampleAt(values, column, row, false);
                    const int mask = std::isnan(raw) ? 0 : static_cast<int>(raw);
                    if (mask == 0) {
                        continue;
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
                    px = argb(static_cast<int>(r), static_cast<int>(g), static_cast<int>(b));
                } else {
                    const float raw = sampleAt(values, column, row, scale > 1);
                    px = colourFor(style, std::isnan(raw) ? std::nan("") : raw * style.factor + style.offset);
                }
            }
        }
    }
}
