// Core unit tests: blosc decode (against libblosc when available), dtype conversion, Lambert grid, rasteriser.
#include <cmath>
#include <cstdio>
#include <algorithm>
#include <cstring>
#include <fstream>
#include <iterator>
#include <limits>
#include <string>
#include <vector>
#include "blosc_decode.h"
#include "chunk_decode.h"
#include "grids.h"
#include "mrms_decode.h"
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
    const GridBase& g = Grids::get(SPC_GRID_REFS);
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
    // a uniform field over the whole grid, viewed from above Kansas: the center pixel is inside the grid and colored
    std::vector<float> values(static_cast<size_t>(SPC_GRID_COLUMNS) * SPC_GRID_ROWS, 40.0f);
    const spc_stop stops[2] = {{10.0, 0, 0, 255}, {50.0, 255, 0, 0}};
    spc_style style{SPC_BLENDED, stops, 2, 1.0, 0.0, 10.0, nullptr, 0, 1e30, 1.0};
    spc_globe globe{SPC_GRID_REFS, 200, 200, 90.0, 38.5, -98.0, 1, 0x40FFFFFFu};
    std::vector<uint32_t> img(200 * 200, 0xDEADBEEF);
    spc_render_globe(values.data(), &globe, &style, img.data());
    CHECK((img[100 * 200 + 100] >> 24) == 0xFF);        // center: data
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
    // NaN data inside the grid -> the domain color
    std::vector<float> none(values.size(), std::nanf(""));
    spc_render_globe(none.data(), &globe, &style, img.data());
    CHECK(img[100 * 200 + 100] == 0x40FFFFFFu);
}

static void testRender() {
    std::vector<float> values(static_cast<size_t>(SPC_GRID_COLUMNS) * SPC_GRID_ROWS, std::nanf(""));
    values[10 * SPC_GRID_COLUMNS + 20] = 45.0f;   // one cell at column 20, row 10 (from the south)
    const spc_stop stops[2] = {{10.0, 0, 0, 255}, {50.0, 255, 0, 0}};
    spc_style style{SPC_BLENDED, stops, 2, 1.0, 0.0, 10.0, nullptr, 0, 1e30, 1.0};
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
    spc_style pb{SPC_PAINTBALL, nullptr, 0, 1.0, 0.0, -1e30, members, 3, 1e30, 1.0};
    spc_render(values.data(), &view, &pb, img.data());
    CHECK((img[9 * 40 + 20] >> 24) == 0xFF && ((img[9 * 40 + 20] >> 16) & 255) > 100);
}


// ---- MRMS: GRIB2 / PNG decoding, checked against GDAL ----
// The fixtures are 120 x 80 windows cut from real MRMS files and re-packed as valid GRIB2 messages (scripts/make_mrms_fixtures.py); the
// .expected.f32 files hold what GDAL decodes from the FULL original file (row 0 = north), so the reference is an independent decoder.
static std::vector<uint8_t> readFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

struct Fixture {
    const char * name;
    double missingAtOrBelow;
    int pool;
    bool wide;   // 16-bit PNG
};

static const Fixture fixtures[] = {
    {"MergedReflectivityQCComposite_00.50", -90.0, 0, true},   // dBZ: -999 no coverage, -99 no echo
    {"RotationTrack60min_00.50", -0.5, 0, false},              // 8-bit PNG
    {"MultiSensor_QPE_24H_Pass2_00.00", -0.5, 1, true},        // mm: -3 no coverage
    {"FLASH_QPE_FFG01H_00.00", -500.0, 0, true},               // 24 bits: an 8-bit RGB PNG
    {"FLASH_HP_MAXSTREAMFLOW_00.00", -500.0, 1, true},         // 32 bits: an 8-bit RGBA PNG
    {"PrecipFlag_00.00", -0.5, 2, false},                      // category codes (1, 3, 6, 7, 10, 91, 96): pooled by the most common value
};

// (GDAL does the scaling in float32, so with a large offset such as -9999 its values are off by up to about 1e-3)
static bool near(float a, float b) { return std::fabs(a - b) <= 2e-3 + 1e-6 * std::fabs(b); }

