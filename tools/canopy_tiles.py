#!/usr/bin/env python3
"""Turn the Meta / WRI 1 m global canopy height map into Orbis Terrarum canopy tiles.

The source (CC BY 4.0) lives in a public S3 bucket as one 65536 x 65536 GeoTIFF per zoom-9 web-mercator tile
(about 430 MB each, canopy height in whole metres). The mod cannot read those, so this script downloads
the tiles covering a box (resumable, kept in canopy-src/ next to the output), and cuts them into slippy-map
zoom-16 PNG tiles in the terrarium encoding the mod already uses for terrain:
config/orbisterrarum/canopy-cache/16_<x>_<y>.png. The mod then places every tree the map shows.

    pip install rasterio numpy pillow
    python tools/canopy_tiles.py --bbox 60.25 5.10 60.55 5.60 --out <instance>/config/orbisterrarum/canopy-cache

Keep the box tight: every zoom-9 source tile it touches is a 430 MB download.
"""
import argparse
import json
import math
import os
import sys
import time
import urllib.request

import numpy as np
from PIL import Image

try:
    import rasterio
    from rasterio.windows import Window
except ImportError:
    print("rasterio is needed: pip install rasterio", file=sys.stderr)
    sys.exit(2)

BASE = "https://dataforgood-fb-data.s3.amazonaws.com/forests/v1/alsgedi_global_v6_float/"
ZOOM = 16
TILE = 256
R = 6378137.0


def tile_bounds_3857(z, x, y):
    n = 2 ** z
    size = 2 * math.pi * R / n
    west = -math.pi * R + x * size
    east = west + size
    north = math.pi * R - y * size
    south = north - size
    return west, south, east, north


