// Core unit tests: blosc decode (against libblosc when available), dtype conversion, Lambert grid, rasteriser.
#include <cmath>
#include <cstdio>
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

    // the output grid's coordinates: a regular lat / lon grid whose cell centres match the file's (checked against gdalinfo's origin)
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
                for (int dy = 0; dy < step; dy += 1) for (int dx = 0; dx < step; dx += 1) {
                    const float e = expected[static_cast<size_t>(oy * step + dy) * 120 + ox * step + dx];
                    if (e <= fx.missingAtOrBelow) continue;
                    best = std::max<double>(best, e); sum += e; n += 1;
                }
                const float v = o[static_cast<size_t>(rr.rows - 1 - oy) * rr.columns + ox];
                if (n == 0) { if (!std::isnan(v)) bad += 1; }
                else if (!near(v, static_cast<float>(fx.pool == 1 ? sum / n : best))) bad += 1;
            }
        }
        CHECK(bad == 0);
        // a block's centre moves by half a source cell per extra step
        double la = 0, lo = 0;
        spc_grid_to_latlon(rr.grid, 0, 0, &la, &lo);
        CHECK(std::fabs(lo - (lon + (step - 1) / 2.0 * dLon)) < 1e-9);
    }

    // a sub-window picked by a latitude / longitude box (the box edges sit on cell centres)
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

    // draw it: the number of coloured pixels is the number of cells at or above the hide threshold
    const spc_stop stops[2] = {{0.0, 255, 0, 0}, {1000.0, 0, 0, 255}};
    const spc_style style{SPC_BLENDED, stops, 2, 1.0, 0.0, 0.0, nullptr, 0};
    const spc_view view{res.grid, 0, 0, 120, 80, 1, 1};
    std::vector<uint32_t> argb(120 * 80);
    spc_render(out.data(), &view, &style, argb.data());
    int coloured = 0, wanted = 0;
    for (size_t i = 0; i < argb.size(); i += 1) {
        if (argb[i] != 0) coloured += 1;
        if (!std::isnan(out[i]) && out[i] >= 0.0f) wanted += 1;
    }
    CHECK(coloured == wanted && coloured > 0);
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

int main() {
    testHalf();
    testDtypes();
    testLambert();
    testGrefsGrid();
    testRender();
    testGlobe();
    testMrms();
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
