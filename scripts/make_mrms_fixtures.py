#!/usr/bin/env python3
"""Cuts small test fixtures out of real MRMS GRIB2 files (PNG packing, template 5.41).

For each full file <name>.grib2 it writes fixtures/mrms/<name>.grib2.gz (a valid GRIB2 message holding a W x H window, re-packed as a
PNG) and <name>.expected.f32 (the window's values as GDAL decodes the FULL file, little-endian float32, row 0 = north). The C++ test decodes
the fixture with the core and compares with those GDAL values, so the reference is an independent decoder, not this script.

Needs numpy, Pillow and gdal_translate. Usage: make_mrms_fixtures.py <outdir> <full.grib2>...
"""
import gzip, io, os, struct, subprocess, sys, tempfile
import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
W, H = 120, 80


def sections(b):
    pos, out = 16, {}
    total = struct.unpack('>Q', b[8:16])[0]
    while pos < total - 4:
        n = struct.unpack('>I', b[pos:pos + 4])[0]
        out[b[pos + 4]] = (pos, n)
        pos += n
    return out


def main(outdir, paths):
    os.makedirs(outdir, exist_ok=True)
    for path in paths:
        name = os.path.basename(path).split('.grib2')[0]
        b = open(path, 'rb').read()
        sec = sections(b)
        p1, n1 = sec[1]; p4, n4 = sec[4]; p3, n3 = sec[3]; p5, n5 = sec[5]; p6, n6 = sec[6]; p7, n7 = sec[7]
        ni, nj = struct.unpack('>II', b[p3 + 30:p3 + 38])
        la1, lo1 = struct.unpack('>ii', b[p3 + 46:p3 + 54])
        di, dj = struct.unpack('>II', b[p3 + 63:p3 + 71])
        R = struct.unpack('>f', b[p5 + 11:p5 + 15])[0]; E, D = struct.unpack('>hh', b[p5 + 15:p5 + 19]); bits = b[p5 + 19]
        png = Image.open(io.BytesIO(b[p7 + 5:p7 + n7]))
        raw = np.asarray(png).astype(np.int64)
        assert raw.shape == (nj, ni)
        # GDAL's decode of the whole file is the reference
        with tempfile.TemporaryDirectory() as t:
            out = os.path.join(t, 'full.flt')
            subprocess.run(['gdal_translate', '-q', '-of', 'EHdr', '-ot', 'Float32', path, out], check=True)
            gd = np.fromfile(out, dtype='<f4').reshape(nj, ni)
        mine = ((R + raw * 2.0 ** E) / 10.0 ** D).astype(np.float32)
        # (GDAL does the arithmetic in float32: results can differ from the exact decimal by one float32 step)
        assert np.allclose(mine, gd, rtol=1e-6, atol=1e-4), 'the formula does not reproduce GDAL on the full file'
        # the busiest window: most distinct values over a coarse search
        best, at = -1, (0, 0)
        for y in range(0, nj - H, max(H, nj // 40)):
            for x in range(0, ni - W, max(W, ni // 60)):
                score = len(np.unique(raw[y:y + H, x:x + W]))
                if score > best: best, at = score, (y, x)
        y, x = at
        win = raw[y:y + H, x:x + W]
        expected = gd[y:y + H, x:x + W]
        # a new message: same sections, the grid cut down
        s3 = bytearray(b[p3:p3 + n3])
        struct.pack_into('>I', s3, 6, W * H)
        struct.pack_into('>II', s3, 30, W, H)
        struct.pack_into('>ii', s3, 46, la1 - y * dj, lo1 + x * di)
        struct.pack_into('>ii', s3, 55, la1 - (y + H - 1) * dj, lo1 + (x + W - 1) * di)
        s5 = bytearray(b[p5:p5 + n5]); struct.pack_into('>I', s5, 5, W * H)
        buf = io.BytesIO()
        Image.fromarray(win.astype(np.uint16 if bits == 16 else np.uint8)).save(buf, 'PNG', optimize=True)
        s7 = struct.pack('>IB', 5 + len(buf.getvalue()), 7) + buf.getvalue()
        body = b[p1:p1 + n1] + bytes(s3) + b[p4:p4 + n4] + bytes(s5) + b[p6:p6 + n6] + s7 + b'7777'
        head = b[:8] + struct.pack('>Q', 16 + len(body))
        msg = head + body
        with gzip.GzipFile(os.path.join(outdir, name + '.grib2.gz'), 'wb', mtime=0) as f: f.write(msg)
        expected.astype('<f4').tofile(os.path.join(outdir, name + '.expected.f32'))
        print(name, 'window at row', y, 'col', x, 'distinct', best, 'bytes', len(msg))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
