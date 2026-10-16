#!/usr/bin/env python3
"""Builds the mod's climate pack (src/main/resources/orbis-climate.bin.gz): the real climate of every place on land.

Sources (both free to redistribute):
  Beck et al. 2023, Köppen-Geiger classes 1991-2020 at 1 km (Scientific Data 10, 724; CC BY 4.0), figshare 21789074:
      reduced to 0.05° by the most common class in each cell.
  CHELSA v2.1 climatologies 1981-2010 at 30 arc-seconds (Karger et al.; CC0), with CHELSA's own elevation grid:
      bio1 (mean annual air temperature), bio11 (mean temperature of the coldest quarter), bio12 (annual precipitation),
      read one row per 0.1° band by range requests (the files are hundreds of MB), averaged over the band's 12 pixels
      in each 0.1° cell. Temperatures are reduced to sea level with 6.5 °C per km using the same pixels' elevation, so
      the mod can put each block's own height back.

Output (gzipped, little-endian):
  'ORCL' int32 version 1
  then four grids, each: int32 width, int32 height, float64 west, float64 north, float64 step, then width*height bytes
  (rows north to south):
    1 Köppen-Geiger class (1-30, Beck's numbering; 0 = no data), 0.05°, 90 N to 90 S
    2 mean annual temperature at sea level: (T + 40) * 2, 0.1°, 84 N to 60 S (255 = no data)
    3 coldest-quarter temperature at sea level, same coding and grid
    4 annual precipitation: round(40 * log10(mm + 1)), same grid (255 = no data)
    5 mean elevation of the cell's land pixels: metres / 25, same grid (255 = no data), so a block can tell how far it
      stands above or below its cell

Run from the repository root (about 190 MB of range requests; slow hosts make it take an hour or so):

    python tools/climate_pack.py --data <a folder for downloads and progress>

Needs numpy and Pillow.
"""
import argparse
import gzip
import io
import json
import math
import os
import struct
import sys
import time
import urllib.request
import zlib
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image

UA = {"User-Agent": "Orbis-Terrarum-tools/1.1"}
BECK_ZIP = "https://ndownloader.figshare.com/files/61012822"
CHELSA = "https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/climatologies/1981-2010/bio/CHELSA_%s_1981-2010_V.2.1.tif"
DEM = "https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/input/dem_latlong.sdat"
OUT = os.path.join("src", "main", "resources", "orbis-climate.bin.gz")
NORTH, SOUTH, STEP = 84.0, -60.0, 0.1
LAPSE = 0.0065


def get(url, start=None, length=None, tries=6):
    for attempt in range(tries):
        try:
            h = dict(UA)
            if start is not None:
                h["Range"] = "bytes=%d-%d" % (start, start + length - 1)
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=120) as r:
                data = r.read()
            if start is not None and len(data) != length:
                raise IOError("short read %d of %d" % (len(data), length))
            return data
        except Exception as e:
            if attempt == tries - 1:
                raise
            time.sleep(2 * (attempt + 1))


# ---------------------------------------------------------------- Beck (one entry of a remote zip)

