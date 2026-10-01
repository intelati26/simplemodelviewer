/* SPC REFS Mobile core: plain C interface, no platform APIs. Networking stays on the Kotlin side.
 * Licensed under the GNU General Public License v3 (derived from wxqt). */
#ifndef SPCREFS_CORE_H
#define SPCREFS_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SPC_GRID_COLUMNS 1799
#define SPC_GRID_ROWS 1059

/* Decodes one downloaded Zarr chunk into `count` floats (C order). `compressed` != 0: blosc. Returns 0 on success, otherwise
 * -1 with a message in `error` (up to `error_cap` bytes). */
int spc_decode_chunk(const uint8_t * data, size_t size, const char * dtype, int compressed, float * out, size_t count,
                     char * error, size_t error_cap);

/* Blosc-decompresses a buffer to raw bytes. Returns the decoded size, or -1 on a malformed / unsupported buffer (message in
 * `error`). If `out` is NULL or `out_cap` is too small nothing is written and the needed size is returned. */
long spc_blosc_inflate(const uint8_t * data, size_t size, uint8_t * out, size_t out_cap, char * error, size_t error_cap);

/* Lambert grid: fractional column (from the west) and row (from the south) of a latitude / longitude (east-positive or
 * west-negative both fine), and back. */
void spc_latlon_to_grid(double lat, double lon, double * column, double * row);
void spc_grid_to_latlon(double column, double row, double * lat, double * lon);

/* A window of the grid: cells from col0 / row0 (south-west), `cols` x `rows` cells, drawn `scale` pixels per cell. */
/* `step` > 1 (with scale 1) draws every step-th cell: a half-memory "data saver" picture, (cols/step) x (rows/step) pixels. */
typedef struct {
    int col0, row0, cols, rows, scale, step;
} spc_view;

typedef struct {
    double value;
    uint8_t r, g, b;
} spc_stop;

enum { SPC_BLENDED = 0, SPC_BANDED = 1, SPC_PAINTBALL = 2 };

typedef struct {
    int kind;                 /* SPC_BLENDED / SPC_BANDED / SPC_PAINTBALL */
    const spc_stop * stops;   /* ignored for paintball */
    int stop_count;
    double factor, offset;    /* shown = stored * factor + offset */
    double hide_below;        /* shown values below this stay transparent */
    const uint32_t * member_rgb; /* paintball: 0xRRGGBB per member (bit order), `member_count` entries */
    int member_count;
} spc_style;

/* An orthographic globe: `width` x `height` pixels, a sphere of `radius` pixels centred in the buffer, looking down on
 * (center_lat, center_lon). Only pixels over the REFS grid are drawn (data colours, or `domain_argb` where there is nothing to
 * show); everything else stays transparent (0) for the caller to paint. `stride` > 1 draws coarse stride x stride blocks (fast
 * preview while dragging). */
typedef struct {
    int width, height;
    double radius;
    double center_lat, center_lon;
    int stride;
    uint32_t domain_argb;
} spc_globe;

void spc_render_globe(const float * values, const spc_globe * globe, const spc_style * style, uint32_t * argb);

/* Renders `values` (SPC_GRID_COLUMNS x SPC_GRID_ROWS, row 0 = south) into `argb` ((view.cols*scale/step) x (view.rows*scale/step), north up,
 * 0xAARRGGBB, transparent where nothing is shown). */
void spc_render(const float * values, const spc_view * view, const spc_style * style, uint32_t * argb);

#ifdef __cplusplus
}
#endif
#endif