static void testMrmsFixture(const Fixture& fx) {
    const std::string base = std::string(FIXTURE_DIR) + "/" + fx.name;
    const std::vector<uint8_t> gz = readFile(base + ".grib2.gz");
    const std::vector<uint8_t> raw = readFile(base + ".expected.f32");
    CHECK(!gz.empty() && raw.size() == 120 * 80 * 4);
    if (gz.empty() || raw.size() != 120 * 80 * 4) return;
    std::vector<float> expected(120 * 80);
    std::memcpy(expected.data(), raw.data(), raw.size());

    // the whole window, every cell
    spc_mrms_request req{-180.0, 0.0, 0.0, 90.0, 1, fx.pool, fx.missingAtOrBelow};
    std::vector<float> out;
    spc_mrms_result res{};
    std::string err;
    CHECK(Mrms::decode(gz.data(), gz.size(), req, out, res, err));
    CHECK(res.columns == 120 && res.rows == 80);
    CHECK(res.valid_time > 1700000000);
    if (res.columns != 120 || res.rows != 80) { std::printf("  (%s: %s)\n", fx.name, err.c_str()); return; }
    int mismatches = 0, finite = 0;
    for (int r = 0; r < 80; r += 1) {
        for (int c = 0; c < 120; c += 1) {
            const float e = expected[static_cast<size_t>(r) * 120 + c];
            const float v = out[static_cast<size_t>(79 - r) * 120 + c];   // out: row 0 = south
            if (e <= fx.missingAtOrBelow) { if (!std::isnan(v)) mismatches += 1; }
            else { finite += 1; if (!near(v, e)) mismatches += 1; }
        }
    }
    CHECK(mismatches == 0);
    CHECK(finite > 50);   // the window has real data in it

    // the output grid's coordinates: a regular lat / lon grid whose cell centers match the file's (checked against gdalinfo's origin)
    double lat = 0, lon = 0;
    spc_grid_to_latlon(res.grid, 0, 0, &lat, &lon);
    const double dLat = res.source_d_lat;
    const double dLon = res.source_d_lon;
    double c = 0, r = 0;
    spc_latlon_to_grid(res.grid, lat, lon, &c, &r);
    CHECK(std::fabs(c) < 1e-9 && std::fabs(r) < 1e-9);
    double lat2 = 0, lon2 = 0;
    spc_grid_to_latlon(res.grid, 119, 79, &lat2, &lon2);
    CHECK(std::fabs((lon2 - lon) - 119 * dLon) < 1e-9 && std::fabs((lat2 - lat) - 79 * dLat) < 1e-9);

    // combined blocks: independent max / mean over the GDAL values
    for (int step : {2, 3}) {
        spc_mrms_request q{-180.0, 0.0, 0.0, 90.0, step, fx.pool, fx.missingAtOrBelow};
        std::vector<float> o;
        spc_mrms_result rr{};
        CHECK(Mrms::decode(gz.data(), gz.size(), q, o, rr, err));
        CHECK(rr.columns == 120 / step && rr.rows == 80 / step);
        int bad = 0;
        for (int oy = 0; oy < rr.rows; oy += 1) {
            for (int ox = 0; ox < rr.columns; ox += 1) {
                double best = -1e30, sum = 0; int n = 0;
                int modeCount = 0; double modeValue = 0;
                for (int dy = 0; dy < step; dy += 1) for (int dx = 0; dx < step; dx += 1) {
                    const float e = expected[static_cast<size_t>(oy * step + dy) * 120 + ox * step + dx];
                    if (e <= fx.missingAtOrBelow) continue;
                    best = std::max<double>(best, e); sum += e; n += 1;
                    int same = 0;
                    for (int ey = 0; ey < step; ey += 1) for (int ex = 0; ex < step; ex += 1)
                        same += expected[static_cast<size_t>(oy * step + ey) * 120 + ox * step + ex] == e;
                    if (same > modeCount || (same == modeCount && e > modeValue)) { modeCount = same; modeValue = e; }
                }
                const float v = o[static_cast<size_t>(rr.rows - 1 - oy) * rr.columns + ox];
                if (n == 0) { if (!std::isnan(v)) bad += 1; }
                else if (!near(v, static_cast<float>(fx.pool == 1 ? sum / n : fx.pool == 2 ? modeValue : best))) bad += 1;
            }
        }
        CHECK(bad == 0);
        // a block's center moves by half a source cell per extra step
        double la = 0, lo = 0;
        spc_grid_to_latlon(rr.grid, 0, 0, &la, &lo);
        CHECK(std::fabs(lo - (lon + (step - 1) / 2.0 * dLon)) < 1e-9);
    }

    // a sub-window picked by a latitude / longitude box (the box edges sit on cell centers)
    if (std::strncmp(fx.name, "MergedReflectivity", 18) == 0) {
        spc_mrms_request q{-81.495, 24.105, -81.005, 24.345, 1, fx.pool, fx.missingAtOrBelow};
        std::vector<float> o;
        spc_mrms_result rr{};
        CHECK(Mrms::decode(gz.data(), gz.size(), q, o, rr, err));
        CHECK(rr.columns == 50 && rr.rows == 25);
        if (rr.columns == 50 && rr.rows == 25) {
            int bad = 0;
            for (int row = 0; row < 25; row += 1) for (int col = 0; col < 50; col += 1) {
                const float e = expected[static_cast<size_t>(20 + (24 - row)) * 120 + 50 + col];   // source rows 20..44 from the north
                const float v = o[static_cast<size_t>(row) * 50 + col];
                if (e <= fx.missingAtOrBelow ? !std::isnan(v) : !near(v, e)) bad += 1;
            }
            CHECK(bad == 0);
        }
    }

    // draw it: the number of colored pixels is the number of cells at or above the hide threshold
    const spc_stop stops[2] = {{0.0, 255, 0, 0}, {1000.0, 0, 0, 255}};
    const spc_style style{SPC_BLENDED, stops, 2, 1.0, 0.0, 0.0, nullptr, 0, 1e30, 1.0};
    const spc_view view{res.grid, 0, 0, 120, 80, 1, 1};
    std::vector<uint32_t> argb(120 * 80);
    spc_render(out.data(), &view, &style, argb.data());
    int colored = 0, wanted = 0;
    for (size_t i = 0; i < argb.size(); i += 1) {
        if (argb[i] != 0) colored += 1;
        if (!std::isnan(out[i]) && out[i] >= 0.0f) wanted += 1;
    }
    CHECK(colored == wanted && colored > 0);
}

