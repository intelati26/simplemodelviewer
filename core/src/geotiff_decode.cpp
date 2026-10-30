// *****************************************************************************
// * This file is part of simplemodelview.  Licensed under the GNU General Public License v3.
// *****************************************************************************
#include "geotiff_decode.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <zlib.h>

namespace Geotiff {
    namespace {
        uint16_t le16(const uint8_t * p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
        uint32_t le32(const uint8_t * p) { return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24); }
        double leDouble(const uint8_t * p) {
            uint64_t v = 0;
            for (int i = 7; i >= 0; i -= 1) v = (v << 8) | p[i];
            double d;
            std::memcpy(&d, &v, 8);
            return d;
        }

        const long long kMaxCells = 60000000;

        struct Entry {
            int type = 0;
            uint32_t count = 0;
            const uint8_t * data = nullptr;   // the values (inline in the entry or at its offset)
            bool present = false;
        };

        int typeSize(int type) {
            switch (type) {
                case 1: case 2: case 6: case 7: return 1;
                case 3: case 8: return 2;
                case 4: case 9: case 11: return 4;
                case 5: case 10: case 12: return 8;
                default: return 0;
            }
        }

        struct Tiff {
            const uint8_t * base = nullptr;
            size_t size = 0;
            std::vector<std::pair<int, Entry>> all;

            const Entry * find(int id) const {
                for (const auto& e : all) if (e.first == id) return &e.second;
                return nullptr;
            }
            // the n-th value of an integer entry (SHORT / LONG), or `fallback`
            uint64_t integer(int id, uint32_t n, uint64_t fallback) const {
                const Entry * e = find(id);
                if (e == nullptr || n >= e->count) return fallback;
                if (e->type == 3) return le16(e->data + 2 * n);
                if (e->type == 4) return le32(e->data + 4 * n);
                return fallback;
            }
            double real(int id, uint32_t n, double fallback) const {
                const Entry * e = find(id);
                if (e == nullptr || n >= e->count || e->type != 12) return fallback;
                return leDouble(e->data + 8 * n);
            }
        };

        bool parse(const uint8_t * data, size_t size, Tiff& t, std::string& error) {
            if (size < 8 || data[0] != 'I' || data[1] != 'I' || le16(data + 2) != 42) {
                error = "not a (little-endian, classic) TIFF file";
                return false;
            }
            const size_t ifd = le32(data + 4);
            if (ifd + 2 > size) { error = "damaged TIFF header"; return false; }
            const size_t n = le16(data + ifd);
            if (ifd + 2 + n * 12 > size) { error = "damaged TIFF directory"; return false; }
            t.base = data;
            t.size = size;
            for (size_t i = 0; i < n; i += 1) {
                const uint8_t * e = data + ifd + 2 + i * 12;
                Entry entry;
                entry.type = le16(e + 2);
                entry.count = le32(e + 4);
                const size_t bytes = size_t(typeSize(entry.type)) * entry.count;
                if (typeSize(entry.type) == 0) continue;
                if (bytes <= 4) {
                    entry.data = e + 8;
                } else {
                    const size_t offset = le32(e + 8);
                    if (offset > size || bytes > size - offset) { error = "damaged TIFF tag"; return false; }
                    entry.data = data + offset;
                }
                entry.present = true;
                t.all.emplace_back(le16(e), entry);
            }
            return true;
        }

        // the GeoTIFF keys: id -> (location, count, value / offset)
        struct GeoKey { int location, count, value; };

        bool geoKey(const Tiff& t, int id, GeoKey& out) {
            const Entry * dir = t.find(34735);
            if (dir == nullptr || dir->type != 3 || dir->count < 4) return false;
            const int keys = le16(dir->data + 6);
            for (int i = 0; i < keys && static_cast<uint32_t>(4 + 4 * i + 4) <= dir->count; i += 1) {
                const uint8_t * k = dir->data + 2 * (4 + 4 * i);
                if (le16(k) == id) {
                    out = {le16(k + 2), le16(k + 4), le16(k + 6)};
                    return true;
                }
            }
            return false;
        }

        // a numeric key: inline (location 0) or from the GeoDoubleParams array (34736)
        bool geoNumber(const Tiff& t, int id, double& value) {
            GeoKey k;
            if (!geoKey(t, id, k)) return false;
            if (k.location == 0) { value = k.value; return true; }
            if (k.location == 34736) { value = t.real(34736, static_cast<uint32_t>(k.value), std::nan("")); return !std::isnan(value); }
            return false;
        }

