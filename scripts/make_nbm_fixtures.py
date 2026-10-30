#!/usr/bin/env python3
"""Fetches a few National Blend of Models GeoTIFFs (public bucket noaa-nbm-pds) and writes, next to each, a list of sampled cells
(`<name>.samples.txt`: column, row from the SOUTH, value or nan) read with Pillow, to check core/src/geotiff_decode.cpp against.
The four cover the encodings the files use: 16-bit integers with predictor 2 (temp), 32-bit floats with predictor 3 (qpf06),
16-bit integers with no predictor and a nodata value (ceil), and no nodata (tstm01).   Needs: pip install pillow numpy"""
import re, sys, urllib.parse, urllib.request, os
import numpy as np
from PIL import Image

BUCKET = 'https://noaa-nbm-pds.s3.amazonaws.com/'
RUN = sys.argv[1] if len(sys.argv) > 1 else 'blendv5.0/conus/2026/10/30/0000/'
VALID_INDEX = {'temp': 40, 'qpf06': 20, 'ceil': 5, 'tstm01': 12}
OUT = os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'nbm')
os.makedirs(OUT, exist_ok=True)
for product, index in VALID_INDEX.items():
    listing = urllib.request.urlopen(BUCKET + '?list-type=2&max-keys=1000&prefix=' + urllib.parse.quote(RUN + product + '/')).read().decode()
    key = re.findall(r'<Key>([^<]+)</Key>', listing)[index]
    raw = urllib.request.urlopen(BUCKET + urllib.parse.quote(key)).read()
    name = f'nbm_{product}.tif'
    open(os.path.join(OUT, name), 'wb').write(raw)
    im = Image.open(os.path.join(OUT, name))
    a = np.array(im).astype(np.float64)
    nodata = im.tag_v2.get(42113)
    if nodata is not None:
        a[a == float(nodata)] = np.nan
    a = a[::-1]   # row 0 = south
    rng = np.random.default_rng(7)
    rows, cols = a.shape
    with open(os.path.join(OUT, name + '.samples.txt'), 'w') as f:
        f.write(f'# {key}\n# size {cols} {rows}\n')
        for c, r in [(0, 0), (cols - 1, 0), (0, rows - 1), (cols - 1, rows - 1)] + list(zip(rng.integers(0, cols, 400), rng.integers(0, rows, 400))):
            v = a[r, c]
            f.write(f'{c} {r} {"nan" if np.isnan(v) else repr(float(v))}\n')
        f.write(f'# nan_count {int(np.isnan(a).sum())}\n')
    print(name, len(raw), key.split('/')[-1])