// category codes are drawn exactly (never blended) in the color of the stop with that value
static void testCategorical() {
    const spc_stop stops[3] = {{1.0, 10, 20, 30}, {6.0, 200, 0, 0}, {91.0, 0, 255, 0}};
    const spc_style style{SPC_CATEGORICAL, stops, 3, 1.0, 0.0, -1e30, nullptr, 0, 1e30, 1.0};
    const float values[8] = {0.0f, 1.0f, 6.0f, 91.0f, 3.0f, std::nanf(""), 6.0f, 1.0f};   // 4 x 2
    const int grid = spc_define_latlon_grid(4, 2, 30.0, -100.0, 0.01, 0.01);
    const spc_view view{grid, 0, 0, 4, 2, 3, 1};   // 3 pixels per cell: a smoothing renderer would blend the codes
    std::vector<uint32_t> argb(12 * 6);
    spc_render(values, &view, &style, argb.data());
    auto at = [&](int cx, int cyFromSouth) { return argb[static_cast<size_t>(5 - (cyFromSouth * 3 + 1)) * 12 + cx * 3 + 1]; };
    CHECK(at(0, 0) == 0);                          // code 0: not in the legend
    CHECK(at(1, 0) == 0xFF0A141Eu);                // code 1
    CHECK(at(2, 0) == 0xFFC80000u);                // code 6
    CHECK(at(3, 0) == 0xFF00FF00u);                // code 91
    CHECK(at(0, 1) == 0);                          // code 3: not in the legend, and not blended from its neighbors
    CHECK(at(1, 1) == 0);                          // NaN
    CHECK(at(2, 1) == 0xFFC80000u && at(3, 1) == 0xFF0A141Eu);
    for (uint32_t c : argb) CHECK(c == 0 || c == 0xFF0A141Eu || c == 0xFFC80000u || c == 0xFF00FF00u);   // no in-between colors anywhere
}