        bool readGeo(const Tiff& t, Info& info, std::string& error) {
            const Entry * scale = t.find(33550);
            const Entry * tie = t.find(33922);
            if (scale == nullptr || scale->count < 2 || scale->type != 12 || tie == nullptr || tie->count < 6 || tie->type != 12) {
                error = "the TIFF has no GeoTIFF georeferencing";
                return false;
            }
            const double sx = leDouble(scale->data), sy = leDouble(scale->data + 8);
            if (std::fabs(sx - sy) > 1e-6 * sx || sx <= 0) { error = "unsupported: non-square cells"; return false; }
            double ct = 0;
            if (!geoNumber(t, 3075, ct) || (ct != 8 && ct != 9)) { error = "unsupported projection (need Lambert conformal conic)"; return false; }
            double unit = 9001;
            geoNumber(t, 3076, unit);
            if (unit != 9001) { error = "unsupported linear units (need meters)"; return false; }
            // two standard parallels (CT 8) come with a "false origin" (keys 3084-3087); one (CT 9) with a "natural origin" (3080-3083). A file may
            // spell either, so take whichever is there.
            double p1 = 0, p2 = std::nan(""), originLat = 0, central = 0, easting = 0, northing = 0;
            const bool haveLat = geoNumber(t, 3085, originLat) || geoNumber(t, 3081, originLat);
            if (!haveLat || (!geoNumber(t, 3078, p1) && ct == 8)) { error = "the Lambert definition is incomplete"; return false; }
            if (ct == 9) p1 = originLat;
            geoNumber(t, 3079, p2);
            if (!geoNumber(t, 3084, central) && !geoNumber(t, 3080, central)) geoNumber(t, 3088, central);
            if (!geoNumber(t, 3086, easting)) geoNumber(t, 3082, easting);
            if (!geoNumber(t, 3087, northing)) geoNumber(t, 3083, northing);
            if (std::fabs(p1 - originLat) > 1e-6 || (!std::isnan(p2) && std::fabs(p2 - p1) > 1e-6)) {
                error = "unsupported Lambert definition (need one standard parallel at the origin latitude)";
                return false;
            }
            double major = 0, minor = 0;
            if (geoNumber(t, 2057, major)) {
                if (geoNumber(t, 2058, minor) && std::fabs(major - minor) > 1.0) { error = "unsupported: an ellipsoid (need a sphere)"; return false; }
                info.radius = major;
            }
            // raster type: 1 = pixel is area (the tie point is the cell's corner), 2 = pixel is point (its center)
            double raster = 1;
            geoNumber(t, 1025, raster);
            const double half = raster == 2 ? 0.0 : 0.5;
            // projected position of the center of pixel (column 0, row `rows - 1`) = the south-west cell
            const double i = leDouble(tie->data), j = leDouble(tie->data + 8);
            const double x = leDouble(tie->data + 24) + easting, y = leDouble(tie->data + 32) + northing;
            info.cellMeters = sx;
            info.parallel = p1;
            info.centralMeridian = central;
            info.firstX = x + (half - i) * sx;
            info.firstY = y - ((info.rows - 1) + half - j) * sx;
            return true;
        }
    }

    bool lzwDecode(const uint8_t * in, size_t size, uint8_t * out, size_t capacity, size_t& produced) {
        uint16_t prefix[4096];
        uint8_t suffix[4096], first[4096];
        uint16_t length[4096];
        for (int i = 0; i < 256; i += 1) { suffix[i] = first[i] = static_cast<uint8_t>(i); prefix[i] = 0; length[i] = 1; }
        int next = 258, bits = 9, previous = -1;
        uint32_t buffer = 0;
        int have = 0;
        size_t pos = 0;
        produced = 0;
        for (;;) {
            while (have < bits && pos < size) { buffer = (buffer << 8) | in[pos++]; have += 8; }
            if (have < bits) return true;   // ran out without an end code: the caller checks the amount
            const int code = static_cast<int>((buffer >> (have - bits)) & ((1u << bits) - 1));
            have -= bits;
            buffer &= (1u << have) - 1;
            if (code == 257) return true;
            if (code == 256) { next = 258; bits = 9; previous = -1; continue; }
            if (previous < 0) {
                if (code > 255 || produced >= capacity) return false;
                out[produced++] = static_cast<uint8_t>(code);
                previous = code;
                continue;
            }
            int len;
            uint8_t head;
            if (code < next) {
                len = length[code];
                head = first[code];
            } else if (code == next) {
                len = length[previous] + 1;
                head = first[previous];
            } else {
                return false;
            }
            if (produced + static_cast<size_t>(len) > capacity) return false;
            // write the string backwards along its prefix chain
            uint8_t * end = out + produced + len;
            if (code < next) {
                int c = code;
                for (int k = 0; k < len; k += 1) { *--end = suffix[c]; c = prefix[c]; }
            } else {
                *--end = head;   // the entry being defined ends with its own first character
                int c = previous;
                for (int k = 1; k < len; k += 1) { *--end = suffix[c]; c = prefix[c]; }
            }
            produced += static_cast<size_t>(len);
            if (next < 4096) {
                prefix[next] = static_cast<uint16_t>(previous);
                suffix[next] = head;
                first[next] = first[previous];
                length[next] = static_cast<uint16_t>(length[previous] + 1);
                next += 1;
                if (next == 511) bits = 10; else if (next == 1023) bits = 11; else if (next == 2047) bits = 12;
            }
            previous = code;
        }
    }

