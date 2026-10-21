#!/usr/bin/env python3
"""Builds the mod's Quebec lake index (src/main/resources/orbis-quebec-lakes.tsv.gz) from GBLQ's download index.

The Géobase des bathymétries de lacs du Québec (GBLQ, Gouvernement du Québec, CC BY 4.0) publishes each surveyed lake's
depth contours as a zip of its own; which lake lies where is only in a 41 MB index. This tool downloads the index once
(into --data), keeps the lakes that have survey data (a GeoPackage zip), and writes one line per lake:

    id <TAB> zip file name <TAB> south <TAB> west <TAB> north <TAB> east

gzipped. The mod fetches a lake's zip the first time a world reaches it. Run from the repository root:

    python tools/quebec_lakes.py --data <a folder for the download>

No packages needed beyond Python 3.
"""
import argparse
import gzip
import json
import math
import os
import urllib.request
import zipfile

URL = "https://stqc380donopppdtce01.blob.core.windows.net/donnees-ouvertes/Bathymetrie/Lacs/DQ/Index_bathymetries_json.zip"
OUT = os.path.join("src", "main", "resources", "orbis-quebec-lakes.tsv.gz")

# NAD83 / Quebec Lambert (EPSG:32198), inverse, as the mod has it (water/QuebecLakes).
A, F = 6378137.0, 1 / 298.257222101
E = math.sqrt(2 * F - F * F)


def _m(phi):
    s = math.sin(phi)
    return math.cos(phi) / math.sqrt(1 - E * E * s * s)


def _t(phi):
    s = math.sin(phi)
    return math.tan(math.pi / 4 - phi / 2) / ((1 - E * s) / (1 + E * s)) ** (E / 2)


P1, P2, P0, L0 = map(math.radians, (60, 46, 44, -68.5))
N = (math.log(_m(P1)) - math.log(_m(P2))) / (math.log(_t(P1)) - math.log(_t(P2)))
FF = _m(P1) / (N * _t(P1) ** N)
RHO0 = A * FF * _t(P0) ** N


def lambert_to_latlon(x, y):
    dy = RHO0 - y
    rho = math.copysign(math.hypot(x, dy), N)
    t = (rho / (A * FF)) ** (1 / N)
    lam = math.atan2(x, dy) / N + L0
    phi = math.pi / 2 - 2 * math.atan(t)
    for _ in range(8):
        s = math.sin(phi)
        phi = math.pi / 2 - 2 * math.atan(t * ((1 - E * s) / (1 + E * s)) ** (E / 2))
    return math.degrees(phi), math.degrees(lam)


def coords(geom):
    """Every (x, y) of a GeoJSON geometry."""
    stack = [geom.get("coordinates")]
    while stack:
        c = stack.pop()
        if not c:
            continue
        if isinstance(c[0], (int, float)):
            yield c[0], c[1]
        else:
            stack.extend(c)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, help="folder for the GBLQ index download (41 MB)")
    args = ap.parse_args()
    os.makedirs(args.data, exist_ok=True)
    zpath = os.path.join(args.data, "Index_bathymetries_json.zip")
    if not os.path.exists(zpath):
        print("Downloading the GBLQ index (41 MB; the province's storage can be slow) ...")
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
    name = next(n for n in z.namelist() if n.lower().endswith((".json", ".geojson")))
    data = json.loads(z.read(name).decode("utf-8-sig"))
    crs = json.dumps(data.get("crs", {}))
    lambert = "32198" in crs
    lines = []
    for f in data["features"]:
        p = f.get("properties") or {}
        url = p.get("URL_GPKG_ZIP") or ""
        if not url or not f.get("geometry"):
            continue
        pts = list(coords(f["geometry"]))
        if not pts:
            continue
        if lambert or abs(pts[0][0]) > 400:
            pts = [lambert_to_latlon(x, y) for x, y in pts]
        else:
            pts = [(y, x) for x, y in pts]
        lats = [a for a, _ in pts]
        lons = [b for _, b in pts]
        lines.append("%s\t%s\t%.5f\t%.5f\t%.5f\t%.5f" % (p.get("NO_LCE_L", ""), url.rsplit("/", 1)[-1],
                                                      min(lats), min(lons), max(lats), max(lons)))
    lines.sort()
    text = "# GBLQ lakes with survey data: id, zip, south, west, north, east (Gouvernement du Québec, CC BY 4.0)\n" + "\n".join(lines) + "\n"
    with open(OUT, "wb") as f:
        f.write(gzip.compress(text.encode("utf-8"), 9, mtime=0))
    print("%d lakes written to %s (%.0f kB)" % (len(lines), OUT, os.path.getsize(OUT) / 1e3))


if __name__ == "__main__":
    main()
