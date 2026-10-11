# Orbis Terrarum: internet access and privacy

Orbis Terrarum downloads map data while you play. It sends **no personal data and no telemetry**; what the services receive is the location of the area being generated (tile numbers or a bounding box), the text you type into a place search, and your IP address as with any download. Downloads are cached on disk under `config/orbisterrarum/`.

| Service | Used for | Sent |
|---|---|---|
| Mapterhorn (tiles.mapterhorn.com) | Terrain elevation; the Elevation map layer | Tile coordinates |
| OpenGeoHub (s3.opengeohub.org) | Bare-earth terrain (GEDTM30) where no national survey exists, for worlds made with 1.1.0 or later | Byte ranges of one global file |
| Open Waters Seascape (tiles.openwaters.io) | Sea floor; sea depths on the Elevation map layer | Tile coordinates |
| Geofabrik (download.geofabrik.de) | OpenStreetMap's file for the region holding a new world's area, only when you agree at Create (or press Download map data on the world map); the region list once a month | The region file's name |
| Overpass API mirrors (overpass-api.de, overpass.kumi.systems, overpass.private.coffee, overpass.openstreetmap.fr) | OpenStreetMap buildings, roads, water | Bounding box of the area |
| Nominatim (nominatim.openstreetmap.org), Photon (photon.komoot.io) | Place search and area outlines, only when you search or preview | Your search text / coordinates |
| Esri ArcGIS Online (server.arcgisonline.com) | Aerial-imagery colours; satellite map background in the preview and world map | Tile coordinates |
| OpenFreeMap (tiles.openfreemap.org) | The street map in the preview and world map (OpenStreetMap vector tiles, drawn by the mod) | Tile coordinates |
| National mapping agencies' photo services (swisstopo, PDOK, Digitaal Vlaanderen, SPW Wallonia, geoportail.lu, IGN France, IGN España, Bavaria, NRW, basemap.at, ČÚZK, GURS, Maa- ja Ruumiamet, GSI Japan, NLSC Taiwan, HK Lands Department, NSW Spatial Services, Vicmap, Ontario GEO, MassGIS) | Aerial-imagery colours, only for areas inside that country or state | Tile coordinates / the tile's bounding box |
| ESA WorldCover (AWS S3, eu-central-1) | Land cover | 3° tile name |
| Norwegian Meteorological Institute (api.met.no) | Real weather, while a world runs (every few minutes at most) | Coordinates of the players' area (4 decimals) |
| Geological Survey of Norway (geo.ngu.no) | Norway only: bedrock rock types | Bounding box of the area |
| Macrostrat (tiles.macrostrat.org) | Rock types outside Norway | Tile coordinates |
| NVE (kart.nve.no) | Norway only: depth surveys of lakes | Bounding box of the area / a lake's number |
| Open-Meteo (api.open-meteo.com) | The world's time zone, once per world, for villagers' clock hours; today's snow depth, every 6 hours at most per 25 km cell of the world being generated or played; last winter's precipitation (archive-api.open-meteo.com), once a year per 50 km cell | The world's origin coordinates; the centre of each 0.25° or 0.5° cell |
| National lidar services: swisstopo (data.geo.admin.ch), NRCan HRDEM (datacube.services.geo.ca, Amazon S3 ca-central-1), AHN (service.pdok.nl), IGN (data.geopf.fr), Geobasis NRW (wcs.nrw.de), Maa- ja Ruumiamet (teenus.maaamet.ee), ČÚZK (ags.cuzk.cz), DOGAMI Oregon, KyFromAbove Kentucky, DC GIS, New Brunswick GNB | Measured building heights, only for areas inside that country or state, at 1:4 or finer | Bounding box of the tile |
| Lake surveys: Finland's SYKE (paikkatiedot.ymparisto.fi), Minnesota DNR (enterprise.gisdata.mn.gov), Ontario MNRF (ws.lioservices.lrc.gov.on.ca) | Depth contours of lakes inside that state or province | Bounding box of the area |
| Road databases: IGN BD TOPO (data.geopf.fr), Digiroad (avoinapi.vaylapilvi.fi), FHWA HPMS (geo.dot.gov), BC Digital Road Atlas (openmaps.gov.bc.ca) | Road widths and lanes, only for areas inside that country or state, at 1:2 or finer | Bounding box of the area |
| Building registers: IGN BD TOPO (data.geopf.fr), 3DBAG (data.3dbag.nl), GURS (ipi.eprostor.gov.si), Stadt Wien (data.wien.gv.at), NYC Open Data (data.cityofnewyork.us) | Building heights and floors, only for areas inside that country or city | Bounding box of the area |
| Kartverket / Geonorge (wcs.geonorge.no), Statens vegvesen NVDB (nvdbapiles.atlas.vegvesen.no) | Norway only: lidar building heights (only when switched on), road widths | Bounding box / tile coordinates |
| USGS National Map (imagery.nationalmap.gov) | USA only: NAIP imagery | Tile coordinates |
| Google (8.8.8.8) and Cloudflare (1.1.1.1) DNS-over-HTTPS | Only if your system DNS fails to resolve one of the hosts above | The host name |

A country's own services (photos, rock map, road widths, lake surveys) are only contacted for areas inside that country; the lidar services only when switched on (Advanced tab). Data Sources → Downloads by country shows and deletes what each source keeps on disk. The generation sources (imagery, sea floor, land cover, lidar, road widths, the tile and Overpass URLs) can be switched off or replaced in `config/orbisterrarum/orbisterrarum.json`. The map backgrounds load only while the area preview or world map is open, place search only runs when you search, and the DNS fallback only when your system DNS fails.
