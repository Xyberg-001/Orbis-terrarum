#!/usr/bin/env python3
"""Building heights from the GlobalBuildingAtlas (TUM, GBA.LoD1) for an area, into the mod's height store.

The atlas gives every building on Earth a height (from 3 m satellite height maps, so expect a metre or two
of noise on small houses). Taylor Geospatial mirrors it as public GeoParquet, one file per 5x5-degree tile:
https://source.coop/tge-labs/globalbuildingatlas-lod1 (licence CC BY-NC 4.0: free use, no selling).

This tool reads only the row groups of the tile(s) that overlap your box, using HTTP range requests, so a
city costs a few megabytes even when its tile is ten gigabytes, and writes 0.1-degree cell files under
config/orbisterrarum/heights/. The mod then gives every OpenStreetMap building without a height or level tag
the atlas height (matched by OSM id, or by position for buildings the atlas took from other sources).

    pip install pyarrow
    python tools/gba_heights.py --bbox 60.20 5.05 60.55 5.65 --out <instance>/config/orbisterrarum/heights

Add --download to fetch whole tiles into heights-src/ instead of range-reading them (slower, but works
through proxies that drop range requests).
"""
import argparse
import gzip
import io
import json
import math
import os
import sys
import time
import urllib.request

try:
    import pyarrow.parquet as pq
except ImportError:
    print("pyarrow is needed: pip install pyarrow", file=sys.stderr)
    sys.exit(2)

BASE = "https://data.source.coop/tge-labs/globalbuildingatlas-lod1/"
UA = "Orbis-Terrarum-tools/0.3 (+https://github.com/)"
CELL = 0.1
COLUMNS = ["source", "id", "height", "bbox"]


def tile_name(lon_min, lat_min):
    lon_max, lat_max = lon_min + 5, lat_min + 5
    def lon(v):
        return ("e" if v >= 0 else "w") + "%03d" % abs(v)
    def lat(v):
        return ("n" if v >= 0 else "s") + "%02d" % abs(v)
    return "%s_%s_%s_%s" % (lon(lon_min), lat(lat_max), lon(lon_max), lat(lat_min))


def tiles_for(south, west, north, east):
    names = []
    for lat in range(int(math.floor(south / 5.0)) * 5, int(math.floor((north - 1e-9) / 5.0)) * 5 + 1, 5):
        for lon in range(int(math.floor(west / 5.0)) * 5, int(math.floor((east - 1e-9) / 5.0)) * 5 + 1, 5):
            names.append(tile_name(lon, lat))
    return names


class RangeFile(io.RawIOBase):
    """A read-only, seekable file over HTTP range requests, with a 1 MB block cache (pyarrow reads the footer,
    then only the row groups it is asked for)."""
    BLOCK = 1 << 20

    def __init__(self, url):
        self.url = url
        # Size from a one-byte range request (the mirror answers HEAD with 403).
        req = urllib.request.Request(url, headers={"Range": "bytes=0-0", "User-Agent": UA})
        with urllib.request.urlopen(req, timeout=60) as r:
            cr = r.headers.get("Content-Range") or ""
            if r.status != 206 or "/" not in cr:
                raise IOError("server does not accept range requests")
            self.length = int(cr.rsplit("/", 1)[1])
        self.pos = 0
        self.cache = {}
        self.fetched = 0

    def _block(self, index):
        b = self.cache.get(index)
        if b is None:
            start = index * self.BLOCK
            end = min(self.length, start + self.BLOCK) - 1
            req = urllib.request.Request(self.url, headers={"Range": "bytes=%d-%d" % (start, end), "User-Agent": UA})
            with urllib.request.urlopen(req, timeout=120) as r:
                b = r.read()
            self.cache[index] = b
            self.fetched += len(b)
            if len(self.cache) > 64:
                self.cache.pop(next(iter(self.cache)))
        return b

    def readable(self):
        return True

    def seekable(self):
        return True

    def seek(self, offset, whence=0):
        if whence == 0:
            self.pos = offset
        elif whence == 1:
            self.pos += offset
        else:
            self.pos = self.length + offset
        return self.pos

    def tell(self):
        return self.pos

    def size(self):
        return self.length

    def read(self, n=-1):
        if n is None or n < 0:
            n = self.length - self.pos
        n = max(0, min(n, self.length - self.pos))
        out = bytearray()
        while n > 0:
            index, off = divmod(self.pos, self.BLOCK)
            b = self._block(index)
            chunk = b[off:off + n]
            out += chunk
            self.pos += len(chunk)
            n -= len(chunk)
            if not chunk:
                break
        return bytes(out)

    def readinto(self, buf):
        data = self.read(len(buf))
        buf[:len(data)] = data
        return len(data)


