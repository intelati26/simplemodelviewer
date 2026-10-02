// *****************************************************************************
// * This file is part of simplemodelview.  Licensed under the GNU General Public License v3.
// *****************************************************************************
#include "mrms_decode.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <zlib.h>
#include "grids.h"

namespace Mrms {
    namespace {
        uint32_t be32(const uint8_t * p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]); }
        uint16_t be16(const uint8_t * p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }

        // GRIB2 signed integers are sign and magnitude, not two's complement
        int32_t signMagnitude32(const uint8_t * p) {
            const uint32_t v = be32(p);
            return (v & 0x80000000u) ? -static_cast<int32_t>(v & 0x7FFFFFFFu) : static_cast<int32_t>(v);
        }
        int signMagnitude16(const uint8_t * p) {
            const int v = be16(p);
            return (v & 0x8000) ? -(v & 0x7FFF) : v;
        }

        int64_t daysFromCivil(int y, int m, int d) {
            y -= m <= 2;
            const int64_t era = (y >= 0 ? y : y - 399) / 400;
            const int64_t yoe = y - era * 400;
            const int64_t doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
            const int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
            return era * 146097 + doe - 719468;
        }

        const size_t kMaxInflated = size_t(512) << 20;   // sanity limit for gunzip
        const long long kMaxOutputCells = 60000000;      // a decoded window larger than this is refused (240 MB)
    }

    bool gunzip(const uint8_t * data, size_t size, std::vector<uint8_t>& out, std::string& error) {
        if (size < 2 || data[0] != 0x1f || data[1] != 0x8b) {
            out.assign(data, data + size);
            return true;
        }
        z_stream z{};
        if (inflateInit2(&z, 16 + MAX_WBITS) != Z_OK) { error = "zlib init failed"; return false; }
        z.next_in = const_cast<Bytef *>(data);
        z.avail_in = static_cast<uInt>(size);
        out.clear();
        out.resize(std::max<size_t>(size * 2, 1 << 16));
        size_t have = 0;
        int rc = Z_OK;
        while (rc == Z_OK) {
            if (have == out.size()) {
                if (out.size() >= kMaxInflated) { inflateEnd(&z); error = "gzip data too large"; return false; }
                out.resize(out.size() * 2);
            }
            z.next_out = out.data() + have;
            z.avail_out = static_cast<uInt>(out.size() - have);
            rc = inflate(&z, Z_NO_FLUSH);
            have = out.size() - z.avail_out;
        }
        inflateEnd(&z);
        if (rc != Z_STREAM_END) { error = "the gzip data is damaged"; return false; }
        out.resize(have);
        return true;
    }

    bool readInfo(const uint8_t * grib, size_t size, Info& info, std::string& error) {
        if (size < 16 + 4 || std::memcmp(grib, "GRIB", 4) != 0) { error = "not a GRIB file"; return false; }
        if (grib[7] != 2) { error = "not GRIB edition 2"; return false; }
        size_t pos = 16;
        bool haveTime = false, haveGrid = false, haveRepr = false, haveData = false;
        double r = 0; int e = 0, d = 0;
        while (pos + 5 <= size && std::memcmp(grib + pos, "7777", 4) != 0) {
            const size_t length = be32(grib + pos);
            const int section = grib[pos + 4];
            if (length < 5 || pos + length > size) { error = "damaged GRIB section"; return false; }
            const uint8_t * s = grib + pos;
            if (section == 1 && length >= 19) {
                const int year = be16(s + 12);
                info.validTime = daysFromCivil(year, s[14], s[15]) * 86400 + s[16] * 3600 + s[17] * 60 + s[18];
                haveTime = true;
            } else if (section == 3 && length >= 72) {
                if (be16(s + 12) != 0) { error = "unsupported grid template (need a regular latitude / longitude grid)"; return false; }
                info.columns = static_cast<int>(be32(s + 30));
                info.rows = static_cast<int>(be32(s + 34));
                const uint32_t basic = be32(s + 38), subdivisions = be32(s + 42);
                const double unit = (basic == 0 || subdivisions == 0 || subdivisions == 0xFFFFFFFFu) ? 1e-6 : double(basic) / double(subdivisions);
                const double la1 = signMagnitude32(s + 46) * unit;
                double lo1 = be32(s + 50) * unit;
                if (lo1 > 180.0) lo1 -= 360.0;
                info.dLon = be32(s + 63) * unit;
                info.dLat = be32(s + 67) * unit;
                const int scanning = s[71];
                if (scanning != 0) { error = "unsupported scanning mode (need west to east, north to south)"; return false; }
                info.northLat = la1;
                info.westLon = lo1;
                if (info.columns <= 0 || info.rows <= 0 || info.dLat <= 0 || info.dLon <= 0) { error = "bad grid definition"; return false; }
                haveGrid = true;
            } else if (section == 5 && length >= 20) {
                if (be16(s + 9) != 41) { error = "unsupported data packing (need PNG, template 5.41)"; return false; }
                float ref;
                const uint32_t bits = be32(s + 11);
                std::memcpy(&ref, &bits, 4);
                r = ref;
                e = signMagnitude16(s + 15);
                d = signMagnitude16(s + 17);
                info.bitDepth = s[19];
                haveRepr = true;
            } else if (section == 6) {
                if (s[5] != 255) { error = "unsupported: a bitmap section is present"; return false; }
            } else if (section == 7) {
                info.png = s + 5;
                info.pngSize = length - 5;
                haveData = true;
            }
            pos += length;
        }
        if (!(haveTime && haveGrid && haveRepr && haveData)) { error = "the GRIB message is missing a section"; return false; }
        info.reference = r / std::pow(10.0, d);
        info.scale = std::pow(2.0, e) / std::pow(10.0, d);
        return true;
    }

    namespace {
        // Streams the rows of a grayscale PNG: each call to next() yields the unfiltered samples of one row (as bytes, 1 or 2 per sample).
        class PngRows {
        public:
            bool open(const uint8_t * png, size_t size, int expectColumns, int expectRows, int expectBits, std::string& error) {
                static const uint8_t signature[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
                if (size < 8 || std::memcmp(png, signature, 8) != 0) { error = "the data section is not a PNG"; return false; }
                size_t pos = 8;
                bool header = false;
                while (pos + 12 <= size) {
                    const size_t length = be32(png + pos);
                    const char * type = reinterpret_cast<const char *>(png + pos + 4);
                    if (pos + 12 + length > size) { error = "damaged PNG chunk"; return false; }
                    const uint8_t * body = png + pos + 8;
                    if (std::memcmp(type, "IHDR", 4) == 0 && length >= 13) {
                        width = static_cast<int>(be32(body));
                        height = static_cast<int>(be32(body + 4));
                        depth = body[8];
                        const int colourType = body[9];
                        const int interlace = body[12];
                        // grayscale 8 / 16 bit, or 8-bit RGB / RGBA (the files' 24 and 32 bit samples are the pixel's bytes, in order)
                        channels = colourType == 0 ? 1 : colourType == 2 ? 3 : colourType == 6 ? 4 : 0;
                        if (channels == 0 || (depth != 8 && !(depth == 16 && channels == 1)) || interlace != 0) {
                            error = "unsupported PNG (need a plain 8 / 16 bit grayscale or 8-bit RGB / RGBA image)";
                            return false;
                        }
                        header = true;
                    } else if (std::memcmp(type, "IDAT", 4) == 0) {
                        chunks.push_back({body, length});
                    } else if (std::memcmp(type, "IEND", 4) == 0) {
                        break;
                    }
                    pos += 12 + length;
                }
                if (!header || chunks.empty()) { error = "the PNG has no image data"; return false; }
                if (width != expectColumns || height != expectRows || depth * channels != expectBits) { error = "the PNG does not match the GRIB grid"; return false; }
                bytesPerPixel = depth / 8 * channels;
                rowBytes = static_cast<size_t>(width) * bytesPerPixel;
                previous.assign(rowBytes, 0);
                current.assign(rowBytes + 1, 0);
                std::memset(&z, 0, sizeof z);
                if (inflateInit(&z) != Z_OK) { error = "zlib init failed"; return false; }
                started = true;
                return true;
            }

            ~PngRows() { if (started) inflateEnd(&z); }

            // the next row's samples (rowBytes bytes), or nullptr on damaged data
            const uint8_t * next() {
                size_t filled = 0;
                while (filled < rowBytes + 1) {
                    if (z.avail_in == 0) {
                        if (chunkIndex >= chunks.size()) return nullptr;
                        z.next_in = const_cast<Bytef *>(chunks[chunkIndex].first);
                        z.avail_in = static_cast<uInt>(chunks[chunkIndex].second);
                        chunkIndex += 1;
                        continue;
                    }
                    z.next_out = current.data() + filled;
                    z.avail_out = static_cast<uInt>(rowBytes + 1 - filled);
                    const int rc = inflate(&z, Z_NO_FLUSH);
                    filled = rowBytes + 1 - z.avail_out;
                    if (rc != Z_OK && rc != Z_STREAM_END && rc != Z_BUF_ERROR) return nullptr;
                    if (rc == Z_STREAM_END && filled < rowBytes + 1) return nullptr;
                }
                unfilter();
                std::swap(previous, row);
                return previous.data();
            }

            int width = 0, height = 0, depth = 0, channels = 1;

        private:
            void unfilter() {
                row.resize(rowBytes);
                const int filter = current[0];
                const uint8_t * in = current.data() + 1;
                const size_t bpp = static_cast<size_t>(bytesPerPixel);
                for (size_t i = 0; i < rowBytes; i += 1) {
                    const int a = i >= bpp ? row[i - bpp] : 0;
                    const int b = previous[i];
                    const int c = i >= bpp ? previous[i - bpp] : 0;
                    int predictor = 0;
                    switch (filter) {
                        case 1: predictor = a; break;
                        case 2: predictor = b; break;
                        case 3: predictor = (a + b) / 2; break;
                        case 4: {
                            const int p = a + b - c;
                            const int pa = std::abs(p - a), pb = std::abs(p - b), pc = std::abs(p - c);
                            predictor = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                            break;
                        }
                        default: break;
                    }
                    row[i] = static_cast<uint8_t>(in[i] + predictor);
                }
            }

            std::vector<std::pair<const uint8_t *, size_t>> chunks;
            size_t chunkIndex = 0;
            size_t rowBytes = 0;
            int bytesPerPixel = 1;
            std::vector<uint8_t> previous, current, row;
            z_stream z{};
            bool started = false;
        };
    }

    bool decode(const uint8_t * data, size_t size, const spc_mrms_request& req, std::vector<float>& out, spc_mrms_result& result,
                std::string& error) {
        std::vector<uint8_t> grib;
        if (!gunzip(data, size, grib, error)) return false;
        Info info;
        if (!readInfo(grib.data(), grib.size(), info, error)) return false;

        const int step = std::max(req.step, 1);
        // the window in source cells: columns from the west, rows from the north
        auto colOf = [&](double lon) { double d = lon - info.westLon; if (d < -180.0) d += 360.0; return d / info.dLon; };
        auto rowOf = [&](double lat) { return (info.northLat - lat) / info.dLat; };
        if (colOf(req.east) < -0.5 || colOf(req.west) > info.columns - 0.5 || rowOf(req.south) < -0.5 || rowOf(req.north) > info.rows - 0.5) {
            error = "the requested area is outside the file's grid";
            return false;
        }
        const long x0 = std::clamp(static_cast<long>(std::floor(colOf(req.west) + 0.5)), 0L, static_cast<long>(info.columns) - 1);
        const long x1 = std::clamp(static_cast<long>(std::floor(colOf(req.east) + 0.5)), 0L, static_cast<long>(info.columns) - 1);
        const long y0 = std::clamp(static_cast<long>(std::floor(rowOf(req.north) + 0.5)), 0L, static_cast<long>(info.rows) - 1);
        const long y1 = std::clamp(static_cast<long>(std::floor(rowOf(req.south) + 0.5)), 0L, static_cast<long>(info.rows) - 1);
        const long cols = x1 - x0 + 1;
        const long rows = y1 - y0 + 1;
        const long outCols = cols / step;
        const long outRows = rows / step;
        if (x1 < x0 || y1 < y0 || outCols < 1 || outRows < 1) { error = "the requested area is outside the file's grid"; return false; }
        if (outCols * outRows > kMaxOutputCells) { error = "the requested area is too large"; return false; }

        PngRows png;
        if (!png.open(info.png, info.pngSize, info.columns, info.rows, info.bitDepth, error)) return false;

        const int bytes = info.bitDepth / 8;   // 1, 2, 3 or 4 bytes per sample, big-endian
        const double missingAt = req.missing_at_or_below;
        const bool mean = req.pool == 1;
        const bool mode = req.pool == 2;
        out.assign(static_cast<size_t>(outCols) * outRows, std::numeric_limits<float>::quiet_NaN());
        std::vector<float> acc(static_cast<size_t>(outCols));
        std::vector<int> count(static_cast<size_t>(outCols));
        // category codes: keep the block's values and take the most common one
        std::vector<float> block(mode ? static_cast<size_t>(outCols) * step * step : 0, std::numeric_limits<float>::quiet_NaN());
        const long lastRow = y0 + outRows * step;   // rows read: y0 .. lastRow - 1
        for (long y = 0; y < lastRow; y += 1) {
            const uint8_t * row = png.next();
            if (row == nullptr) { error = "the PNG data is damaged"; return false; }
            if (y < y0) continue;
            const long inBlock = (y - y0) % step;
            if (inBlock == 0) {
                std::fill(acc.begin(), acc.end(), mean ? 0.0f : -std::numeric_limits<float>::infinity());
                std::fill(count.begin(), count.end(), 0);
            }
            for (long ox = 0; ox < outCols; ox += 1) {
                for (long sx = 0; sx < step; sx += 1) {
                    const long x = x0 + ox * step + sx;
                    const uint8_t * px = row + bytes * x;
                    uint32_t sample = px[0];
                    for (int b = 1; b < bytes; b += 1) sample = (sample << 8) | px[b];
                    const double value = info.reference + static_cast<double>(sample) * info.scale;
                    if (value <= missingAt) {
                        if (mode) block[(static_cast<size_t>(ox) * step + inBlock) * step + sx] = std::numeric_limits<float>::quiet_NaN();
                        continue;
                    }
                    const float v = static_cast<float>(value);
                    if (mode) {
                        block[(static_cast<size_t>(ox) * step + inBlock) * step + sx] = v;
                        continue;
                    }
                    if (mean) acc[static_cast<size_t>(ox)] += v;
                    else if (v > acc[static_cast<size_t>(ox)]) acc[static_cast<size_t>(ox)] = v;
                    count[static_cast<size_t>(ox)] += 1;
                }
            }
            if (inBlock == step - 1) {
                const long northRow = (y - y0) / step;
                float * dest = out.data() + static_cast<size_t>(outRows - 1 - northRow) * outCols;   // row 0 = south
                for (long ox = 0; ox < outCols; ox += 1) {
                    if (mode) {
                        const float * cell = block.data() + static_cast<size_t>(ox) * step * step;
                        int bestCount = 0;
                        float best = 0;
                        for (long i = 0; i < step * step; i += 1) {
                            if (std::isnan(cell[i])) continue;
                            int c = 0;
                            for (long j = 0; j < step * step; j += 1) c += cell[j] == cell[i];
                            if (c > bestCount || (c == bestCount && cell[i] > best)) { bestCount = c; best = cell[i]; }
                        }
                        if (bestCount > 0) dest[ox] = best;
                        continue;
                    }
                    const int n = count[static_cast<size_t>(ox)];
                    if (n > 0) dest[ox] = mean ? acc[static_cast<size_t>(ox)] / n : acc[static_cast<size_t>(ox)];
                }
            }
        }

        const double dLat = info.dLat * step, dLon = info.dLon * step;
        const double westCentre = info.westLon + (x0 + (step - 1) / 2.0) * info.dLon;
        const double northCentre = info.northLat - (y0 + (step - 1) / 2.0) * info.dLat;
        const double southCentre = northCentre - (outRows - 1) * dLat;
        result.grid = Grids::defineLatLon(static_cast<int>(outCols), static_cast<int>(outRows), southCentre, westCentre, dLat, dLon);
        result.columns = static_cast<int>(outCols);
        result.rows = static_cast<int>(outRows);
        result.valid_time = info.validTime;
        result.source_columns = info.columns;
        result.source_rows = info.rows;
        result.source_d_lat = info.dLat;
        result.source_d_lon = info.dLon;
        return true;
    }
}