def beck_tif(data_dir):
    path = os.path.join(data_dir, "kg_1km_1991_2020.tif")
    if os.path.exists(path):
        return path
    print("Köppen-Geiger: reading the zip's directory ...", flush=True)
    tail = get(BECK_ZIP, 130618411 - 65536, 65536)
    eocd = tail.rfind(b"PK\x05\x06")
    cd_size, cd_off = struct.unpack("<II", tail[eocd + 12:eocd + 20])
    if cd_off == 0xFFFFFFFF:  # zip64
        loc = tail.rfind(b"PK\x06\x07")
        z64 = struct.unpack("<Q", tail[loc + 8:loc + 16])[0]
        rec = get(BECK_ZIP, z64, 56)
        cd_size, cd_off = struct.unpack("<QQ", rec[40:56])
    cd = get(BECK_ZIP, cd_off, cd_size)
    p, best = 0, None
    while p < len(cd) and cd[p:p + 4] == b"PK\x01\x02":
        method, = struct.unpack("<H", cd[p + 10:p + 12])
        csize, usize = struct.unpack("<II", cd[p + 20:p + 28])
        nlen, xlen, clen = struct.unpack("<HHH", cd[p + 28:p + 34])
        lho, = struct.unpack("<I", cd[p + 42:p + 46])
        name = cd[p + 46:p + 46 + nlen].decode("utf-8", "replace")
        extra = cd[p + 46 + nlen:p + 46 + nlen + xlen]
        q = 0
        while q + 4 <= len(extra):
            hid, hl = struct.unpack("<HH", extra[q:q + 4])
            if hid == 1:
                vals = list(struct.unpack("<%dQ" % (hl // 8), extra[q + 4:q + 4 + hl - hl % 8]))
                if usize == 0xFFFFFFFF: usize = vals.pop(0)
                if csize == 0xFFFFFFFF: csize = vals.pop(0)
                if lho == 0xFFFFFFFF: lho = vals.pop(0)
            q += 4 + hl
        if "1991_2020" in name and name.endswith(".tif") and (best is None or usize > best[3]):
            best = (name, lho, csize, usize, method)
        p += 46 + nlen + xlen + clen
    name, lho, csize, usize, method = best
    print("Köppen-Geiger: %s (%.1f MB) ..." % (name, csize / 1e6), flush=True)
    lh = get(BECK_ZIP, lho, 30)
    nlen, xlen = struct.unpack("<HH", lh[26:30])
    raw = get(BECK_ZIP, lho + 30 + nlen + xlen, csize)
    data = zlib.decompressobj(-15).decompress(raw) if method == 8 else raw
    open(path, "wb").write(data)
    return path


def beck_grid(tif):
    Image.MAX_IMAGE_PIXELS = None
    a = np.array(Image.open(tif), dtype=np.uint8)  # 21600 x 43200, 90 N to 90 S
    f = 6  # 0.05° cells
    h, w = a.shape[0] // f, a.shape[1] // f
    out = np.zeros((h, w), np.uint8)
    for r in range(h):
        block = a[r * f:(r + 1) * f].reshape(f, w, f).transpose(1, 0, 2).reshape(w, f * f)
        counts = np.zeros((w, 31), np.int32)
        for c in range(1, 31):
            counts[:, c] = (block == c).sum(axis=1)
        best = counts.argmax(axis=1)
        best[counts.max(axis=1) == 0] = 0
        out[r] = best
    return out


# ---------------------------------------------------------------- CHELSA rows

def tiff_layout(url):
    head = get(url, 0, 16)
    bo = "<" if head[:2] == b"II" else ">"
    big = struct.unpack(bo + "H", head[2:4])[0] == 43
    ifd = struct.unpack(bo + ("Q" if big else "I"), head[8:16] if big else head[4:8])[0]
    n = struct.unpack(bo + ("Q" if big else "H"), get(url, ifd, 8 if big else 2))[0]
    es = 20 if big else 12
    ent = get(url, ifd + (8 if big else 2), n * es)
    tags = {}
    sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 11: 4, 12: 8, 16: 8, 17: 1, 18: 8}
    fmts = {1: "B", 2: "s", 3: "H", 4: "I", 11: "f", 12: "d", 16: "Q", 17: "b", 18: "q"}
    for i in range(n):
        e = ent[i * es:(i + 1) * es]
        if big:
            tag, typ, cnt = struct.unpack(bo + "HHQ", e[:12]); val = e[12:20]
        else:
            tag, typ, cnt = struct.unpack(bo + "HHI", e[:8]); val = e[8:12]
        tot = sizes.get(typ, 1) * cnt
        raw = val[:tot] if tot <= len(val) else get(url, struct.unpack(bo + ("Q" if big else "I"), val)[0], tot)
        tags[tag] = raw if typ == 2 else struct.unpack(bo + fmts.get(typ, "B") * cnt, raw)
    return {
        "bo": bo, "w": tags[256][0], "h": tags[257][0], "bits": tags[258][0], "fmt": tags.get(339, (1,))[0],
        "comp": tags[259][0], "pred": tags.get(317, (1,))[0], "rps": tags.get(278, (1,))[0],
        "off": tags[273], "cnt": tags[279], "scale": tags[33550], "tie": tags[33922],
        "nodata": tags[42113].decode().strip("\0 ") if 42113 in tags else None,
    }


def tiff_row(url, lay, row):
    k = row // lay["rps"]
    raw = zlib.decompress(get(url, lay["off"][k], lay["cnt"][k])) if lay["comp"] in (8, 32946) else get(url, lay["off"][k], lay["cnt"][k])
    dt = {(16, 1): "u2", (16, 2): "i2", (32, 1): "u4", (32, 2): "i4", (32, 3): "f4"}[(lay["bits"], lay["fmt"])]
    a = np.frombuffer(raw, dtype=np.dtype(dt).newbyteorder(lay["bo"])).reshape(-1, lay["w"])[row % lay["rps"]].copy()
    if lay["pred"] == 2:
        a = np.cumsum(a, dtype=a.dtype)
    return a


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True)
    args = ap.parse_args()
    os.makedirs(args.data, exist_ok=True)

    kg_file = os.path.join(args.data, "kg_0p05.npy")
    if os.path.exists(kg_file):
        kg = np.load(kg_file)
    else:
        kg = beck_grid(beck_tif(args.data))
        np.save(kg_file, kg)
    print("Köppen-Geiger grid", kg.shape, flush=True)

    layouts = {v: tiff_layout(CHELSA % v) for v in ("bio1", "bio11", "bio12")}
    lay = layouts["bio1"]
    x0, y0 = lay["tie"][3], lay["tie"][4]
    px = lay["scale"][0]
    print("CHELSA grid %dx%d from %.4f, %.4f, pixel %.6f; nodata %s" % (lay["w"], lay["h"], x0, y0, px, lay["nodata"]), flush=True)
    dem_x0, dem_y0, dem_cs, dem_w = -179.9959722222, -89.9959722222, 0.0083333333, 43200

    bands = int(round((NORTH - SOUTH) / STEP))
    cells = int(round(360 / STEP))
    per = int(round(STEP / px))
    t0 = np.full((bands, cells), 255, np.uint8)
    tc = np.full((bands, cells), 255, np.uint8)
    pr = np.full((bands, cells), 255, np.uint8)
    el = np.full((bands, cells), 255, np.uint8)
    progress = os.path.join(args.data, "climate_rows.npz")
    done = set()
    if os.path.exists(progress):
        z = np.load(progress)
        t0, tc, pr, el = z["t0"], z["tc"], z["pr"], z["el"]
        done = set(z["done"].tolist())

    def band(j):
        lat = NORTH - (j + 0.5) * STEP
        row = int((y0 - lat) / px)
        if row < 0 or row >= lay["h"]:
            return j, None
        rows = {v: tiff_row(CHELSA % v, layouts[v], row) for v in layouts}
        drow = int(round((lat - dem_y0) / dem_cs))
        dem = np.frombuffer(get(DEM, drow * dem_w * 2, dem_w * 2), dtype="<i2").astype(np.float64)
        dem_lon = dem_x0 + np.arange(dem_w) * dem_cs
        lon = x0 + (np.arange(lay["w"]) + 0.5) * px
        elev = np.interp(lon, dem_lon, dem)
        elev[elev < -1000] = 0
        # Land: where the temperature has a value (the sea is 65535, the uint16 maximum); the same pixels for all three.
        land = (rows["bio1"] > 0) & (rows["bio1"] < 65535)
        out = []
        for v, scale, offset in (("bio1", 0.1, -273.15), ("bio11", 0.1, -273.15), ("bio12", 0.1, 0.0)):
            a = rows[v].astype(np.float64)
            valid = land & (rows[v] < 65535)
            val = a * scale + offset
            if v != "bio12":
                val = val + LAPSE * elev  # to sea level
            n = (lay["w"] // per) * per
            vv = np.where(valid, val, 0)[:n].reshape(-1, per)
            cnt = valid[:n].reshape(-1, per).sum(axis=1)
            mean = np.where(cnt > 0, vv.sum(axis=1) / np.maximum(cnt, 1), np.nan)
            out.append(mean)
        ev = np.where(land, elev, 0)[:n].reshape(-1, per)
        cnt = land[:n].reshape(-1, per).sum(axis=1)
        out.append(np.where(cnt > 0, ev.sum(axis=1) / np.maximum(cnt, 1), np.nan))
        return j, out

    todo = [j for j in range(bands) if j not in done]
    print("%d of %d bands to read" % (len(todo), bands), flush=True)
    t_start = time.time()
    with ThreadPoolExecutor(6) as pool:
        for i, (j, res) in enumerate(pool.map(band, todo)):
            if res is not None:
                m_t, m_c, m_p, m_e = res
                n = min(cells, len(m_t))
                ok = ~np.isnan(m_t[:n])
                t0[j, :n][ok] = np.clip(np.round((m_t[:n][ok] + 40) * 2), 0, 254)
                okc = ~np.isnan(m_c[:n])
                tc[j, :n][okc] = np.clip(np.round((m_c[:n][okc] + 40) * 2), 0, 254)
                okp = ~np.isnan(m_p[:n])
                pr[j, :n][okp] = np.clip(np.round(40 * np.log10(m_p[:n][okp] + 1)), 0, 254)
                oke = ~np.isnan(m_e[:n])
                el[j, :n][oke] = np.clip(np.round(np.maximum(m_e[:n][oke], 0) / 25), 0, 254)
            done.add(j)
            if i % 20 == 19:
                np.savez(progress, t0=t0, tc=tc, pr=pr, el=el, done=np.array(sorted(done)))
                spent = time.time() - t_start
                print("  %d/%d bands, %.0f s, about %.0f min left" % (i + 1, len(todo), spent, spent / (i + 1) * (len(todo) - i - 1) / 60), flush=True)
    np.savez(progress, t0=t0, tc=tc, pr=pr, el=el, done=np.array(sorted(done)))

    buf = io.BytesIO()
    buf.write(b"ORCL" + struct.pack("<i", 1))
    for grid, west, north, step in ((kg, -180.0, 90.0, 0.05), (t0, -180.0, NORTH, STEP), (tc, -180.0, NORTH, STEP), (pr, -180.0, NORTH, STEP), (el, -180.0, NORTH, STEP)):
        buf.write(struct.pack("<iiddd", grid.shape[1], grid.shape[0], west, north, step))
        buf.write(np.ascontiguousarray(grid, np.uint8).tobytes())
    with open(OUT, "wb") as f:
        f.write(gzip.compress(buf.getvalue(), 9, mtime=0))
    print("Written %s (%.2f MB)" % (OUT, os.path.getsize(OUT) / 1e6))


if __name__ == "__main__":
    main()