    namespace {
        // undoes the predictor on one block of `width` x `height` samples of `bytes` bytes each
        bool unpredict(uint8_t * block, int width, int height, int bytes, int predictor, std::string& error) {
            if (predictor == 1) return true;
            if (predictor == 2) {
                for (int y = 0; y < height; y += 1) {
                    uint8_t * row = block + static_cast<size_t>(y) * width * bytes;
                    if (bytes == 1) {
                        for (int x = 1; x < width; x += 1) row[x] = static_cast<uint8_t>(row[x] + row[x - 1]);
                    } else if (bytes == 2) {
                        for (int x = 1; x < width; x += 1) {
                            const uint16_t v = static_cast<uint16_t>(le16(row + 2 * x) + le16(row + 2 * (x - 1)));
                            row[2 * x] = static_cast<uint8_t>(v); row[2 * x + 1] = static_cast<uint8_t>(v >> 8);
                        }
                    } else {
                        for (int x = 1; x < width; x += 1) {
                            const uint32_t v = le32(row + 4 * x) + le32(row + 4 * (x - 1));
                            for (int b = 0; b < 4; b += 1) row[4 * x + b] = static_cast<uint8_t>(v >> (8 * b));
                        }
                    }
                }
                return true;
            }
            if (predictor == 3 && bytes == 4) {
                std::vector<uint8_t> temp(static_cast<size_t>(width) * 4);
                for (int y = 0; y < height; y += 1) {
                    uint8_t * row = block + static_cast<size_t>(y) * width * 4;
                    for (int k = 1; k < width * 4; k += 1) row[k] = static_cast<uint8_t>(row[k] + row[k - 1]);
                    std::memcpy(temp.data(), row, temp.size());
                    for (int x = 0; x < width; x += 1) {
                        for (int b = 0; b < 4; b += 1) row[4 * x + b] = temp[static_cast<size_t>(3 - b) * width + x];   // planes run most significant byte first
                    }
                }
                return true;
            }
            error = "unsupported TIFF predictor";
            return false;
        }

        bool inflateBlock(const uint8_t * in, size_t size, uint8_t * out, size_t capacity, size_t& produced) {
            uLongf n = static_cast<uLongf>(capacity);
            if (uncompress(out, &n, in, static_cast<uLong>(size)) != Z_OK) return false;
            produced = n;
            return true;
        }
    }

