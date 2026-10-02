// *****************************************************************************
// * This file is part of SPC REFS Mobile (derived from wxqt).  Licensed under the GNU General Public License v3.
// * See the COPYING file for the full license text.
// *****************************************************************************

#ifndef BLOSC_DECODE_H
#define BLOSC_DECODE_H

#include <string>
#include <vector>

// Decoder for the blosc (version 1 frame) buffers that Zarr stores use as their compressor: byte shuffle plus lz4 or zstd
// (blosclz, zlib and snappy are not needed by the data this app reads and are reported as unsupported). zstd is the
// decoder from https://github.com/facebook/zstd (src/external/zstddeclib.c, BSD license, docs/zstd-LICENSE.txt).
namespace Blosc {
    // `out` gets the decoded bytes; false with a reason in `error` on a malformed or unsupported buffer
    bool decompress(const unsigned char * data, size_t size, std::vector<unsigned char>& out, std::string& error);
}

#endif  // BLOSC_DECODE_H
