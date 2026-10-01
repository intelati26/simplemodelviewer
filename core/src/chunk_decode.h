// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// *****************************************************************************

#ifndef CHUNK_DECODE_H
#define CHUNK_DECODE_H

#include <string>
#include <vector>

namespace ChunkDecode {
    float halfToFloat(unsigned short h);
    // Decodes one Zarr chunk (blosc-compressed when `compressed`) of numeric dtype `dtype` ("<f2", "<i4", "|u1", ...) into
    // `count` floats in C order. Fails with a reason in `error` on a malformed buffer or unsupported dtype.
    bool toFloats(const unsigned char * data, size_t size, const std::string& dtype, bool compressed, size_t count,
                  std::vector<float>& out, std::string& error);
}

#endif  // CHUNK_DECODE_H
