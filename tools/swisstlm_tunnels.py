"""
Packs Switzerland's surveyed road tunnels into src/main/resources/orbis-ch-tunnels.txt.gz: every road piece of
swissTLM3D (swisstopo's 3D landscape model) marked KUNSTBAUTE = Tunnel, as lines of lat,lon,height (metres above sea,
LN02), for osm/TunnelSurveys to shape tunnel floors with like NVDB's in Norway.

    python tools/swisstlm_tunnels.py [release]          (default 2026-02)

Only the road layer is read, straight out of the 3.4 GB Shapefile zip with range requests (about 490 MB streamed: the
attribute table, then the geometry), nothing kept on disk but the result. LV95 to WGS84 with swisstopo's approximate
formulas (about a metre). Source: swisstopo, swissTLM3D, free geodata (open use, the source must be named).
"""
import gzip, io, math, os, struct, sys, time, urllib.request, zipfile

RELEASE = sys.argv[1] if len(sys.argv) > 1 else '2026-02'
URL = f'https://data.geo.admin.ch/ch.swisstopo.swisstlm3d/swisstlm3d_{RELEASE}/swisstlm3d_{RELEASE}_2056_5728.shp.zip'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'orbis-ch-tunnels.txt.gz')


class HttpFile(io.RawIOBase):
    """A file on a web server read with range requests, for zipfile."""

    def __init__(self, url):
        self.url, self.pos = url, 0
        r = urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=60)
        self.size = int(r.headers['Content-Length'])

    def seekable(self):
        return True

    def readable(self):
        return True

    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos

    def tell(self):
        return self.pos

    def readinto(self, b):
        n = min(len(b), self.size - self.pos)
        if n <= 0:
            return 0
        for attempt in range(5):
            try:
                req = urllib.request.Request(self.url, headers={'Range': 'bytes=%d-%d' % (self.pos, self.pos + n - 1)})
                data = urllib.request.urlopen(req, timeout=120).read()
                break
            except OSError:
                if attempt == 4:
                    raise
                time.sleep(3 * (attempt + 1))
        b[:len(data)] = data
        self.pos += len(data)
        return len(data)


def lv95_to_wgs84(e, n):
    y, x = (e - 2_600_000) / 1e6, (n - 1_200_000) / 1e6
    lam = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y ** 3
    phi = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x - 0.0140 * x ** 3
    return phi * 100 / 36, lam * 100 / 36


def simplify(pts, tol):
    """Douglas-Peucker on (east, north, 3 x height): a point stays when leaving it out moves the line more than tol."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay, az = pts[a][0], pts[a][1], 3 * pts[a][2]
        bx, by, bz = pts[b][0], pts[b][1], 3 * pts[b][2]
        dx, dy, dz = bx - ax, by - ay, bz - az
        ll = dx * dx + dy * dy + dz * dz
        best, bi = -1.0, -1
        for i in range(a + 1, b):
            px, py, pz = pts[i][0] - ax, pts[i][1] - ay, 3 * pts[i][2] - az
            t = 0 if ll == 0 else max(0.0, min(1.0, (px * dx + py * dy + pz * dz) / ll))
            d = math.dist((px, py, pz), (t * dx, t * dy, t * dz))
            if d > best:
                best, bi = d, i
        if best > tol:
            keep[bi] = True
            stack += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]


f = HttpFile(URL)
z = zipfile.ZipFile(io.BufferedReader(f, buffer_size=4 << 20))
t0 = time.time()

# ---- the attribute table: which records are tunnels
tunnels = {}
with z.open('TLM_STRASSEN/swissTLM3D_TLM_STRASSE.dbf') as d:
    head = d.read(32)
    count, hlen, rlen = struct.unpack('<IHH', head[4:12])
    rest = d.read(hlen - 32)
    cols, off = {}, 1
    for i in range(0, len(rest) - 1, 32):
        fd = rest[i:i + 32]
        if fd[0] == 0x0D:
            break
        name = fd[:11].split(b'\0')[0].decode('latin1')
        cols[name] = (off, fd[16])
        off += fd[16]
    ko, kl = cols['KUNSTBAUTE']
    oo, ol = cols['OBJEKTART']
    batch = 20000
    for start in range(0, count, batch):
        block = d.read(rlen * min(batch, count - start))
        for i in range(len(block) // rlen):
            rec = block[i * rlen:(i + 1) * rlen]
            if rec[0] == 0x2A:
                continue  # deleted
            if rec[ko:ko + kl].decode('latin1').strip() == 'Tunnel':
                tunnels[start + i + 1] = rec[oo:oo + ol].decode('latin1').strip()
        if start % 400000 == 0:
            print('  attributes %d of %d, %d tunnel pieces, %.0f s' % (start, count, len(tunnels), time.time() - t0), flush=True)
print(len(tunnels), 'tunnel pieces of', count, 'road pieces', flush=True)

# ---- the geometry: their 3D lines
lines, kinds = [], {}
with z.open('TLM_STRASSEN/swissTLM3D_TLM_STRASSE.shp') as s:
    s.read(100)
    seen = 0
    while True:
        h = s.read(8)
        if len(h) < 8:
            break
        num, words = struct.unpack('>ii', h)
        content = s.read(words * 2)
        seen += 1
        if seen % 400000 == 0:
            print('  geometry %d records, %.0f s' % (seen, time.time() - t0), flush=True)
        if num not in tunnels or len(content) < 44:
            continue
        stype = struct.unpack('<i', content[:4])[0]
        if stype != 13:  # PolylineZ
            continue
        nparts, npts = struct.unpack('<ii', content[36:44])
        parts = list(struct.unpack('<%di' % nparts, content[44:44 + 4 * nparts]))
        p = 44 + 4 * nparts
        xy = struct.unpack('<%dd' % (2 * npts), content[p:p + 16 * npts])
        p += 16 * npts + 16
        zs = struct.unpack('<%dd' % npts, content[p:p + 8 * npts])
        parts.append(npts)
        for a, b in zip(parts, parts[1:]):
            pts = [(xy[2 * i], xy[2 * i + 1], zs[i]) for i in range(a, b)]
            if len(pts) >= 2:
                lines.append(simplify(pts, 1.0))
                kinds[tunnels[num]] = kinds.get(tunnels[num], 0) + 1

out = []
for pts in lines:
    ll = []
    for e, n, h in pts:
        lat, lon = lv95_to_wgs84(e, n)
        ll.append('%.6f,%.6f,%.1f' % (lat, lon, h))
    out.append(';'.join(ll))
out.sort()
data = (f'# swissTLM3D {RELEASE} road tunnels (swisstopo, free geodata, source named): lat,lon,height_m;... one line per piece\n'
        + '\n'.join(out) + '\n').encode('utf-8')
with gzip.open(OUT, 'wb', compresslevel=9) as g:
    g.write(data)
print(len(out), 'lines,', sum(len(l.split(';')) for l in out), 'points,', os.path.getsize(OUT), 'bytes ->', os.path.normpath(OUT))
print('by kind:', sorted(kinds.items(), key=lambda kv: -kv[1])[:12])
print('%.0f s' % (time.time() - t0))