def stat(rg, path):
    for i in range(rg.num_columns):
        c = rg.column(i)
        if c.path_in_schema == path and c.statistics is not None and c.statistics.has_min_max:
            return c.statistics.min, c.statistics.max
    return None


def read_tile(source, south, west, north, east, progress):
    pf = pq.ParquetFile(source)
    md = pf.metadata
    rows = []
    groups = 0
    for i in range(md.num_row_groups):
        rg = md.row_group(i)
        sx = stat(rg, "bbox.xmin")
        sy = stat(rg, "bbox.ymin")
        if sx and sy and (sx[0] > east or sx[1] < west or sy[0] > north or sy[1] < south):
            continue
        groups += 1
        t = pf.read_row_group(i, columns=COLUMNS)
        src = t.column("source").to_pylist()
        ids = t.column("id").to_pylist()
        hs = t.column("height").to_pylist()
        bb = t.column("bbox").to_pylist()
        for s, ident, h, b in zip(src, ids, hs, bb):
            if h is None or b is None:
                continue
            lat = (b["ymin"] + b["ymax"]) / 2.0
            lon = (b["xmin"] + b["xmax"]) / 2.0
            if lat < south or lat > north or lon < west or lon > east:
                continue
            osm_id = 0
            if s == "osm" and ident is not None:
                try:
                    osm_id = int(ident)
                except ValueError:
                    osm_id = 0
            rows.append((osm_id, lat, lon, h))
        progress("  row group %d/%d: %d buildings so far" % (i + 1, md.num_row_groups, len(rows)))
    return rows, groups


def cell_name(lat, lon):
    return "h_%d_%d.jsonl.gz" % (math.floor(lat / CELL), math.floor(lon / CELL))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("SOUTH", "WEST", "NORTH", "EAST"), required=True)
    ap.add_argument("--out", required=True, help="the heights folder (config/orbisterrarum/heights)")
    ap.add_argument("--download", action="store_true", help="download whole tiles into heights-src/ instead of range reads")
    args = ap.parse_args()
    south, west, north, east = args.bbox
    os.makedirs(args.out, exist_ok=True)
    src_dir = os.path.join(os.path.dirname(os.path.abspath(args.out.rstrip("/\\"))), "heights-src")

    names = tiles_for(south, west, north, east)
    print("atlas tiles:", names, flush=True)
    all_rows = []
    t0 = time.time()
    for name in names:
        url = BASE + name + ".parquet"
        try:
            if args.download:
                os.makedirs(src_dir, exist_ok=True)
                path = os.path.join(src_dir, name + ".parquet")
                if not os.path.exists(path):
                    print("downloading", url, flush=True)
                    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=120) as r, open(path + ".part", "wb") as f:
                        while True:
                            chunk = r.read(1 << 20)
                            if not chunk:
                                break
                            f.write(chunk)
                    os.replace(path + ".part", path)
                rows, groups = read_tile(path, south, west, north, east, lambda m: None)
                print("%s: %d buildings from %d row groups" % (name, len(rows), groups), flush=True)
            else:
                rf = RangeFile(url)
                print("%s: %.0f MB on the server, reading the parts that overlap the box..." % (name, rf.length / 1e6), flush=True)
                rows, groups = read_tile(rf, south, west, north, east, lambda m: None)
                print("%s: %d buildings from %d row groups, %.1f MB fetched" % (name, len(rows), groups, rf.fetched / 1e6), flush=True)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                print("%s: no tile on the server (no buildings there)" % name, flush=True)
                continue
            raise
        all_rows.extend(rows)

    # Merge into the cell files (existing entries of a cell are kept unless the same building is written again).
    cells = {}
    for osm_id, lat, lon, h in all_rows:
        cells.setdefault(cell_name(lat, lon), {})[osm_id if osm_id else ("%.6f,%.6f" % (lat, lon))] = (osm_id, lat, lon, h)
    for name, entries in cells.items():
        path = os.path.join(args.out, name)
        if os.path.exists(path):
            with gzip.open(path, "rt", encoding="utf-8") as f:
                for line in f:
                    try:
                        osm_id, lat, lon, h = json.loads(line)
                    except ValueError:
                        continue
                    key = osm_id if osm_id else ("%.6f,%.6f" % (lat, lon))
                    entries.setdefault(key, (osm_id, lat, lon, h))
        tmp = path + ".tmp"
        with gzip.open(tmp, "wt", encoding="utf-8") as f:
            for osm_id, lat, lon, h in entries.values():
                f.write("[%d,%.6f,%.6f,%.1f]\n" % (osm_id, lat, lon, h))
        os.replace(tmp, path)
    print("done: %d buildings into %d cell files under %s (%.0f s)" % (len(all_rows), len(cells), args.out, time.time() - t0), flush=True)


if __name__ == "__main__":
    main()
