// Core unit tests: blosc decode (against libblosc when available), dtype conversion, Lambert grid, rasteriser.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include "blosc_decode.h"
#include "chunk_decode.h"
#include "grids.h"
#include "spcrefs_core.h"
#ifdef HAVE_LIBBLOSC
#include <blosc.h>
#endif

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); failures += 1; } } while (0)

static void testHalf() {
    CHECK(ChunkDecode::halfToFloat(0x3C00) == 1.0f);
    CHECK(ChunkDecode::halfToFloat(0xC000) == -2.0f);
    CHECK(ChunkDecode::halfToFloat(0x0000) == 0.0f);
    CHECK(std::isinf(ChunkDecode::halfToFloat(0x7C00)));
    CHECK(std::isnan(ChunkDecode::halfToFloat(0x7E00)));
}

static void testDtypes() {
    const int32_t ints[3] = {1, 1023, -5};
    std::vector<float> out;
    std::string err;
    CHECK(ChunkDecode::toFloats(reinterpret_cast<const unsigned char *>(ints), sizeof ints, "<i4", false, 3, out, err));
    CHECK(out[1] == 1023.0f && out[2] == -5.0f);
    const unsigned char bytes[2] = {1, 4};
    CHECK(ChunkDecode::toFloats(bytes, 2, "|u1", false, 2, out, err) && out[1] == 4.0f);
    CHECK(!ChunkDecode::toFloats(bytes, 2, "<f4", false, 2, out, err));   // too short
    CHECK(!ChunkDecode::toFloats(bytes, 2, "<c16", false, 2, out, err));
}

#ifdef HAVE_LIBBLOSC
static void testBloscRoundTrip(const char * codec, int typesize) {
    blosc_init();
    blosc_set_compressor(codec);
    const size_t n = 1059 * 1799 / 4;   // a few hundred thousand elements: several blocks
    std::vector<unsigned char> src(n * typesize);
    for (size_t i = 0; i < n; i += 1) {
        const double v = 30.0 + 20.0 * std::sin(i * 0.001) + (i % 97 == 0 ? 5.0 : 0.0);
        if (typesize == 2) {
            const uint16_t h = static_cast<uint16_t>(static_cast<int>(v * 16.0));
            std::memcpy(&src[i * 2], &h, 2);
        } else {
            const float f = static_cast<float>(v);
            std::memcpy(&src[i * 4], &f, 4);
        }
    }
    std::vector<unsigned char> packed(src.size() + BLOSC_MAX_OVERHEAD);
    const int c = blosc_compress(5, BLOSC_SHUFFLE, typesize, src.size(), src.data(), packed.data(), packed.size());
    CHECK(c > 0);
    std::vector<unsigned char> out;
    std::string err;
    CHECK(Blosc::decompress(packed.data(), static_cast<size_t>(c), out, err));
    CHECK(out == src);
    if (out != src) {
        std::printf("  codec %s typesize %d: %s\n", codec, typesize, err.c_str());
    }
    blosc_destroy();
}
#endif

static void testLambert() {
    const LambertGrid& g = Grids::get(SPC_GRID_REFS);
    double col = 0, row = 0;
    g.toGrid(21.138123, -122.719528, col, row);   // 237.280472 E = -122.719528
    CHECK(std::fabs(col) < 1e-3 && std::fabs(row) < 1e-3);
    double lat = 0, lon = 0;
    g.toLatLon(900.0, 530.0, lat, lon);
    g.toGrid(lat, lon, col, row);
    CHECK(std::fabs(col - 900.0) < 1e-6 && std::fabs(row - 530.0) < 1e-6);
    CHECK(lat > 35 && lat < 45 && lon > -105 && lon < -90);   // the middle of the grid is over the central US
    // the same through the C interface
    spc_grid_to_latlon(SPC_GRID_REFS, 900.0, 530.0, &lat, &lon);
    spc_latlon_to_grid(SPC_GRID_REFS, lat, lon, &col, &row);
    CHECK(std::fabs(col - 900.0) < 1e-6);
}

// The GREFS / REFSCT grid (NCEP 212): five points of the REFSCT store's own latitude / longitude arrays (row, column, lat, east lon).
// (The GREFS store's own arrays are inconsistent with its data; see lambert_grid.h.)
static void testGrefsGrid() {
    CHECK(spc_grid_columns(SPC_GRID_GREFS) == 185 && spc_grid_rows(SPC_GRID_GREFS) == 129);
    CHECK(spc_grid_columns(SPC_GRID_REFS) == 1799 && spc_grid_rows(SPC_GRID_REFS) == 1059);
    const double ref[5][4] = {{0, 0, 12.190000, 226.541000}, {0, 184, 14.334820, 294.907906}, {128, 0, 54.535355, 207.144893},
                              {128, 184, 57.289180, 310.613416}, {64, 92, 40.605433, 259.444789}};
    for (const auto & p : ref) {
        double col = 0, row = 0;
        spc_latlon_to_grid(SPC_GRID_GREFS, p[2], p[3] - 360.0, &col, &row);
        if (!(std::fabs(col - p[1]) < 0.02 && std::fabs(row - p[0]) < 0.02)) {
            std::printf("  grid point (%g,%g): got (%.4f,%.4f)\n", p[0], p[1], row, col);
        }
        CHECK(std::fabs(col - p[1]) < 0.02 && std::fabs(row - p[0]) < 0.02);
        double lat = 0, lon = 0;
        spc_grid_to_latlon(SPC_GRID_GREFS, p[1], p[0], &lat, &lon);
        CHECK(std::fabs(lat - p[2]) < 0.02 && std::fabs(std::fmod(lon + 360.0, 360.0) - p[3]) < 0.02);
    }
}

