#!/usr/bin/env python3
"""Builds the mod's river width file (src/main/resources/orbis-river-widths.bin.gz) from GRWL.

GRWL, the Global River Widths from Landsat database (Allen & Pavelsky 2018, Science; CC BY 4.0), measures every river
30 m and wider on Earth. Its summary statistics (V01.02, 43 MB) give each stretch of about 20 km its median width. The
mod reads the file to give OpenStreetMap river lines without a width tag their real width.

This tool downloads the summary once (into --data), drops the lake and reservoir stretches, simplifies each line
(Douglas-Peucker, 400 m: the mod only needs to find the stretch an OSM river follows), and writes:

    'GRWL' int32 version (1), int32 stretches, float64 quantum (degrees)
    per stretch: varint width (m), varint points, then zigzag varint deltas of lon and lat in quanta

all gzipped. Run from the repository root:

    python tools/river_widths.py --data <a folder for the download>

No packages needed beyond Python 3.
"""
import argparse
import gzip
import io
import math
import os
import struct
import urllib.request
import zipfile

URL = "https://zenodo.org/api/records/18624542/files/GRWL_summaryStats_V01.02.zip/content"
OUT = os.path.join("src", "main", "resources", "orbis-river-widths.bin.gz")
TOLERANCE_M = 400.0
QUANTUM = 3e-4
MIN_WIDTH_M = 30.0


def simplify(pts, tol):
    if len(pts) < 3:
        return pts
    kx = 111320 * math.cos(math.radians(pts[0][1]))
    ky = 110574
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a][0] * kx, pts[a][1] * ky
        dx, dy = pts[b][0] * kx - ax, pts[b][1] * ky - ay
        length2 = dx * dx + dy * dy
        best, at = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i][0] * kx - ax, pts[i][1] * ky - ay
            t = 0 if length2 == 0 else max(0.0, min(1.0, (px * dx + py * dy) / length2))
            d = (px - t * dx) ** 2 + (py - t * dy) ** 2
            if d > best:
                best, at = d, i
        if best > tol * tol:
            keep[at] = True
            stack += [(a, at), (at, b)]
    return [p for p, k in zip(pts, keep) if k]


def varint(v):
    out = bytearray()
    while True:
        c = v & 0x7F
        v >>= 7
        if v:
            out.append(c | 0x80)
        else:
            out.append(c)
            return bytes(out)


def zigzag(v):
    return varint((v << 1) ^ (v >> 63))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, help="folder for the GRWL download (43 MB)")
    args = ap.parse_args()
    os.makedirs(args.data, exist_ok=True)
    zpath = os.path.join(args.data, "GRWL_summaryStats_V01.02.zip")
    if not os.path.exists(zpath):
        print("Downloading GRWL summary statistics (43 MB) ...")
        req = urllib.request.Request(URL, headers={"User-Agent": "Orbis-Terrarum-tools/1.1"})
        with urllib.request.urlopen(req) as r, open(zpath + ".part", "wb") as f:
            expected = int(r.headers.get("Content-Length", "0"))
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
        got = os.path.getsize(zpath + ".part")
        if expected and got != expected:
            raise SystemExit("Download stopped at %d of %d bytes: run again" % (got, expected))
        os.replace(zpath + ".part", zpath)
    z = zipfile.ZipFile(zpath)
    shp = z.read("GRWL_summaryStats.shp")
    dbf = z.read("GRWL_summaryStats.dbf")
    header_len = struct.unpack("<H", dbf[8:10])[0]
    record_len = struct.unpack("<H", dbf[10:12])[0]
    # Field offsets in a record (after its deletion flag): OBJECTID 10, ID 10, width_min_ 10, width_med_ 19, width_mean 19,
    # width_max_ 10, width_sd_m 19, lakeFlag 19.
    def record(i):
        r = dbf[header_len + i * record_len + 1: header_len + (i + 1) * record_len]
        return float(r[30:49]), float(r[97:116])

    body = io.BytesIO()
    count = 0
    p, i = 100, 0
    while p < len(shp):
        content = struct.unpack(">i", shp[p + 4:p + 8])[0] * 2
        shape_type = struct.unpack("<i", shp[p + 8:p + 12])[0]
        width, lake = record(i)
        i += 1
        if shape_type == 3 and lake == 0 and width >= MIN_WIDTH_M:
            nparts, npts = struct.unpack("<ii", shp[p + 44:p + 52])
            parts = struct.unpack("<%di" % nparts, shp[p + 52:p + 52 + 4 * nparts])
            base = p + 52 + 4 * nparts
            pts = [struct.unpack("<2d", shp[base + 16 * k:base + 16 * k + 16]) for k in range(npts)]
            for pi in range(nparts):
                line = simplify(pts[parts[pi]:(parts[pi + 1] if pi + 1 < nparts else npts)], TOLERANCE_M)
                if len(line) < 2:
                    continue
                count += 1
                body.write(varint(int(round(width))))
                body.write(varint(len(line)))
                lx = ly = 0
                for x, y in line:
                    qx, qy = round(x / QUANTUM), round(y / QUANTUM)
                    body.write(zigzag(qx - lx) + zigzag(qy - ly))
                    lx, ly = qx, qy
        p += 8 + content
    data = b"GRWL" + struct.pack("<iid", 1, count, QUANTUM) + body.getvalue()
    with open(OUT, "wb") as f:
        f.write(gzip.compress(data, 9, mtime=0))
    print("%d stretches written to %s (%.1f MB)" % (count, OUT, os.path.getsize(OUT) / 1e6))


if __name__ == "__main__":
    main()
