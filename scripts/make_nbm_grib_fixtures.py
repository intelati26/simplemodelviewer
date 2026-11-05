#!/usr/bin/env python3
"""Fetches a few National Blend of Models GRIB2 messages (public bucket noaa-nbm-grib2-pds, byte ranges found with the .idx) and writes, next to each,
a list of sampled cells (`<name>.samples.txt`: column, row from the SOUTH, value or nan) read with pygrib, to check core/src/grib_decode.cpp against.
Usage: make_nbm_grib_fixtures.py [blend.YYYYMMDD/HH/core/blend.tHHz.core.fFFF.co.grib2] [name=idx-pattern ...]   Needs: pip install pygrib numpy"""
import os, re, sys, urllib.request
import numpy as np
import pygrib

BUCKET = 'https://noaa-nbm-grib2-pds.s3.amazonaws.com/'
FILE = sys.argv[1] if len(sys.argv) > 1 else 'blend.20261104/23/core/blend.t23z.core.f012.co.grib2'
FIELDS = dict(a.split('=', 1) for a in sys.argv[2:]) or {'temp': ':TMP:2 m above ground:12 hour fcst:$', 'apcp': ':APCP:surface:11-12 hour acc fcst:$', 'vis': ':VIS:surface:12 hour fcst:$'}
OUT = os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'nbm')
os.makedirs(OUT, exist_ok=True)
idx = urllib.request.urlopen(BUCKET + FILE + '.idx').read().decode().splitlines()
offsets = [int(l.split(':')[1]) for l in idx]
for name, pattern in FIELDS.items():
    hits = [i for i, l in enumerate(idx) if re.search(pattern, l)]
    if not hits:
        print(name, 'no match for', pattern); continue
    i = hits[0]
    req = urllib.request.Request(BUCKET + FILE, headers={'Range': f'bytes={offsets[i]}-{offsets[i + 1] - 1}'})
    raw = urllib.request.urlopen(req).read()
    path = os.path.join(OUT, f'nbm_{name}.grib2')
    open(path, 'wb').write(raw)
    m = pygrib.open(path)[1]
    v = np.ma.filled(m.values, np.nan).astype(np.float64)   # row 0 = south for these files (jScansPositively)
    assert m['jScansPositively'] == 1 and m['iScansNegatively'] == 0
    rng = np.random.default_rng(11)
    rows, cols = v.shape
    with open(path + '.samples.txt', 'w') as f:
        f.write(f'# {FILE} {idx[i]}\n# size {cols} {rows}\n# packing {m["packingType"]}\n')
        for c, r in [(0, 0), (cols - 1, 0), (0, rows - 1), (cols - 1, rows - 1)] + list(zip(rng.integers(0, cols, 400), rng.integers(0, rows, 400))):
            x = v[r, c]
            f.write(f'{c} {r} {"nan" if np.isnan(x) else repr(float(x))}\n')
        f.write(f'# nan_count {int(np.isnan(v).sum())}\n')
    print(name, len(raw), idx[i], 'nan', int(np.isnan(v).sum()), m['packingType'])
