// Core unit tests: blosc decode (against libblosc when available), dtype conversion, Lambert grid, rasteriser.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include "blosc_decode.h"
#include "chunk_decode.h"
#include "lambert_grid.h"
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
    LambertGrid g;
    double col = 0, row = 0;
    g.toGrid(21.138123, -122.719528, col, row);   // 237.280472 E = -122.719528
    CHECK(std::fabs(col) < 1e-3 && std::fabs(row) < 1e-3);
    double lat = 0, lon = 0;
    g.toLatLon(900.0, 530.0, lat, lon);
    g.toGrid(lat, lon, col, row);
    CHECK(std::fabs(col - 900.0) < 1e-6 && std::fabs(row - 530.0) < 1e-6);
    CHECK(lat > 35 && lat < 45 && lon > -105 && lon < -90);   // the middle of the grid is over the central US
    // the same through the C interface
    spc_grid_to_latlon(900.0, 530.0, &lat, &lon);
    spc_latlon_to_grid(lat, lon, &col, &row);
    CHECK(std::fabs(col - 900.0) < 1e-6);
}

static void testRender() {
    std::vector<float> values(static_cast<size_t>(SPC_GRID_COLUMNS) * SPC_GRID_ROWS, std::nanf(""));
    values[10 * SPC_GRID_COLUMNS + 20] = 45.0f;   // one cell at column 20, row 10 (from the south)
    const spc_stop stops[2] = {{10.0, 0, 0, 255}, {50.0, 255, 0, 0}};
    spc_style style{SPC_BLENDED, stops, 2, 1.0, 0.0, 10.0, nullptr, 0};
    spc_view view{0, 0, 40, 20, 1, 1};
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
    spc_view half{0, 0, 40, 20, 1, 2};
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
    testRender();
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
