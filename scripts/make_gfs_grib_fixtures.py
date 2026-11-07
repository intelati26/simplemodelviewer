#!/usr/bin/env python3
"""Fetches a few GFS 0.25 degree GRIB2 messages (public bucket noaa-gfs-bdp-pds, byte ranges found with the .idx) and writes, next to each, a list of
sampled cells (`<name>.samples.txt`: column, row from the SOUTH, columns from -180 degrees, value or nan) read with pygrib, to check
core/src/grib_decode.cpp against.   Usage: make_gfs_grib_fixtures.py [gfs.YYYYMMDD/HH/atmos/gfs.tHHz.pgrb2.0p25.fFFF]   Needs: pip install pygrib numpy"""
import os, re, sys, urllib.request
import numpy as np
import pygrib

BUCKET = 'https://noaa-gfs-bdp-pds.s3.amazonaws.com/'
FILE = sys.argv[1] if len(sys.argv) > 1 else 'gfs.20261106/12/atmos/gfs.t12z.pgrb2.0p25.f120'
LEAD = int(FILE[-3:])
FIELDS = {'hgt500': f':HGT:500 mb:{LEAD} hour fcst:$', 'ugrd500': f':UGRD:500 mb:{LEAD} hour fcst:$'}
OUT = os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'gfs')
os.makedirs(OUT, exist_ok=True)
idx = urllib.request.urlopen(BUCKET + FILE + '.idx').read().decode().splitlines()
offsets = [int(l.split(':')[1]) for l in idx]
for name, pattern in FIELDS.items():
    i = [k for k, l in enumerate(idx) if re.search(pattern, l)][0]
    req = urllib.request.Request(BUCKET + FILE, headers={'Range': f'bytes={offsets[i]}-{offsets[i + 1] - 1}'})
    raw = urllib.request.urlopen(req).read()
    path = os.path.join(OUT, f'gfs_{name}.grib2')
    open(path, 'wb').write(raw)
    m = pygrib.open(path)[1]
    v = np.ma.filled(m.values, np.nan).astype(np.float64)[::-1]      # file: north to south, 0..360 E  ->  row 0 = south
    v = np.roll(v, v.shape[1] // 2, axis=1)                          # columns from -180
    rng = np.random.default_rng(5)
    rows, cols = v.shape
    with open(path + '.samples.txt', 'w') as f:
        f.write(f'# {FILE} {idx[i]}\n# size {cols} {rows}\n# packing {m["packingType"]}\n')
        for c, r in [(0, 0), (cols - 1, 0), (0, rows - 1), (cols - 1, rows - 1), (720, 360), (340, 516)] + list(zip(rng.integers(0, cols, 400), rng.integers(0, rows, 400))):
            x = v[r, c]
            f.write(f'{c} {r} {"nan" if np.isnan(x) else repr(float(x))}\n')
        f.write(f'# nan_count {int(np.isnan(v).sum())}\n')
    print(name, len(raw), idx[i])