// a style can leave values above its top transparent and draw everything with an opacity (the SPC site's tables do both)
static void testOpacityAndTop() {
    const spc_stop stops[2] = {{10.0, 0, 0, 255}, {20.0, 255, 0, 0}};
    const int grid = spc_define_latlon_grid(4, 1, 30.0, -100.0, 0.01, 0.01);
    const float values[4] = {5.0f, 15.0f, 25.0f, 100.0f};
    std::vector<uint32_t> argb(4);
    const spc_view view{grid, 0, 0, 4, 1, 1, 1};
    const spc_style opaque{SPC_BANDED, stops, 2, 1.0, 0.0, -1e30, nullptr, 0, 1e30, 1.0};
    spc_render(values, &view, &opaque, argb.data());
    CHECK(argb[0] == 0 && argb[1] == 0xFF0000FFu && argb[2] == 0xFFFF0000u && argb[3] == 0xFFFF0000u);   // below the first stop: nothing; above the last: its color
    const spc_style capped{SPC_BANDED, stops, 2, 1.0, 0.0, -1e30, nullptr, 0, 50.0, 1.0};              // nothing above 50
    spc_render(values, &view, &capped, argb.data());
    CHECK(argb[2] == 0xFFFF0000u && argb[3] == 0);
    const spc_style faint{SPC_BANDED, stops, 2, 1.0, 0.0, -1e30, nullptr, 0, 1e30, 0.9};              // 90 % opacity: alpha 230, the color unchanged
    spc_render(values, &view, &faint, argb.data());
    CHECK(argb[0] == 0 && argb[1] == 0xE60000FFu && argb[3] == 0xE6FF0000u);
}

static void testMrmsErrors() {
    std::vector<float> out;
    spc_mrms_result res{};
    std::string err;
    spc_mrms_request req{-180.0, 0.0, 0.0, 90.0, 1, 0, -90.0};
    const uint8_t junk[40] = {1, 2, 3};
    CHECK(!Mrms::decode(junk, sizeof junk, req, out, res, err));
    CHECK(!Mrms::decode(junk, 0, req, out, res, err));
    const std::vector<uint8_t> gz = readFile(std::string(FIXTURE_DIR) + "/MergedReflectivityQCComposite_00.50.grib2.gz");
    CHECK(!gz.empty());
    // a cut-off download must fail cleanly, never read past the end
    CHECK(!Mrms::decode(gz.data(), gz.size() / 2, req, out, res, err));
    CHECK(!Mrms::decode(gz.data(), 10, req, out, res, err));
    // an area that does not overlap the file's grid
    spc_mrms_request away{10.0, 10.0, 20.0, 20.0, 1, 0, -90.0};
    CHECK(!Mrms::decode(gz.data(), gz.size(), away, out, res, err));
    // damaged PNG bytes: flip bytes through the data section of the unpacked message
    std::vector<uint8_t> grib;
    CHECK(Mrms::gunzip(gz.data(), gz.size(), grib, err));
    for (size_t i = grib.size() - 300; i < grib.size() - 5; i += 7) grib[i] ^= 0xA5;
    const bool decoded = Mrms::decode(grib.data(), grib.size(), req, out, res, err);
    (void)decoded;   // may fail or give wrong numbers; it must simply not crash
    // dynamic grids are shared: the same definition gives the same id
    const int a = spc_define_latlon_grid(10, 20, 30.0, -100.0, 0.01, 0.01);
    CHECK(a >= SPC_GRID_FIRST_DYNAMIC && a == spc_define_latlon_grid(10, 20, 30.0, -100.0, 0.01, 0.01));
    CHECK(a != spc_define_latlon_grid(10, 20, 30.0, -100.0, 0.02, 0.02));
    CHECK(spc_grid_columns(a) == 10 && spc_grid_rows(a) == 20);
}

static void testMrms() {
    for (const Fixture& fx : fixtures) testMrmsFixture(fx);
    testMrmsErrors();
}

