// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************
#ifndef RASTER_H
#define RASTER_H
#include "spcrefs_core.h"

namespace Raster {
    // value -> 0xAARRGGBB for a blended or banded style (0 = transparent)
    uint32_t colourFor(const spc_style& style, double shown);
    float sampleAt(const float * values, double column, double row, bool smooth);
    void render(const float * values, const spc_view& view, const spc_style& style, uint32_t * argb);
}
#endif
