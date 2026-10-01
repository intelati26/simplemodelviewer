// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************

#include "spcrefs_core.h"
#include <cstring>
#include <string>
#include <vector>
#include "blosc_decode.h"
#include "chunk_decode.h"
#include "lambert_grid.h"
#include "raster.h"

namespace {
    const LambertGrid & grid() {
        static const LambertGrid g;
        return g;
    }
}

extern "C" {

int spc_decode_chunk(const uint8_t * data, size_t size, const char * dtype, int compressed, float * out, size_t count,
                     char * error, size_t error_cap) {
    std::vector<float> values;
    std::string message;
    if (!ChunkDecode::toFloats(data, size, dtype ? dtype : "", compressed != 0, count, values, message)) {
        if (error && error_cap > 0) {
            std::strncpy(error, message.c_str(), error_cap - 1);
            error[error_cap - 1] = 0;
        }
        return -1;
    }
    std::memcpy(out, values.data(), count * sizeof(float));
    return 0;
}

long spc_blosc_inflate(const uint8_t * data, size_t size, uint8_t * out, size_t out_cap, char * error, size_t error_cap) {
    std::vector<unsigned char> bytes;
    std::string message;
    if (!Blosc::decompress(data, size, bytes, message)) {
        if (error && error_cap > 0) {
            std::strncpy(error, message.c_str(), error_cap - 1);
            error[error_cap - 1] = 0;
        }
        return -1;
    }
    if (out != nullptr && out_cap >= bytes.size()) {
        std::memcpy(out, bytes.data(), bytes.size());
    }
    return static_cast<long>(bytes.size());
}

void spc_latlon_to_grid(double lat, double lon, double * column, double * row) {
    grid().toGrid(lat, lon, *column, *row);
}

void spc_grid_to_latlon(double column, double row, double * lat, double * lon) {
    grid().toLatLon(column, row, *lat, *lon);
}

void spc_render_globe(const float * values, const spc_globe * globe, const spc_style * style, uint32_t * argb) {
    Raster::renderGlobe(values, grid(), *globe, *style, argb);
}

void spc_render(const float * values, const spc_view * view, const spc_style * style, uint32_t * argb) {
    Raster::render(values, *view, *style, argb);
}

}
