// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************

#include "spcrefs_core.h"
#include <cstring>
#include <string>
#include <vector>
#include "blosc_decode.h"
#include "chunk_decode.h"
#include <cstdlib>
#include "grids.h"
#include "mrms_decode.h"
#include "raster.h"

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

int spc_grid_columns(int grid) { return Grids::get(grid).columns; }
int spc_grid_rows(int grid) { return Grids::get(grid).rows; }

void spc_latlon_to_grid(int grid, double lat, double lon, double * column, double * row) {
    Grids::get(grid).toGrid(lat, lon, *column, *row);
}

void spc_grid_to_latlon(int grid, double column, double row, double * lat, double * lon) {
    Grids::get(grid).toLatLon(column, row, *lat, *lon);
}

int spc_define_latlon_grid(int columns, int rows, double south_lat, double west_lon, double d_lat, double d_lon) {
    return Grids::defineLatLon(columns, rows, south_lat, west_lon, d_lat, d_lon);
}

int spc_mrms_decode(const uint8_t * data, size_t size, const spc_mrms_request * request, float ** out, spc_mrms_result * result,
                    char * error, size_t error_cap) {
    std::vector<float> values;
    std::string message;
    if (!Mrms::decode(data, size, *request, values, *result, message)) {
        if (error && error_cap > 0) {
            std::strncpy(error, message.c_str(), error_cap - 1);
            error[error_cap - 1] = 0;
        }
        return -1;
    }
    float * copy = static_cast<float *>(std::malloc(values.size() * sizeof(float)));
    if (copy == nullptr) {
        if (error && error_cap > 0) std::strncpy(error, "out of memory", error_cap - 1);
        return -1;
    }
    std::memcpy(copy, values.data(), values.size() * sizeof(float));
    *out = copy;
    return 0;
}

void spc_free(void * pointer) { std::free(pointer); }

void spc_render_globe(const float * values, const spc_globe * globe, const spc_style * style, uint32_t * argb) {
    Raster::renderGlobe(values, *globe, *style, argb);
}

void spc_render(const float * values, const spc_view * view, const spc_style * style, uint32_t * argb) {
    Raster::render(values, *view, *style, argb);
}

}
