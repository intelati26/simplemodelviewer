/* SPC REFS Mobile core: plain C interface, no platform APIs. Networking stays on the Kotlin side.
 * Licensed under the GNU General Public License v3 (derived from wxqt). */
#ifndef SPCREFS_CORE_H
#define SPCREFS_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* The grids of SPC's data: REFS (1799 x 1059, 3 km) and GREFS probabilities (185 x 129, 40.6 km). Ids from SPC_GRID_FIRST_DYNAMIC are
 * regular latitude / longitude grids registered at run time (MRMS windows): see spc_mrms_decode / spc_define_latlon_grid. */
enum { SPC_GRID_REFS = 0, SPC_GRID_GREFS = 1, SPC_GRID_FIRST_DYNAMIC = 2 };
#define SPC_GRID_COLUMNS 1799   /* the REFS grid */
#define SPC_GRID_ROWS 1059

int spc_grid_columns(int grid);
int spc_grid_rows(int grid);

/* Decodes one downloaded Zarr chunk into `count` floats (C order). `compressed` != 0: blosc. Returns 0 on success, otherwise
 * -1 with a message in `error` (up to `error_cap` bytes). */
int spc_decode_chunk(const uint8_t * data, size_t size, const char * dtype, int compressed, float * out, size_t count,
                     char * error, size_t error_cap);

/* Blosc-decompresses a buffer to raw bytes. Returns the decoded size, or -1 on a malformed / unsupported buffer (message in
 * `error`). If `out` is NULL or `out_cap` is too small nothing is written and the needed size is returned. */
long spc_blosc_inflate(const uint8_t * data, size_t size, uint8_t * out, size_t out_cap, char * error, size_t error_cap);

/* Lambert grid: fractional column (from the west) and row (from the south) of a latitude / longitude (east-positive or
 * west-negative both fine), and back. */
void spc_latlon_to_grid(int grid, double lat, double lon, double * column, double * row);
void spc_grid_to_latlon(int grid, double column, double row, double * lat, double * lon);

/* A window of a grid: cells from col0 / row0 (south-west), `cols` x `rows` cells, drawn `scale` pixels per cell. */
/* `step` > 1 (with scale 1) draws every step-th cell: a half-memory "data saver" picture, (cols/step) x (rows/step) pixels. */
typedef struct {
    int grid;
    int col0, row0, cols, rows, scale, step;
} spc_view;

typedef struct {
    double value;
    uint8_t r, g, b;
} spc_stop;

/* SPC_CATEGORICAL: the stored values are category codes: a cell is drawn in the color of the stop whose value equals its (shown) value, else transparent; never blended or smoothed. */
enum { SPC_BLENDED = 0, SPC_BANDED = 1, SPC_PAINTBALL = 2, SPC_CATEGORICAL = 3 };

typedef struct {
    int kind;                 /* SPC_BLENDED / SPC_BANDED / SPC_PAINTBALL / SPC_CATEGORICAL */
    const spc_stop * stops;   /* ignored for paintball */
    int stop_count;
    double factor, offset;    /* shown = stored * factor + offset */
    double hide_below;        /* shown values below this stay transparent */
    const uint32_t * member_rgb; /* paintball: 0xRRGGBB per member (bit order), `member_count` entries */
    int member_count;
    double hide_above;        /* shown values above this stay transparent (1e30: none) */
    double opacity;           /* 0..1: the alpha of everything drawn (1 = opaque) */
} spc_style;

/* An orthographic globe: `width` x `height` pixels, a sphere of `radius` pixels centered in the buffer, looking down on
 * (center_lat, center_lon). Only pixels over the REFS grid are drawn (data colors, or `domain_argb` where there is nothing to
 * show); everything else stays transparent (0) for the caller to paint. `stride` > 1 draws coarse stride x stride blocks (fast
 * preview while dragging). */
typedef struct {
    int grid;
    int width, height;
    double radius;
    double center_lat, center_lon;
    int stride;
    uint32_t domain_argb;
} spc_globe;

void spc_render_globe(const float * values, const spc_globe * globe, const spc_style * style, uint32_t * argb);

/* Renders `values` (the grid's columns x rows, row 0 = south) into `argb` ((view.cols*scale/step) x (view.rows*scale/step), north up,
 * 0xAARRGGBB, transparent where nothing is shown). */
void spc_render(const float * values, const spc_view * view, const spc_style * style, uint32_t * argb);

/* Registers a regular latitude / longitude grid: cell (column, row) is centered at (south_lat + row * d_lat, west_lon + column * d_lon),
 * row 0 = south, west-negative longitudes. Returns its grid id (the same definition gives the same id). */
int spc_define_latlon_grid(int columns, int rows, double south_lat, double west_lon, double d_lat, double d_lon);

/* Reprojection of lat / lon fields (MRMS) onto a Lambert conformal grid (REFS's projection), so the map looks like the REFS one and lines /
 * outlines / contours use the same projection. Each registers a grid (same definition, same id) and reports its size.
 * spc_plan_lambert_box: covers the lat / lon box (west-negative degrees) with square cells of `cell_meters`.
 * spc_plan_lambert_crop: a window of base cells col_min..col_max x row_min..row_max (fractional) of a Lambert grid, with cells of `cell_meters`
 * (the same projection); returns -1 when `base_grid` is a lat / lon grid. */
int spc_plan_lambert_box(double west, double south, double east, double north, double cell_meters, int * columns, int * rows);
int spc_plan_lambert_crop(int base_grid, double col_min, double row_min, double col_max, double row_max, double cell_meters, int * columns, int * rows);

/* Resamples `src` (the columns x rows of `src_grid`) onto `dst_grid` (nearest cell; NaN where the target is outside the source) into `dst`
 * (dst_grid columns x rows floats). */
void spc_reproject(const float * src, int src_grid, int dst_grid, float * dst);

/* ---- MRMS: GRIB2 files with PNG packing (template 5.41) on a regular lat / lon grid (7000 x 3500 at 0.01 degrees, 14000 x 7000 at 0.005) ---- */

typedef struct {
    double west, south, east, north;   /* the box to cut out (degrees, west-negative) */
    int step;                          /* >= 1: every step-th cell in each direction, cells combined by `pool` */
    int pool;                          /* 0 = maximum of the valid cells (reflectivity, rotation), 1 = mean of the valid cells (accumulations), 2 = the most common valid value, ties to the larger (category codes) */
    double missing_at_or_below;        /* stored values <= this (the files' -999 / -99 / -3 codes) become NaN */
} spc_mrms_request;

typedef struct {
    int grid;                          /* the registered lat / lon grid of the output (columns x rows, row 0 = south) */
    int columns, rows;                 /* of the output */
    int64_t valid_time;                /* epoch seconds (the file's reference time) */
    int source_columns, source_rows;   /* of the whole file's grid */
    double source_d_lat, source_d_lon; /* degrees per cell of the file */
} spc_mrms_result;

/* Decodes a (possibly gzip-compressed) MRMS GRIB2 file's window into floats. On success returns 0, `*out` is a malloc'd array of
 * result->columns * result->rows floats (release with spc_free). Otherwise returns -1 with a message in `error`. */
int spc_mrms_decode(const uint8_t * data, size_t size, const spc_mrms_request * request, float ** out, spc_mrms_result * result,
                    char * error, size_t error_cap);

void spc_free(void * pointer);

#ifdef __cplusplus
}
#endif
#endif