def lat_lon_to_tile(lat, lon, z):
    n = 2 ** z
    xt = (lon + 180.0) / 360.0 * n
    lat_r = math.radians(lat)
    yt = (1 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2 * n
    return xt, yt


def quadkey(x, y, z):
    q = []
    for i in range(z, 0, -1):
        d = 0
        m = 1 << (i - 1)
        if x & m:
            d += 1
        if y & m:
            d += 2
        q.append(str(d))
    return "".join(q)


def load_index(src_dir):
    path = os.path.join(src_dir, "tiles.geojson")
    if not os.path.exists(path):
        print("downloading tile index (15 MB)...", flush=True)
        download(BASE + "tiles.geojson", path)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def source_tiles_for(index, south, west, north, east):
    """Quadkeys of the source tiles whose footprint intersects the box, from the index."""
    keys = []
    for feat in index.get("features", []):
        geom = feat.get("geometry") or {}
        coords = geom.get("coordinates")
        if not coords:
            continue
        ring = coords[0] if geom.get("type") == "Polygon" else coords[0][0]
        lons = [p[0] for p in ring]
        lats = [p[1] for p in ring]
        if max(lons) <= west or min(lons) >= east or max(lats) <= south or min(lats) >= north:
            continue
        props = feat.get("properties") or {}
        key = props.get("tile") or props.get("quadkey") or props.get("name")
        if key:
            keys.append(str(key))
    return sorted(set(keys))


def download(url, path):
    """Streams url to path, resuming a previous partial download."""
    part = path + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    headers = {"Range": "bytes=%d-" % have} if have else {}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as r:
        if have and r.status != 206:
            have = 0  # server ignored the range: start over
        total = have + int(r.headers.get("Content-Length") or 0)
        mode = "ab" if have else "wb"
        t0 = time.time()
        got = have
        last = t0
        with open(part, mode) as f:
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                got += len(chunk)
                if time.time() - last > 5:
                    rate = (got - have) / max(1e-6, time.time() - t0) / 1e6
                    print("  %d / %d MB (%.1f MB/s)" % (got // 1000000, total // 1000000, rate), flush=True)
                    last = time.time()
    os.replace(part, path)


def terrarium(values):
    v = np.clip(values.astype(np.float64), 0, 200) + 32768.0
    r = np.floor(v / 256.0)
    g = np.floor(v - r * 256.0)
    b = np.floor((v - r * 256.0 - g) * 256.0)
    rgb = np.stack([r, g, b], axis=-1).astype(np.uint8)
    return Image.fromarray(rgb, "RGB")


def cut_tiles(path, z, xs, ys, out):
    """Cuts one source GeoTIFF into zoom-z tiles for the tile ranges given; returns the number written."""
    written = 0
    with rasterio.open(path) as ds:
        if ds.crs is None or ds.crs.to_epsg() != 3857:
            print("  skipping", path, ": not web mercator", file=sys.stderr)
            return 0
        b = ds.bounds
        res = ds.res[0]
        tile_m = 2 * math.pi * R / (2 ** z)
        src_px = int(round(tile_m / res))  # source pixels per output tile side
        if src_px < TILE or src_px % TILE != 0:
            print("  skipping", path, ": %d source px per tile does not pool to %d" % (src_px, TILE), file=sys.stderr)
            return 0
        f = src_px // TILE
        rows_here = [ty for ty in ys if tile_bounds_3857(z, 0, ty)[3] > b.bottom and tile_bounds_3857(z, 0, ty)[1] < b.top]
        cols_here = [tx for tx in xs if tile_bounds_3857(z, tx, 0)[2] > b.left and tile_bounds_3857(z, tx, 0)[0] < b.right]
        if not rows_here or not cols_here:
            return 0
        c0 = int(round((tile_bounds_3857(z, cols_here[0], 0)[0] - b.left) / res))
        c1 = int(round((tile_bounds_3857(z, cols_here[-1], 0)[2] - b.left) / res))
        c0c, c1c = max(0, c0), min(ds.width, c1)
        for ty in rows_here:
            targets = [(tx, os.path.join(out, "%d_%d_%d.png" % (z, tx, ty))) for tx in cols_here]
            if all(os.path.exists(t) for _, t in targets):
                continue
            w_, s_, e_, n_ = tile_bounds_3857(z, 0, ty)
            r0 = int(round((b.top - n_) / res))
            r0c, r1c = max(0, r0), min(ds.height, r0 + src_px)
            if r1c <= r0c or c1c <= c0c:
                continue
            band = np.zeros((src_px, c1 - c0), dtype=np.float32)
            data = ds.read(1, window=Window(c0c, r0c, c1c - c0c, r1c - r0c))
            if ds.nodata is not None:
                data = np.where(data == ds.nodata, 0, data)
            band[r0c - r0:r0c - r0 + data.shape[0], c0c - c0:c0c - c0 + data.shape[1]] = data
            pooled = band.reshape(TILE, f, band.shape[1] // f, f).mean(axis=(1, 3))
            for i, (tx, target) in enumerate(targets):
                if os.path.exists(target):
                    continue
                tile = pooled[:, i * TILE:(i + 1) * TILE]
                if tile.max() <= 0:
                    continue  # nothing but ground/water: the mod treats a missing tile as "no canopy data"
                terrarium(tile).save(target + ".tmp.png", optimize=True)
                os.replace(target + ".tmp.png", target)
                written += 1
            print("  row %d: %d tiles so far" % (ty, written), flush=True)
    return written


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("SOUTH", "WEST", "NORTH", "EAST"), required=True)
    ap.add_argument("--out", required=True, help="canopy-cache folder")
    ap.add_argument("--zoom", type=int, default=ZOOM)
    ap.add_argument("--max-tiles", type=int, default=4, help="refuse to download more source tiles than this")
    args = ap.parse_args()
    south, west, north, east = args.bbox
    out = args.out
    src_dir = os.path.join(os.path.dirname(os.path.abspath(out.rstrip("/\\"))), "canopy-src")
    os.makedirs(out, exist_ok=True)
    os.makedirs(src_dir, exist_ok=True)

    index = load_index(src_dir)
    keys = source_tiles_for(index, south, west, north, east)
    if not keys:
        z = 9
        x0, y0 = lat_lon_to_tile(north, west, z)
        x1, y1 = lat_lon_to_tile(south, east, z)
        keys = sorted({quadkey(int(x), int(y), z) for x in range(int(x0), int(x1) + 1) for y in range(int(y0), int(y1) + 1)})
    print("source tiles:", keys, "(about 430 MB each)", flush=True)
    if len(keys) > args.max_tiles:
        print("that is more than --max-tiles %d: tighten the box or raise the limit" % args.max_tiles, file=sys.stderr)
        sys.exit(1)

    sources = []
    for key in keys:
        path = os.path.join(src_dir, key + ".tif")
        if not os.path.exists(path):
            print("downloading", key, "...", flush=True)
            try:
                download(BASE + "chm/" + key + ".tif", path)
            except Exception as e:  # noqa: BLE001
                print("  no tile", key, "(" + str(e) + ")", flush=True)
                continue
        sources.append(path)
    if not sources:
        print("no source tiles cover the box")
        sys.exit(1)

    z = args.zoom
    xa, ya = lat_lon_to_tile(north, west, z)
    xb, yb = lat_lon_to_tile(south, east, z)
    xs = list(range(int(math.floor(xa)), int(math.floor(xb)) + 1))
    ys = list(range(int(math.floor(ya)), int(math.floor(yb)) + 1))
    print("cutting up to %d tiles at zoom %d" % (len(xs) * len(ys), z), flush=True)
    written = 0
    for path in sources:
        print("reading", os.path.basename(path), flush=True)
        written += cut_tiles(path, z, xs, ys, out)
    print("done: %d tiles written to %s" % (written, out), flush=True)


if __name__ == "__main__":
    main()
