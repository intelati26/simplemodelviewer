// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************

#include "chunk_decode.h"
#include <cstdint>
#include <cstring>
#include "blosc_decode.h"

namespace ChunkDecode {
    float halfToFloat(unsigned short h) {
        const uint32_t sign = (h & 0x8000u) << 16;
        uint32_t exponent = (h >> 10) & 0x1Fu;
        uint32_t mantissa = h & 0x3FFu;
        uint32_t bits;
        if (exponent == 0) {
            if (mantissa == 0) {
                bits = sign;
            } else {   // subnormal: normalise
                exponent = 127 - 15 + 1;
                while ((mantissa & 0x400u) == 0) {
                    mantissa <<= 1;
                    exponent -= 1;
                }
                mantissa &= 0x3FFu;
                bits = sign | (exponent << 23) | (mantissa << 13);
            }
        } else if (exponent == 31) {
            bits = sign | 0x7F800000u | (mantissa << 13);
        } else {
            bits = sign | ((exponent + 127 - 15) << 23) | (mantissa << 13);
        }
        float value;
        std::memcpy(&value, &bits, sizeof value);
        return value;
    }

    bool toFloats(const unsigned char * data, size_t size, const std::string& dtype, bool compressed, size_t count,
                  std::vector<float>& out, std::string& error) {
        std::vector<unsigned char> bytes;
        if (compressed) {
            if (!Blosc::decompress(data, size, bytes, error)) {
                return false;
            }
        } else {
            bytes.assign(data, data + size);
        }
        size_t width = 0;
        if (dtype.size() >= 3) {
            width = static_cast<size_t>(std::atoi(dtype.c_str() + 2));
        }
        if (width == 0 || bytes.size() < count * width) {
            error = "chunk has " + std::to_string(bytes.size()) + " bytes, expected " + std::to_string(count * width);
            return false;
        }
        out.assign(count, 0.0f);
        const unsigned char * p = bytes.data();
        const char kind = dtype[1];
        for (size_t i = 0; i < count; i += 1, p += width) {
            float v = 0.0f;
            if (kind == 'f' && width == 2) {
                uint16_t h;
                std::memcpy(&h, p, 2);
                v = halfToFloat(h);
            } else if (kind == 'f' && width == 4) {
                std::memcpy(&v, p, 4);
            } else if (kind == 'f' && width == 8) {
                double d;
                std::memcpy(&d, p, 8);
                v = static_cast<float>(d);
            } else if (kind == 'i' && width == 4) {
                int32_t n;
                std::memcpy(&n, p, 4);
                v = static_cast<float>(n);
            } else if (kind == 'i' && width == 8) {
                int64_t n;
                std::memcpy(&n, p, 8);
                v = static_cast<float>(n);
            } else if (kind == 'u' && width == 1) {
                v = static_cast<float>(*p);
            } else if (kind == 'i' && width == 1) {
                v = static_cast<float>(static_cast<int8_t>(*p));
            } else if (kind == 'u' && width == 2) {
                uint16_t n;
                std::memcpy(&n, p, 2);
                v = static_cast<float>(n);
            } else if (kind == 'i' && width == 2) {
                int16_t n;
                std::memcpy(&n, p, 2);
                v = static_cast<float>(n);
            } else {
                error = "unsupported data type " + dtype;
                return false;
            }
            out[i] = v;
        }
        return true;
    }
}