    bool decode(const uint8_t * data, size_t size, std::vector<float>& out, Info& info, std::string& error) {
        Tiff t;
        if (!parse(data, size, t, error)) return false;
        info = Info{};
        const long long width = static_cast<long long>(t.integer(256, 0, 0)), height = static_cast<long long>(t.integer(257, 0, 0));
        if (width <= 0 || height <= 0 || width * height > kMaxCells) { error = "unsupported raster size"; return false; }
        info.columns = static_cast<int>(width);
        info.rows = static_cast<int>(height);
        const int bits = static_cast<int>(t.integer(258, 0, 1));
        const int format = static_cast<int>(t.integer(339, 0, 1));    // 1 unsigned, 2 signed, 3 float
        const int compression = static_cast<int>(t.integer(259, 0, 1));
        const int predictor = static_cast<int>(t.integer(317, 0, 1));
        if (t.integer(277, 0, 1) != 1 || t.integer(284, 0, 1) != 1) { error = "unsupported: need one sample per pixel"; return false; }
        if (!((bits == 8 || bits == 16 || bits == 32) && (format == 1 || format == 2 || (format == 3 && bits == 32)))) {
            error = "unsupported sample type";
            return false;
        }
        if (compression != 1 && compression != 5 && compression != 8 && compression != 32946) { error = "unsupported TIFF compression"; return false; }
        const int bytes = bits / 8;

        // blocks: tiles, or strips (a block as wide as the image)
        long long bw, bh;
        const Entry * offsets;
        const Entry * counts;
        if (t.find(322) != nullptr) {
            bw = static_cast<long long>(t.integer(322, 0, 0));
            bh = static_cast<long long>(t.integer(323, 0, 0));
            offsets = t.find(324);
            counts = t.find(325);
        } else {
            bw = width;
            bh = static_cast<long long>(t.integer(278, 0, static_cast<uint64_t>(height)));
            offsets = t.find(273);
            counts = t.find(279);
        }
        if (bw <= 0 || bh <= 0 || bw > 65536 || bh > 65536 || offsets == nullptr || counts == nullptr || offsets->count != counts->count ||
            (offsets->type != 4 && offsets->type != 3) || (counts->type != 4 && counts->type != 3)) {
            error = "unsupported or damaged TIFF block layout";
            return false;
        }
        const long long across = (width + bw - 1) / bw, down = (height + bh - 1) / bh;
        if (static_cast<long long>(offsets->count) < across * down) { error = "the TIFF is missing blocks"; return false; }

        if (const Entry * nd = t.find(42113); nd != nullptr && nd->type == 2 && nd->count > 0) {
            const std::string text(reinterpret_cast<const char *>(nd->data), nd->count);
            char * end = nullptr;
            const double v = std::strtod(text.c_str(), &end);
            if (end != text.c_str()) { info.hasNodata = true; info.nodata = v; }
        }
        if (!readGeo(t, info, error)) return false;

        out.assign(static_cast<size_t>(width) * height, std::nanf(""));
        std::vector<uint8_t> block(static_cast<size_t>(bw) * bh * bytes);
        const float nodata = static_cast<float>(info.nodata);
        for (long long by = 0; by < down; by += 1) {
            for (long long bx = 0; bx < across; bx += 1) {
                const size_t k = static_cast<size_t>(by * across + bx);
                const size_t offset = static_cast<size_t>(offsets->type == 4 ? le32(offsets->data + 4 * k) : le16(offsets->data + 2 * k));
                const size_t count = static_cast<size_t>(counts->type == 4 ? le32(counts->data + 4 * k) : le16(counts->data + 2 * k));
                if (offset > size || count > size - offset) { error = "a TIFF block lies outside the file"; return false; }
                // a strip image's last strip is shorter than the others
                const long long rowsHere = std::min<long long>(bh, height - by * bh);
                const long long needRows = t.find(322) != nullptr ? bh : rowsHere;
                const size_t need = static_cast<size_t>(bw) * needRows * bytes;
                size_t produced = 0;
                bool ok = true;
                if (compression == 1) {
                    produced = std::min(count, need);
                    std::memcpy(block.data(), data + offset, produced);
                } else if (compression == 5) {
                    ok = lzwDecode(data + offset, count, block.data(), need, produced);
                } else {
                    ok = inflateBlock(data + offset, count, block.data(), need, produced);
                }
                if (!ok || produced < need) { error = "a TIFF block is damaged"; return false; }
                if (!unpredict(block.data(), static_cast<int>(bw), static_cast<int>(needRows), bytes, predictor, error)) return false;
                for (long long y = 0; y < rowsHere; y += 1) {
                    const long long row = by * bh + y;
                    float * dst = out.data() + static_cast<size_t>(height - 1 - row) * width;   // row 0 = south
                    const uint8_t * src = block.data() + static_cast<size_t>(y) * bw * bytes;
                    const long long cols = std::min<long long>(bw, width - bx * bw);
                    for (long long x = 0; x < cols; x += 1) {
                        const uint8_t * p = src + static_cast<size_t>(x) * bytes;
                        float v;
                        if (format == 3) { std::memcpy(&v, p, 4); }
                        else if (format == 2) v = bytes == 1 ? static_cast<float>(static_cast<int8_t>(p[0])) : bytes == 2 ? static_cast<float>(static_cast<int16_t>(le16(p))) : static_cast<float>(static_cast<int32_t>(le32(p)));
                        else v = bytes == 1 ? static_cast<float>(p[0]) : bytes == 2 ? static_cast<float>(le16(p)) : static_cast<float>(le32(p));
                        if (std::isnan(v) || (info.hasNodata && v == nodata)) v = std::nanf("");
                        dst[bx * bw + x] = v;
                    }
                }
            }
        }
        return true;
    }
}
