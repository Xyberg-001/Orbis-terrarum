"""
Packs GlaThiDa (the Glacier Thickness Database, WGMS, CC BY 4.0) into src/main/resources/orbis-glathida.tsv.gz: one row per
survey with the place of its deepest reading (a point on the ice, which Glaciers matches to an RGI 7 outline), the
greatest thickness measured (the larger of the survey's stated maximum and its deepest reading) and the stated mean.

    python tools/glathida_pack.py <glathida-3.1.0.zip>

GlaThiDa Consortium (2020): Glacier Thickness Database 3.1.0. World Glacier Monitoring Service, Zurich, Switzerland.
DOI: 10.5904/wgms-glathida-2020-10. Download: https://www.gtn-g.ch/database/glathida-3.1.0.zip (47 MB).
Columns out: lat, lon, max_m, mean_m (blank when not stated), year, readings, name.
"""
import csv, gzip, io, os, sys, zipfile

src = sys.argv[1]
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
out = os.path.join(root, 'src', 'main', 'resources', 'orbis-glathida.tsv.gz')
z = zipfile.ZipFile(src)
base = next(n for n in z.namelist() if n.endswith('data/T.csv'))[:-len('T.csv')]


def num(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


surveys = {}
with z.open(base + 'T.csv') as f:
    for row in csv.DictReader(io.TextIOWrapper(f, encoding='utf-8')):
        sid = row['GlaThiDa_ID']
        surveys[sid] = {
            'lat': num(row['LAT']), 'lon': num(row['LON']), 'max': num(row['MAXIMUM_THICKNESS']), 'mean': num(row['MEAN_THICKNESS']),
            'year': (row['SURVEY_DATE'] or '')[:4], 'name': (row['GLACIER_NAME'] or '').replace('\t', ' ').strip(),
            'flag': row.get('DATA_FLAG') or '', 'deep': None, 'n': 0,
        }

# The deepest reading of each survey, and how many there were (293 MB read as a stream).
with z.open(base + 'TTT.csv') as f:
    for row in csv.DictReader(io.TextIOWrapper(f, encoding='utf-8')):
        s = surveys.get(row['GlaThiDa_ID'])
        if s is None:
            continue
        t, la, lo = num(row['THICKNESS']), num(row['POINT_LAT']), num(row['POINT_LON'])
        if t is None or la is None or lo is None or t <= 0:
            continue
        s['n'] += 1
        if s['deep'] is None or t > s['deep'][0]:
            s['deep'] = (t, la, lo)

rows = []
for sid, s in surveys.items():
    if s['flag'].strip():
        continue  # flagged as doubtful by the database
    deepest = s['deep'][0] if s['deep'] else None
    mx = max(v for v in (s['max'], deepest) if v is not None) if (s['max'] is not None or deepest is not None) else None
    if mx is None or mx <= 0:
        continue
    lat, lon = (s['deep'][1], s['deep'][2]) if s['deep'] else (s['lat'], s['lon'])
    if lat is None or lon is None:
        continue
    year = s['year'] if s['year'].isdigit() else ''
    mean = '' if s['mean'] is None else '%.0f' % s['mean']
    rows.append('%.5f\t%.5f\t%.0f\t%s\t%s\t%d\t%s' % (lat, lon, mx, mean, year, s['n'], s['name']))

rows.sort()
data = ('# GlaThiDa 3.1.0 (WGMS, CC BY 4.0, doi:10.5904/wgms-glathida-2020-10): lat lon max_m mean_m year readings name\n'
        + '\n'.join(rows) + '\n').encode('utf-8')
with gzip.open(out, 'wb', compresslevel=9) as g:
    g.write(data)
print(len(surveys), 'surveys,', len(rows), 'kept,', os.path.getsize(out), 'bytes ->', out)