static void testReproject() {
    // a lat / lon field value = 10 * lat + lon over 30..45 N, 105..85 W (0.05 degrees), put on a Lambert grid
    const int cols = 401, rows = 301;
    const int src = spc_define_latlon_grid(cols, rows, 30.0, -105.0, 0.05, 0.05);
    std::vector<float> values(static_cast<size_t>(cols) * rows);
    for (int r = 0; r < rows; r += 1) for (int c = 0; c < cols; c += 1) values[static_cast<size_t>(r) * cols + c] = static_cast<float>(10.0 * (30.0 + r * 0.05) + (-105.0 + c * 0.05));
    int lc = 0, lr = 0;
    const int dst = spc_plan_lambert_box(-104.0, 31.0, -86.0, 44.0, 3000.0, &lc, &lr);
    CHECK(dst >= SPC_GRID_FIRST_DYNAMIC && lc > 100 && lr > 100);
    CHECK(dst == spc_plan_lambert_box(-104.0, 31.0, -86.0, 44.0, 3000.0, &lc, &lr));   // the same definition, the same grid
    std::vector<float> out(static_cast<size_t>(lc) * lr);
    spc_reproject(values.data(), src, dst, out.data());
    int inside = 0;
    for (int r = 0; r < lr; r += 17) for (int c = 0; c < lc; c += 17) {
        double lat, lon;
        spc_grid_to_latlon(dst, c, r, &lat, &lon);
        const float v = out[static_cast<size_t>(r) * lc + c];
        if (std::isnan(v)) continue;
        inside += 1;
        CHECK(std::fabs(v - (10.0 * lat + lon)) < 0.35);          // nearest cell of a 0.05 degree grid
    }
    CHECK(inside > 100);
    // the box's corners are inside the planned grid and the grid's own cells map back onto themselves
    double c0, r0;
    spc_latlon_to_grid(dst, 44.0, -104.0, &c0, &r0);
    CHECK(c0 >= -1.0 && c0 <= lc && r0 >= -1.0 && r0 <= lr);
    // a crop: its cell (0, 0) sits where the base's cell (col_min, row_min) is, for the same cell size
    int cc = 0, cr = 0;
    const int crop = spc_plan_lambert_crop(dst, 10.0, 20.0, 59.0, 69.0, 3000.0, &cc, &cr);
    CHECK(crop >= SPC_GRID_FIRST_DYNAMIC && cc == 50 && cr == 50);
    double a, b, a2, b2;
    spc_grid_to_latlon(crop, 0.0, 0.0, &a, &b);
    spc_grid_to_latlon(dst, 10.0, 20.0, &a2, &b2);
    CHECK(std::fabs(a - a2) < 1e-6 && std::fabs(b - b2) < 1e-6);
    int none = 0, nr = 0;
    CHECK(spc_plan_lambert_crop(src, 0, 0, 5, 5, 3000.0, &none, &nr) == -1);   // a lat / lon grid has no Lambert crop
}

static void testReprojectKeepsSingleCells() {
    // isolated one-cell features (a strike, a thin track) must survive: every one of them lands in at least one Lambert cell
    const int cols = 601, rows = 401;
    const double dLat = 0.05, dLon = 0.05;
    const int src = spc_define_latlon_grid(cols, rows, 30.0, -105.0, dLat, dLon);
    std::vector<float> values(static_cast<size_t>(cols) * rows, std::nanf(""));
    int placed = 0;
    for (int r = 5; r < rows - 5; r += 7) for (int c = 5; c < cols - 5; c += 7) { values[static_cast<size_t>(r) * cols + c] = static_cast<float>(1 + placed); placed += 1; }
    const double cell = 111000.0 * dLat * std::min(1.0, std::cos(37.5 * 3.14159265358979 / 180.0)) * 0.95;     // as the app chooses it
    int lc = 0, lr = 0;
    const int dst = spc_plan_lambert_box(-104.0, 31.0, -76.0, 49.0, cell, &lc, &lr);
    std::vector<float> out(static_cast<size_t>(lc) * lr);
    spc_reproject(values.data(), src, dst, out.data());
    std::vector<char> seen(static_cast<size_t>(placed) + 2, 0);
    for (float v : out) if (!std::isnan(v)) seen[static_cast<size_t>(v)] = 1;
    // inside the planned box (away from its curved edges) nothing may be lost
    int inBox = 0, lost = 0, id = 0;
    for (int r = 5; r < rows - 5; r += 7) for (int c = 5; c < cols - 5; c += 7) {
        id += 1;
        const double lat = 30.0 + r * dLat, lon = -105.0 + c * dLon;
        if (lat > 32.0 && lat < 47.5 && lon > -102.0 && lon < -79.0) { inBox += 1; if (!seen[static_cast<size_t>(id)]) lost += 1; }
    }
    CHECK(inBox > 100);
    CHECK(lost == 0);
}

int main() {
    testHalf();
    testDtypes();
    testLambert();
    testGrefsGrid();
    testRender();
    testGlobe();
    testMrms();
    testCategorical();
    testOpacityAndTop();
    testReproject();
    testReprojectKeepsSingleCells();
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
