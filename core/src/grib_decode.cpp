// *****************************************************************************
// * This file is part of simplemodelview.  Licensed under the GNU General Public License v3.
// *****************************************************************************
#include "grib_decode.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace Grib {
    namespace {
        uint32_t be32(const uint8_t * p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]); }
        uint16_t be16(const uint8_t * p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }
        // GRIB2 signed integers are sign and magnitude
        int32_t sm32(const uint8_t * p) { const uint32_t v = be32(p); return (v & 0x80000000u) ? -static_cast<int32_t>(v & 0x7FFFFFFFu) : static_cast<int32_t>(v); }
        int sm16(const uint8_t * p) { const int v = be16(p); return (v & 0x8000) ? -(v & 0x7FFF) : v; }
        float ieee32(const uint8_t * p) { const uint32_t v = be32(p); float f; std::memcpy(&f, &v, 4); return f; }

        int64_t daysFromCivil(int y, int m, int d) {
            y -= m <= 2;
            const int64_t era = (y >= 0 ? y : y - 399) / 400;
            const int64_t yoe = y - era * 400;
            const int64_t doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
            const int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
            return era * 146097 + doe - 719468;
        }

        // reads big-endian bit fields from a byte range
        struct Bits {
            const uint8_t * p; size_t size; size_t bit = 0;
            bool ok = true;
            uint64_t read(int n) {
                if (n == 0) return 0;
                if (bit + n > size * 8) { ok = false; return 0; }
                uint64_t v = 0;
                int left = n;
                while (left > 0) {
                    const size_t byte = bit >> 3;
                    const int offset = static_cast<int>(bit & 7);
                    const int take = std::min(left, 8 - offset);
                    const unsigned chunk = (p[byte] >> (8 - offset - take)) & ((1u << take) - 1u);
                    v = (v << take) | chunk;
                    bit += take;
                    left -= take;
                }
                return v;
            }
            void align() { bit = (bit + 7) & ~size_t(7); }
        };
    }

    bool decode(const uint8_t * data, size_t size, std::vector<float>& out, Info& info, std::string& error) {
        if (size < 20 || std::memcmp(data, "GRIB", 4) != 0) { error = "not a GRIB message"; return false; }
        if (data[7] != 2) { error = "not GRIB edition 2"; return false; }
        uint64_t total = 0;
        for (int i = 8; i < 16; i += 1) total = (total << 8) | data[i];
        const size_t end = std::min<uint64_t>(total, size);

        const uint8_t * s3 = nullptr; size_t l3 = 0;
        const uint8_t * s5 = nullptr; size_t l5 = 0;
        const uint8_t * s6 = nullptr; size_t l6 = 0;
        const uint8_t * s7 = nullptr; size_t l7 = 0;
        size_t pos = 16;
        while (pos + 5 <= end) {
            if (std::memcmp(data + pos, "7777", 4) == 0) break;
            const size_t length = be32(data + pos);
            const int section = data[pos + 4];
            if (length < 5 || pos + length > end) { error = "damaged GRIB section"; return false; }
            const uint8_t * s = data + pos;
            switch (section) {
                case 1:
                    if (length >= 19) info.referenceTime = daysFromCivil(be16(s + 12), s[14], s[15]) * 86400LL + s[16] * 3600 + s[17] * 60 + s[18];
                    break;
                case 3: s3 = s; l3 = length; break;
                case 4:
                    if (length >= 28) {
                        info.category = s[9]; info.number = s[10];
                        if (s[17] == 1) info.forecastHours = static_cast<int>(be32(s + 18));
                    }
                    break;
                case 5: s5 = s; l5 = length; break;
                case 6: s6 = s; l6 = length; break;
                case 7: s7 = s; l7 = length; break;
                default: break;
            }
            pos += length;
        }
        if (!s3 || !s5 || !s6 || !s7) { error = "the GRIB message is missing a section"; return false; }

        // ---- the grid (section 3) ----
        if (l3 < 81 || be16(s3 + 12) != 30) { error = "unsupported grid template (need Lambert conformal, 3.30)"; return false; }
        const int shape = s3[14];
        double radius = 6371229.0;
        if (shape == 0) radius = 6367470.0;
        else if (shape == 1) radius = be32(s3 + 16) / std::pow(10.0, s3[15]);
        else if (shape == 6) radius = 6371229.0;
        else { error = "unsupported earth shape (need a sphere)"; return false; }
        const int nx = static_cast<int>(be32(s3 + 30)), ny = static_cast<int>(be32(s3 + 34));
        if (nx <= 0 || ny <= 0 || static_cast<int64_t>(nx) * ny > 60000000) { error = "unreasonable GRIB grid size"; return false; }
        const double la1 = sm32(s3 + 38) * 1e-6, lo1 = be32(s3 + 42) * 1e-6;
        const double lov = be32(s3 + 51) * 1e-6;
        const double dx = be32(s3 + 55) * 1e-3, dy = be32(s3 + 59) * 1e-3;
        const int scan = s3[64];
        const double latin1 = sm32(s3 + 65) * 1e-6, latin2 = sm32(s3 + 69) * 1e-6;
        if (std::fabs(latin1 - latin2) > 1e-6) { error = "unsupported projection (two different standard parallels)"; return false; }
        if (std::fabs(dx - dy) > 1.0 || dx <= 0) { error = "unsupported grid spacing (need square cells)"; return false; }
        if (be32(s3 + 6) != static_cast<uint32_t>(nx) * static_cast<uint32_t>(ny)) { error = "the grid size does not match its point count"; return false; }

        const double pi = 3.14159265358979323846;
        const double lovWest = (lov > 180.0) ? lov - 360.0 : lov;
        const double lo1West = (lo1 > 180.0) ? lo1 - 360.0 : lo1;
        const double phi1 = latin1 * pi / 180.0;
        const double n = std::sin(phi1);
        const double f = std::cos(phi1) * std::pow(std::tan(pi / 4.0 + phi1 / 2.0), n) / n;
        auto rho = [&](double phi) { return radius * f / std::pow(std::tan(pi / 4.0 + phi / 2.0), n); };
        const double rho0 = rho(phi1);
        double dl = (lo1West - lovWest) * pi / 180.0;
        dl = std::fmod(dl + 3.0 * pi, 2.0 * pi) - pi;
        const double r1 = rho(la1 * pi / 180.0);
        const double firstX = r1 * std::sin(n * dl);
        const double firstY = rho0 - r1 * std::cos(n * dl);

        // ---- the packing (section 5) ----
        const uint32_t npoints = be32(s5 + 5);                         // the values stored (the grid's points that are in the bitmap)
        const int tmpl = be16(s5 + 9);
        if ((tmpl != 0 && tmpl != 2 && tmpl != 3) || l5 < 21) { error = "unsupported data packing (need 5.0, 5.2 or 5.3)"; return false; }
        const double reference = ieee32(s5 + 11);
        const int e = sm16(s5 + 15), d = sm16(s5 + 17);
        const int bitsPerValue = s5[19];
        const double scale = std::pow(2.0, e), decimal = std::pow(10.0, -d);

        // ---- the bitmap (section 6) ----
        const size_t cells = static_cast<size_t>(nx) * static_cast<size_t>(ny);
        std::vector<uint8_t> present;                                   // empty: every cell has a value
        if (l6 < 6) { error = "damaged GRIB bitmap section"; return false; }
        if (s6[5] == 0) {
            Bits b{s6 + 6, l6 - 6};
            present.assign(cells, 0);
            for (size_t i = 0; i < cells; i += 1) present[i] = static_cast<uint8_t>(b.read(1));
            if (!b.ok) { error = "damaged GRIB bitmap"; return false; }
        } else if (s6[5] != 255) { error = "unsupported bitmap indicator"; return false; }

        // ---- the data (section 7) ----
        const uint8_t * data7 = s7 + 5;
        const size_t size7 = l7 - 5;
        std::vector<double> values;                                     // the values stored, in order (NaN where the packing says missing)
        values.reserve(npoints);
        const double nan = std::numeric_limits<double>::quiet_NaN();
        if (tmpl == 0) {
            Bits b{data7, size7};
            for (uint32_t i = 0; i < npoints; i += 1) {
                const double x = bitsPerValue == 0 ? 0.0 : static_cast<double>(b.read(bitsPerValue));
                values.push_back((reference + x * scale) * decimal);
            }
            if (!b.ok) { error = "damaged GRIB data"; return false; }
        } else {
            if (l5 < 47) { error = "damaged GRIB packing section"; return false; }
            const int missing = s5[22];
            const uint32_t groups = be32(s5 + 31);
            const int groupWidthRef = s5[35], groupWidthBits = s5[36];
            const uint32_t groupLengthRef = be32(s5 + 37);
            const int groupLengthInc = s5[41];
            const uint32_t lastLength = be32(s5 + 42);
            const int groupLengthBits = s5[46];
            int order = 0, extra = 0;
            if (tmpl == 3) { if (l5 < 49) { error = "damaged GRIB packing section"; return false; } order = s5[47]; extra = s5[48]; }
            if (order > 2 || groups == 0 || groups > npoints + 1) { error = "unsupported spatial differencing"; return false; }

            Bits b{data7, size7};
            int64_t first[2] = {0, 0};
            int64_t minimum = 0;
            auto signedField = [&](int octets) -> int64_t {
                const int bits = octets * 8;
                const uint64_t sign = b.read(1);
                const int64_t mag = static_cast<int64_t>(b.read(bits - 1));
                return sign ? -mag : mag;
            };
            if (order > 0) {
                for (int k = 0; k < order; k += 1) first[k] = signedField(extra);
                minimum = signedField(extra);
            }
            std::vector<uint32_t> refs(groups), widths(groups), lengths(groups);
            for (uint32_t g = 0; g < groups; g += 1) refs[g] = static_cast<uint32_t>(b.read(bitsPerValue));
            b.align();
            for (uint32_t g = 0; g < groups; g += 1) widths[g] = static_cast<uint32_t>(b.read(groupWidthBits)) + groupWidthRef;
            b.align();
            for (uint32_t g = 0; g < groups; g += 1) lengths[g] = static_cast<uint32_t>(b.read(groupLengthBits)) * groupLengthInc + groupLengthRef;
            lengths[groups - 1] = lastLength;
            b.align();
            if (!b.ok) { error = "damaged GRIB group tables"; return false; }

            std::vector<int64_t> x;                                     // the integers of the groups; missing ones are flagged
            std::vector<uint8_t> gone;
            x.reserve(npoints);
            gone.reserve(npoints);
            const uint64_t refAllOnes = bitsPerValue >= 64 ? ~uint64_t(0) : ((uint64_t(1) << bitsPerValue) - 1);
            for (uint32_t g = 0; g < groups; g += 1) {
                const int w = static_cast<int>(widths[g]);
                if (w > 32) { error = "unsupported group width"; return false; }
                const uint64_t allOnes = w == 0 ? 0 : ((uint64_t(1) << w) - 1);
                for (uint32_t i = 0; i < lengths[g]; i += 1) {
                    bool miss = false;
                    int64_t v;
                    if (w == 0) {
                        v = static_cast<int64_t>(refs[g]);
                        miss = (missing >= 1 && refs[g] == refAllOnes) || (missing == 2 && refs[g] == refAllOnes - 1);
                    } else {
                        const uint64_t raw = b.read(w);
                        v = static_cast<int64_t>(refs[g]) + static_cast<int64_t>(raw);
                        miss = (missing >= 1 && raw == allOnes) || (missing == 2 && raw == allOnes - 1);
                    }
                    x.push_back(v);
                    gone.push_back(miss ? 1 : 0);
                }
            }
            if (!b.ok) { error = "damaged GRIB data"; return false; }
            if (x.size() != npoints) { error = "the GRIB group lengths do not add up to the point count"; return false; }

            // undo the spatial differencing over the values that are there
            if (order > 0) {
                int64_t prev1 = 0, prev2 = 0;                           // the last and the one before
                int count = 0;
                for (size_t i = 0; i < x.size(); i += 1) {
                    if (gone[i]) continue;
                    int64_t v;
                    if (count < order) v = first[count];
                    else if (order == 1) v = x[i] + minimum + prev1;
                    else v = x[i] + minimum + 2 * prev1 - prev2;
                    x[i] = v;
                    prev2 = prev1; prev1 = v;
                    count += 1;
                }
            }
            for (size_t i = 0; i < x.size(); i += 1) values.push_back(gone[i] ? nan : (reference + static_cast<double>(x[i]) * scale) * decimal);
        }
        if (values.size() != npoints) { error = "the GRIB value count does not match"; return false; }

        // ---- lay the values out: row 0 = south, columns west to east ----
        const bool iNegative = (scan & 0x80) != 0, jPositive = (scan & 0x40) != 0, jConsecutive = (scan & 0x20) != 0, alternate = (scan & 0x10) != 0;
        if (alternate && jConsecutive) { error = "unsupported scanning mode"; return false; }
        out.assign(cells, std::numeric_limits<float>::quiet_NaN());
        size_t next = 0;
        for (size_t k = 0; k < cells; k += 1) {
            if (!present.empty() && !present[k]) continue;
            if (next >= values.size()) { error = "the GRIB bitmap has more points than values"; return false; }
            const double v = values[next++];
            // k is the position in the file's scan order
            size_t a = jConsecutive ? k / ny : k % nx;                  // the i index along the scan (before direction)
            const size_t b2 = jConsecutive ? k % ny : k / nx;           // the j index
            if (alternate && (b2 & 1)) a = static_cast<size_t>(nx) - 1 - a;      // every other row runs the other way
            const size_t col = iNegative ? static_cast<size_t>(nx) - 1 - a : a;
            const size_t row = jPositive ? b2 : static_cast<size_t>(ny) - 1 - b2;
            out[row * nx + col] = static_cast<float>(v);
        }
        info.columns = nx; info.rows = ny;
        info.parallel = latin1; info.centralMeridian = lovWest; info.radius = radius; info.cellMeters = dx;
        info.firstX = firstX; info.firstY = firstY;
        // the grid is aligned with the projection's axes: when the file starts at the east or north edge, the south-west cell is the rest of the row / column away
        if (iNegative) info.firstX = firstX - (nx - 1) * dx;
        if (!jPositive) info.firstY = firstY - (ny - 1) * dy;
        return true;
    }
}
