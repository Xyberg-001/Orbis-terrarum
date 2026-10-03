#!/usr/bin/env python3
"""Measured building heights and flat roofs from Germany's LoD2 3D building models, for an area, into the mod.

Every German state publishes its LoD2 buildings (roof shapes from airborne lidar) as open data. This tool reads
the states that serve them as tiles in the UTM grid: North Rhine-Westphalia (1 km tiles, dl-de/zero-2.0) and
Bavaria (2 km tiles, CC BY 4.0, Bayerische Vermessungsverwaltung). The files are big (a city tile is 50-160 MB),
so each is streamed and only every building part's footprint, measured height and flat-or-not roof is kept, in
the mod's building-database tiles (building-db-cache/de-lod2/), a few megabytes for a whole city. The mod then
gives each OSM building without a height tag its LoD2 height (by footprint), before the global building atlas.

    python tools/lod2_heights.py --bbox 50.90 6.90 50.98 7.02 --out <instance>/config/orbisterrarum/building-db-cache/de-lod2

(or the data folder you chose in the mod's Storage tab). Run it again for a neighbouring box: tiles already
imported are merged, not repeated. Add --keep to keep the downloaded GML files in --src for a later run.
"""
import argparse
import math
import os
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

UA = "Orbis-Terrarum-tools/0.3"
TILE = 0.01  # the mod's building-database tiles

# State, tile size (km), URL from the tile's south-west corner (E, N in km), state box (S, W, N, E) for picking.
STATES = [
    ("North Rhine-Westphalia", 1, "https://www.opengeodata.nrw.de/produkte/geobasis/3dg/lod2_gml/lod2_gml/LoD2_32_{e}_{n}_1_NW.gml",
     (50.32, 5.86, 52.53, 9.46)),
    ("Bavaria", 2, "https://download1.bayernwolke.de/a/lod2/citygml/{e}_{n}.gml", (47.27, 8.97, 50.57, 13.84)),
]

# ETRS89 / UTM zone 32N on GRS80 (Krueger series, good to well under a millimetre)
A, F = 6378137.0, 1 / 298.257222101
N_ = F / (2 - F)
AA = A / (1 + N_) * (1 + N_ ** 2 / 4 + N_ ** 4 / 64)
K0, LON0 = 0.9996, math.radians(9)
ALPHA = [N_ / 2 - 2 * N_ ** 2 / 3 + 5 * N_ ** 3 / 16, 13 * N_ ** 2 / 48 - 3 * N_ ** 3 / 5, 61 * N_ ** 3 / 240]
BETA = [N_ / 2 - 2 * N_ ** 2 / 3 + 37 * N_ ** 3 / 96, N_ ** 2 / 48 + N_ ** 3 / 15, 17 * N_ ** 3 / 480]
DELTA = [2 * N_ - 2 * N_ ** 2 / 3 - 2 * N_ ** 3, 7 * N_ ** 2 / 3 - 8 * N_ ** 3 / 5, 56 * N_ ** 3 / 15]


def to_utm(lat, lon):
    phi, lam = math.radians(lat), math.radians(lon) - LON0
    t = math.sinh(math.atanh(math.sin(phi)) - 2 * math.sqrt(N_) / (1 + N_) * math.atanh(2 * math.sqrt(N_) / (1 + N_) * math.sin(phi)))
    xi, eta = math.atan2(t, math.cos(lam)), math.atanh(math.sin(lam) / math.sqrt(1 + t * t))
    e = eta + sum(ALPHA[j] * math.cos(2 * (j + 1) * xi) * math.sinh(2 * (j + 1) * eta) for j in range(3))
    n = xi + sum(ALPHA[j] * math.sin(2 * (j + 1) * xi) * math.cosh(2 * (j + 1) * eta) for j in range(3))
    return 500000 + K0 * AA * e, K0 * AA * n


def to_latlon(x, y):
    xi, eta = y / (K0 * AA), (x - 500000) / (K0 * AA)
    xi1 = xi - sum(BETA[j] * math.sin(2 * (j + 1) * xi) * math.cosh(2 * (j + 1) * eta) for j in range(3))
    eta1 = eta - sum(BETA[j] * math.cos(2 * (j + 1) * xi) * math.sinh(2 * (j + 1) * eta) for j in range(3))
    chi = math.asin(math.sin(xi1) / math.cosh(eta1))
    phi = chi + sum(DELTA[j] * math.sin(2 * (j + 1) * chi) for j in range(3))
    lam = LON0 + math.atan2(math.sinh(eta1), math.cos(xi1))
    return math.degrees(phi), math.degrees(lam)


def local(tag):
    return tag.rsplit('}', 1)[-1]


def parts_of(building):
    """The building's parts if it has any, else the building itself."""
    parts = [el for el in building.iter() if local(el.tag) == "BuildingPart"]
    return parts or [building]


def footprint(el):
    """The first ground surface's ring as (x, y) pairs."""
    for gs in el.iter():
        if local(gs.tag) != "GroundSurface":
            continue
        for pl in gs.iter():
            if local(pl.tag) in ("posList", "coordinates"):
                v = [float(t) for t in pl.text.replace(',', ' ').split()]
                dim = int(pl.get("srsDimension", "3"))
                return [(v[i], v[i + 1]) for i in range(0, len(v) - dim + 1, dim)]
        pts = [el2.text for el2 in gs.iter() if local(el2.tag) == "pos"]
        if pts:
            return [tuple(float(t) for t in p.split()[:2]) for p in pts]
    return None


