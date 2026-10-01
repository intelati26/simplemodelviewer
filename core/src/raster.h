// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef RASTER_H
#define RASTER_H
#include "spcrefs_core.h"

namespace Raster {
    // value -> 0xAARRGGBB for a blended or banded style (0 = transparent)
    uint32_t colourFor(const spc_style& style, double shown);
    float sampleAt(const float * values, int w, int h, double column, double row, bool smooth);
    uint32_t shade(const float * values, int w, int h, double column, double row, bool smooth, const spc_style& style);
    void render(const float * values, const spc_view& view, const spc_style& style, uint32_t * argb);
    void renderGlobe(const float * values, const spc_globe& globe, const spc_style& style, uint32_t * argb);
}
#endif
