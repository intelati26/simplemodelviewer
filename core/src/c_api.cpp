// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************

#include "spcrefs_core.h"
#include <cmath>
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

int spc_plan_lambert_box(double west, double south, double east, double north, double cell_meters, int * columns, int * rows) {
    return Grids::planLambertBox(west, south, east, north, cell_meters, columns, rows);
}

int spc_plan_lambert_crop(int base_grid, double col_min, double row_min, double col_max, double row_max, double cell_meters, int * columns, int * rows) {
    return Grids::planLambertCrop(base_grid, col_min, row_min, col_max, row_max, cell_meters, columns, rows);
}

void spc_reproject(const float * src, int src_grid, int dst_grid, float * dst) {
    const GridBase& from = Grids::get(src_grid);
    const GridBase& to = Grids::get(dst_grid);
    std::vector<unsigned char> sampled(static_cast<size_t>(from.columns) * from.rows, 0);
    for (int r = 0; r < to.rows; r += 1) {
        for (int c = 0; c < to.columns; c += 1) {
            double lat, lon, sc, sr;
            to.toLatLon(c, r, lat, lon);
            from.toGrid(lat, lon, sc, sr);
            const long ic = std::lround(sc), ir = std::lround(sr);
            const bool inside = ic >= 0 && ir >= 0 && ic < from.columns && ir < from.rows;
            if (inside) sampled[static_cast<size_t>(ir) * from.columns + ic] = 1;
            dst[static_cast<size_t>(r) * to.columns + c] = inside ? src[static_cast<size_t>(ir) * from.columns + ic] : std::nanf("");
        }
    }
    // The target is rotated against the source, so nearest-cell sampling can step over a source cell: a thin feature (a strike, a narrow track) would
    // vanish. Every valid source cell nobody sampled is written into its nearest target cell (the larger value wins a clash).
    for (int r = 0; r < from.rows; r += 1) {
        for (int c = 0; c < from.columns; c += 1) {
            const size_t k = static_cast<size_t>(r) * from.columns + c;
            if (sampled[k] || std::isnan(src[k])) continue;
            double lat, lon, tc, tr;
            from.toLatLon(c, r, lat, lon);
            to.toGrid(lat, lon, tc, tr);
            const long ic = std::lround(tc), ir = std::lround(tr);
            if (ic < 0 || ir < 0 || ic >= to.columns || ir >= to.rows) continue;
            float& d = dst[static_cast<size_t>(ir) * to.columns + ic];
            if (std::isnan(d) || src[k] > d) d = src[k];
        }
    }
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
