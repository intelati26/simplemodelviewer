// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// * See the COPYING file for the full license text.
// *****************************************************************************

#include "blosc_decode.h"
#include <cstdint>
#include <cstring>

extern "C" {
    // from third_party/zstd/zstddeclib.c
    size_t ZSTD_decompress(void * dst, size_t dstCapacity, const void * src, size_t compressedSize);
    unsigned ZSTD_isError(size_t code);
}

namespace Blosc {
    namespace {
        uint32_t le32(const unsigned char * p) {
            return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
        }

        // one LZ4 block (the format blosc uses): sequences of a token, literals, a 2-byte offset and a match
        bool lz4Block(const unsigned char * src, size_t srcSize, unsigned char * dst, size_t dstSize) {
            size_t s = 0;
            size_t d = 0;
            while (s < srcSize) {
                const unsigned token = src[s++];
                size_t literals = token >> 4;
                if (literals == 15) {
                    unsigned char more = 255;
                    while (more == 255) {
                        if (s >= srcSize) return false;
                        more = src[s++];
                        literals += more;
                    }
                }
                if (s + literals > srcSize || d + literals > dstSize) return false;
                std::memcpy(dst + d, src + s, literals);
                s += literals;
                d += literals;
                if (s >= srcSize) break;   // the last sequence has no match
                if (s + 2 > srcSize) return false;
                const size_t offset = src[s] | (static_cast<size_t>(src[s + 1]) << 8);
                s += 2;
                size_t match = token & 15;
                if (match == 15) {
                    unsigned char more = 255;
                    while (more == 255) {
                        if (s >= srcSize) return false;
                        more = src[s++];
                        match += more;
                    }
                }
                match += 4;
                if (offset == 0 || offset > d || d + match > dstSize) return false;
                for (size_t i = 0; i < match; i += 1) {   // may overlap: copy forwards byte by byte
                    dst[d + i] = dst[d + i - offset];
                }
                d += match;
            }
            return d == dstSize;
        }

        // blosc's byte shuffle puts byte 0 of every element first, then byte 1, ...; leftover bytes are not shuffled
        void unshuffle(const unsigned char * shuffled, unsigned char * out, size_t size, size_t typeSize) {
            const size_t elements = size / typeSize;
            for (size_t byte = 0; byte < typeSize; byte += 1) {
                for (size_t i = 0; i < elements; i += 1) {
                    out[i * typeSize + byte] = shuffled[byte * elements + i];
                }
            }
            std::memcpy(out + elements * typeSize, shuffled + elements * typeSize, size - elements * typeSize);
        }
    }

    bool decompress(const unsigned char * data, size_t size, std::vector<unsigned char>& out, std::string& error) {
        if (size < 16) {
            error = "blosc: buffer too short";
            return false;
        }
        const unsigned flags = data[2];
        const size_t typeSize = data[3];
        const size_t nbytes = le32(data + 4);
        const size_t blockSize = le32(data + 8);
        const size_t cbytes = le32(data + 12);
        if (data[0] != 2 || cbytes > size || typeSize == 0) {
            error = "blosc: not a version 2 buffer";
            return false;
        }
        out.assign(nbytes, 0);
        if (nbytes == 0) {
            return true;
        }
        if (flags & 0x2) {   // stored without compression
            if (16 + nbytes > size) {
                error = "blosc: truncated";
                return false;
            }
            std::memcpy(out.data(), data + 16, nbytes);
            return true;
        }
        if (flags & 0x4) {
            error = "blosc: bit shuffle is not supported";
            return false;
        }
        const unsigned codec = flags >> 5;   // 0 blosclz, 1 lz4, 2 snappy, 3 zlib, 4 zstd
        if (codec != 1 && codec != 4) {
            error = "blosc: unsupported compressor " + std::to_string(codec);
            return false;
        }
        if (blockSize == 0) {
            error = "blosc: zero block size";
            return false;
        }
        const bool shuffle = (flags & 0x1) != 0;
        const bool dontSplit = (flags & 0x10) != 0;
        const size_t blocks = (nbytes + blockSize - 1) / blockSize;
        const size_t leftover = nbytes % blockSize;
        if (16 + blocks * 4 > size) {
            error = "blosc: truncated block table";
            return false;
        }
        std::vector<unsigned char> block(blockSize);
        for (size_t b = 0; b < blocks; b += 1) {
            const size_t thisBlock = (b == blocks - 1 && leftover != 0) ? leftover : blockSize;
            const bool leftoverBlock = thisBlock != blockSize;
            size_t at = le32(data + 16 + b * 4);
            const size_t splits = (!dontSplit && !leftoverBlock) ? typeSize : 1;
            const size_t part = thisBlock / splits;
            unsigned char * target = shuffle ? block.data() : out.data() + b * blockSize;
            for (size_t k = 0; k < splits; k += 1) {
                if (at + 4 > size) {
                    error = "blosc: truncated block";
                    return false;
                }
                const size_t csize = le32(data + at);
                at += 4;
                if (at + csize > size) {
                    error = "blosc: truncated split";
                    return false;
                }
                unsigned char * piece = target + k * part;
                const size_t pieceSize = (k == splits - 1) ? thisBlock - k * part : part;   // the last split takes any remainder
                if (csize == pieceSize) {
                    std::memcpy(piece, data + at, pieceSize);
                } else if (codec == 1) {
                    if (!lz4Block(data + at, csize, piece, pieceSize)) {
                        error = "blosc: bad lz4 data";
                        return false;
                    }
                } else {
                    const size_t got = ZSTD_decompress(piece, pieceSize, data + at, csize);
                    if (ZSTD_isError(got) != 0 || got != pieceSize) {
                        error = "blosc: bad zstd data";
                        return false;
                    }
                }
                at += csize;
            }
            if (shuffle) {
                unshuffle(block.data(), out.data() + b * blockSize, thisBlock, typeSize);
            }
        }
        return true;
    }
}