def child_text(el, name):
    for c in el:
        if local(c.tag) == name and c.text:
            return c.text.strip()
    return None


def read_tile(stream, out):
    """Streams one LoD2 file: each building part's footprint, measured height and whether its roof is flat."""
    n = 0
    for _, el in ET.iterparse(stream, events=("end",)):
        if local(el.tag) != "Building":
            continue
        for part in parts_of(el):
            h = child_text(part, "measuredHeight") or child_text(el, "measuredHeight")
            roof = child_text(part, "roofType") or child_text(el, "roofType")
            ring = footprint(part)
            if not h or not ring or len(ring) < 3:
                continue
            try:
                height = float(h)
            except ValueError:
                continue
            if not (2 <= height < 400):
                continue
            ll = [to_latlon(x, y) for x, y in ring]
            out.append((height, roof == "1000", ll))  # AdV roof code 1000: flat
            n += 1
        el.clear()
    return n


def tiles_for(state, box):
    s, w, n, e = box
    size = state[1]
    (x0, y0), (x1, y1) = to_utm(s, w), to_utm(n, e)
    (x2, y2), (x3, y3) = to_utm(s, e), to_utm(n, w)
    xs, ys = [x0, x1, x2, x3], [y0, y1, y2, y3]
    k0, k1 = int(min(xs) // 1000) // size * size, int(max(xs) // 1000) // size * size
    m0, m1 = int(min(ys) // 1000) // size * size, int(max(ys) // 1000) // size * size
    return [(k, m) for k in range(k0, k1 + 1, size) for m in range(m0, m1 + 1, size)]


def write_tiles(out_dir, buildings):
    """Into the mod's 0.01-degree tiles: merged with what an earlier run wrote (same footprint start: same building)."""
    by_tile = {}
    for height, flat, ll in buildings:
        clat, clon = sum(p[0] for p in ll) / len(ll), sum(p[1] for p in ll) / len(ll)
        key = (math.floor(clat / TILE), math.floor(clon / TILE))
        line = "%.1f 0 %d %s" % (height, 1 if flat else 0, " ".join("%.6f %.6f" % p for p in ll))
        by_tile.setdefault(key, {})["%.6f %.6f" % ll[0]] = line
    os.makedirs(out_dir, exist_ok=True)
    for (ty, tx), lines in by_tile.items():
        path = os.path.join(out_dir, "%d_%d.txt" % (ty, tx))
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                for line in f:
                    p = line.split(" ")
                    if len(p) >= 9:
                        lines.setdefault(p[3] + " " + p[4].strip(), line.rstrip("\n"))
        with open(path + ".tmp", "w", encoding="utf-8") as f:
            f.write("\n".join(lines.values()) + "\n")
        os.replace(path + ".tmp", path)
    return len(by_tile)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", nargs=4, type=float, required=True, metavar=("SOUTH", "WEST", "NORTH", "EAST"))
    ap.add_argument("--out", required=True, help="the mod's building-db-cache/de-lod2 folder")
    ap.add_argument("--src", default=None, help="where to keep downloaded GML files with --keep")
    ap.add_argument("--keep", action="store_true", help="keep the downloaded GML files (they are big)")
    a = ap.parse_args()
    s, w, n, e = a.bbox
    src = a.src or os.path.join(os.path.dirname(os.path.abspath(a.out)), "..", "lod2-src")
    total, files = [], 0
    for state in STATES:
        bs, bw, bn, be = state[3]
        if n < bs or s > bn or e < bw or w > be:
            continue
        for k, m in tiles_for(state, (s, w, n, e)):
            url = state[2].format(e=k, n=m)
            local_file = os.path.join(src, os.path.basename(url))
            try:
                if os.path.exists(local_file):
                    stream = open(local_file, "rb")
                elif a.keep:
                    os.makedirs(src, exist_ok=True)
                    print("  downloading %s ..." % url, flush=True)
                    urllib.request.urlretrieve(url, local_file)
                    stream = open(local_file, "rb")
                else:
                    print("  streaming %s ..." % url, flush=True)
                    stream = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=120)
                with stream:
                    got = read_tile(stream, total)
                files += 1
                print("    %s %d_%d: %d building parts" % (state[0], k, m, got), flush=True)
            except urllib.error.HTTPError as ex:
                if ex.code != 404:  # 404: the tile lies outside the state (its box is wider)
                    print("    %s: HTTP %d" % (url, ex.code), file=sys.stderr)
            except (urllib.error.URLError, ET.ParseError, OSError) as ex:
                print("    %s: %s" % (url, ex), file=sys.stderr)
    if not total:
        print("No LoD2 buildings found for that box (North Rhine-Westphalia and Bavaria only).")
        return 1
    # Only what lies in the box (tiles reach beyond it).
    inside = [b for b in total if any(s <= p[0] <= n and w <= p[1] <= e for p in b[2])]
    cells = write_tiles(a.out, inside)
    print("Wrote %d building parts from %d files into %d tiles in %s" % (len(inside), files, cells, os.path.abspath(a.out)))
    print("Data: Geobasis NRW (dl-de/zero-2.0), Bayerische Vermessungsverwaltung (CC BY 4.0).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