static void testGlobe() {
    // a uniform field over the whole grid, viewed from above Kansas: the centre pixel is inside the grid and coloured
    std::vector<float> values(static_cast<size_t>(SPC_GRID_COLUMNS) * SPC_GRID_ROWS, 40.0f);
    const spc_stop stops[2] = {{10.0, 0, 0, 255}, {50.0, 255, 0, 0}};
    spc_style style{SPC_BLENDED, stops, 2, 1.0, 0.0, 10.0, nullptr, 0};
    spc_globe globe{SPC_GRID_REFS, 200, 200, 90.0, 38.5, -98.0, 1, 0x40FFFFFFu};
    std::vector<uint32_t> img(200 * 200, 0xDEADBEEF);
    spc_render_globe(values.data(), &globe, &style, img.data());
    CHECK((img[100 * 200 + 100] >> 24) == 0xFF);        // centre: data
    CHECK(img[2 * 200 + 2] == 0);                       // corner: outside the sphere
    for (uint32_t p : img) { CHECK(p != 0xDEADBEEF); }
    // looking at the far side of the earth: nothing over the grid
    spc_globe away{SPC_GRID_REFS, 200, 200, 90.0, -38.5, 82.0, 1, 0x40FFFFFFu};
    spc_render_globe(values.data(), &away, &style, img.data());
    CHECK(img[100 * 200 + 100] == 0);
    // coarse preview fills the same pixels in blocks
    spc_globe coarse{SPC_GRID_REFS, 200, 200, 90.0, 38.5, -98.0, 4, 0x40FFFFFFu};
    spc_render_globe(values.data(), &coarse, &style, img.data());
    CHECK(img[100 * 200 + 100] == img[101 * 200 + 101]);
    // NaN data inside the grid -> the domain colour
    std::vector<float> none(values.size(), std::nanf(""));
    spc_render_globe(none.data(), &globe, &style, img.data());
    CHECK(img[100 * 200 + 100] == 0x40FFFFFFu);
}

static void testRender() {
    std::vector<float> values(static_cast<size_t>(SPC_GRID_COLUMNS) * SPC_GRID_ROWS, std::nanf(""));
    values[10 * SPC_GRID_COLUMNS + 20] = 45.0f;   // one cell at column 20, row 10 (from the south)
    const spc_stop stops[2] = {{10.0, 0, 0, 255}, {50.0, 255, 0, 0}};
    spc_style style{SPC_BLENDED, stops, 2, 1.0, 0.0, 10.0, nullptr, 0};
    spc_view view{SPC_GRID_REFS, 0, 0, 40, 20, 1, 1};
    std::vector<uint32_t> img(40 * 20, 0xDEADBEEF);
    spc_render(values.data(), &view, &style, img.data());
    // north at the top: row 10 of 20 shown rows is image row 20 - 1 - 10 = 9
    const uint32_t px = img[9 * 40 + 20];
    CHECK((px >> 24) == 0xFF);
    CHECK(((px >> 16) & 255) > 200 && (px & 255) < 60);   // 45 is mostly red on a blue-to-red ramp
    CHECK(img[0] == 0);                                    // NaN stays transparent

    // data saver: every 2nd cell -> half-size picture (it samples the odd cells), so move the one value to column 21 / row 11
    values[10 * SPC_GRID_COLUMNS + 20] = std::nanf("");
    values[11 * SPC_GRID_COLUMNS + 21] = 45.0f;
    spc_view half{SPC_GRID_REFS, 0, 0, 40, 20, 1, 2};
    std::vector<uint32_t> small(20 * 10, 0xDEADBEEF);
    spc_render(values.data(), &half, &style, small.data());
    int painted = 0;
    for (uint32_t p : small) { CHECK(p != 0xDEADBEEF); painted += (p >> 24) == 0xFF; }
    CHECK(painted == 1);

    // paintball: members 0 and 2 over the threshold
    values[11 * SPC_GRID_COLUMNS + 21] = std::nanf("");
    values[10 * SPC_GRID_COLUMNS + 20] = 5.0f;
    const uint32_t members[3] = {0x000000, 0x00FF00, 0xFF0000};
    spc_style pb{SPC_PAINTBALL, nullptr, 0, 1.0, 0.0, -1e30, members, 3};
    spc_render(values.data(), &view, &pb, img.data());
    CHECK((img[9 * 40 + 20] >> 24) == 0xFF && ((img[9 * 40 + 20] >> 16) & 255) > 100);
}

int main() {
    testHalf();
    testDtypes();
    testLambert();
    testGrefsGrid();
    testRender();
    testGlobe();
#ifdef HAVE_LIBBLOSC
    for (const char * codec : {"lz4", "zstd"}) {
        testBloscRoundTrip(codec, 2);
        testBloscRoundTrip(codec, 4);
    }
#else
    std::printf("libblosc not found: skipping the blosc round-trip tests\n");
#endif
    std::printf(failures == 0 ? "all core tests passed\n" : "%d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
