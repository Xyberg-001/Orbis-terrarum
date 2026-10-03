#!/usr/bin/env python3
"""Greatest lake depths from GLOBathy for an area, into the mod's lake-depth folder.

GLOBathy (Khazaei et al. 2022, Scientific Data; CC0) estimates the greatest depth of each of the 1.4 million lakes
of HydroLAKES (every lake of 10 ha or more), checked against 1,503 surveyed lakes. The mod shapes each lake as a
bowl down to that depth; without it, it guesses the depth from the lake's size. (Norway's surveyed lakes come from
NVE by themselves and are better still.)

This tool downloads two files once (about 195 MB, kept in --data):
  GLOBathy_basic_parameters.zip  greatest depth per lake   https://doi.org/10.6084/m9.figshare.13402070
  HydroLAKES_points_v10_shp.zip  each lake's outlet and area (HydroSHEDS, CC BY 4.0)  https://www.hydrosheds.org
and writes the lakes whose outlet lies in your box (south, west, north, east) as one JSON file:

    python tools/lake_depths.py --bbox 60.20 5.05 60.55 5.65 --out <instance>/config/orbisterrarum/lake-depths

No packages needed beyond Python 3.
"""
import argparse
import csv
import io
import json
import os
import struct
import sys
import urllib.request
import zipfile

GLOBATHY_URL = "https://ndownloader.figshare.com/files/28919991"
HYDROLAKES_URL = "https://data.hydrosheds.org/file/hydrolakes/HydroLAKES_points_v10_shp.zip"
UA = "Orbis-Terrarum-tools/1.1"


def download(url, path):
    if os.path.exists(path):
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    print(f"downloading {os.path.basename(path)} ...", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r  {done / 1e6:.0f} / {total / 1e6:.0f} MB", end="", flush=True)
    print()
    os.replace(tmp, path)


def hydrolakes_in(zip_path, south, west, north, east):
    """Hylak_id -> (pour lat, pour lon, area km2, name) for the lakes whose outlet is in the box (reads the .dbf)."""
    out = {}
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if n.endswith(".dbf"))
        with z.open(name) as f:
            head = f.read(32)
            nrec, hlen, rlen = struct.unpack("<IHH", head[4:12])
            fields, rest = [], f.read(hlen - 32)
            for i in range(0, len(rest) - 1, 32):
                fd = rest[i:i + 32]
                if fd[0] == 0x0D:
                    break
                fields.append((fd[:11].split(b"\0")[0].decode(), fd[16]))
            offs, pos = {}, 1
            for fname, length in fields:
                offs[fname] = (pos, pos + length)
                pos += length

            def val(rec, key):
                a, b = offs[key]
                return rec[a:b].decode("latin-1").strip()
            for _ in range(nrec):
                rec = f.read(rlen)
                if len(rec) < rlen:
                    break
                try:
                    lat, lon = float(val(rec, "Pour_lat")), float(val(rec, "Pour_long"))
                except ValueError:
                    continue
                if south <= lat <= north and west <= lon <= east:
                    out[int(val(rec, "Hylak_id"))] = (lat, lon, float(val(rec, "Lake_area") or 0), val(rec, "Lake_name"))
    return out


def depths_for(zip_path, ids):
    """Hylak_id -> greatest depth (m) from GLOBathy's parameter tables."""
    out = {}
    with zipfile.ZipFile(zip_path) as z:
        for name in z.namelist():
            if not name.endswith(".csv"):
                continue
            with z.open(name) as f:
                for row in csv.DictReader(io.TextIOWrapper(f, encoding="utf-8", errors="replace")):
                    try:
                        i = int(float(row["Hylak_id"]))
                    except (KeyError, ValueError):
                        continue
                    if i in ids:
                        try:
                            out[i] = float(row["Dmax_use_m"])
                        except (KeyError, ValueError):
                            pass
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("SOUTH", "WEST", "NORTH", "EAST"), required=True)
    ap.add_argument("--out", required=True, help="the mod's lake-depths folder (config/orbisterrarum/lake-depths)")
    ap.add_argument("--data", help="where the two source files are kept (default: lake-depths-src next to --out)")
    a = ap.parse_args()
    south, west, north, east = a.bbox
    data = a.data or os.path.join(os.path.dirname(os.path.abspath(a.out)), "lake-depths-src")
    g_zip = os.path.join(data, "GLOBathy_basic_parameters.zip")
    h_zip = os.path.join(data, "HydroLAKES_points_v10_shp.zip")
    download(GLOBATHY_URL, g_zip)
    download(HYDROLAKES_URL, h_zip)
    print("reading HydroLAKES outlets ...", flush=True)
    lakes = hydrolakes_in(h_zip, south, west, north, east)
    print(f"  {len(lakes)} lakes in the box")
    print("reading GLOBathy depths ...", flush=True)
    depth = depths_for(g_zip, set(lakes))
    rows = []
    for i, (lat, lon, area, name) in lakes.items():
        d = depth.get(i)
        if d is None or d <= 0:
            continue
        rows.append({"id": i, "lat": round(lat, 6), "lon": round(lon, 6), "area_km2": round(area, 4), "dmax_m": round(d, 1), "name": name})
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, "globathy_%.2f_%.2f_%.2f_%.2f.json" % (south, west, north, east))
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False)
    print(f"wrote {len(rows)} lakes to {path}")
    if rows:
        deepest = max(rows, key=lambda r: r["dmax_m"])
        print(f"  deepest: {deepest['name'] or 'unnamed'} {deepest['dmax_m']} m")


if __name__ == "__main__":
    sys.exit(main())
