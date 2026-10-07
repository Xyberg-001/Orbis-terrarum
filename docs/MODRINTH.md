# Modrinth upload kit — Orbis Terrarum 1.1.0

Everything to paste into Modrinth's project settings and "Upload version" forms.
The description below is the project body (Modrinth renders Markdown).

1.0.0 (28 Sep 2026) was the beta; 1.1.0 is the first full release, a major overhaul. On Modrinth, 1.0.0 stays
in the **Beta** channel (Versions → 1.0.0 → Edit → Release channel, if it was ever set to Release), and its
title can read "Orbis Terrarum 1.0.0 (beta)". The git tag `v1.0.0` stays as it is.

## Project form

| Field | Value |
|---|---|
| Name | Orbis Terrarum |
| URL (slug) | `orbis-terrarum` (free as of 2026-09-28) |
| Summary | Generates the real Earth in Minecraft: real elevation, coastlines and rivers, OpenStreetMap buildings, roads, bridges and forests, streamed in as you explore. Pick any place on Earth as a new world type. |
| Project type | Mod |
| Categories | Worldgen (primary), Adventure |
| Client side | Optional (settings screens, area preview and the N world map) |
| Server side | Required (it generates the world) |
| License | All Rights Reserved (`LicenseRef-All-Rights-Reserved`), matching `LICENSE` and `fabric.mod.json` |
| Source code | https://github.com/Xyberg-001/orbis-terrarum |
| Issues | https://github.com/Xyberg-001/orbis-terrarum/issues |
| **Contains AI-generated content** | **Tick it.** Modrinth rule 6.1 requires it: a substantial part of the code and this description were produced with an AI assistant. |
| Icon | `E:\Software dev projects\Orbis Terrarum\art\Orbis terrarum icon.png` (800×800, 241 KiB; limit 256 KiB). The jar carries the same artwork at 128×128. |
| Gallery | Real in-game screenshots only (e.g. Bergen from above, a street with buildings, a bridge, the N map). |

## Version form

| Field | Value |
|---|---|
Two uploads, one jar per Minecraft version (the same changelog for both):

| Field | 26.3 | 26.2 |
|---|---|---|
| File | `orbisterrarum-1.1.0+26.3.jar` | `orbisterrarum-1.1.0+26.2.jar` |
| Version number | `1.1.0+26.3` | `1.1.0+26.2` |
| Version title | Orbis Terrarum 1.1.0 (26.3) | Orbis Terrarum 1.1.0 (26.2) |
| Release channel | Release | Release |
| Loaders | Fabric | Fabric |
| Game versions | 26.3 | 26.2 |
| Dependencies | Fabric API — required; YetAnotherConfigLib (v3) — optional; Mod Menu — optional | the same |

Changelog:

```
The first full release: a major overhaul of the 1.0.0 beta.

New
- Minecraft 26.3 support (26.2 still supported, one jar for each).
- Set up a world on the map: search a place, an address or coordinates on the World
  generator map, or right-click to put the spawn there; the spawn is the middle of the
  world. The World tab is now just the map, a preset list, the scale and the world type.
- Elevation map layer on both maps: heights in colour with hillshading, sea depths in
  blue, a colour key and the height or depth under the cursor.
- Real sky and seasons: real daylight, moon phase, weather and seasons, today's snow
  depth, and villagers who keep the town's clock hours.
- Real rock under the ground, real lake beds, and road widths and building heights from
  national databases in more countries.
- Underwater plants: kelp, seagrass, sea pickles and coral reefs by climate.
- Street name signs at junctions.
- Pre-generation planned when the world is created: select the area on the map
  (rectangle, ellipse, lasso, expand/shrink, skip open sea), generate it on creation,
  and keep the world to it with a hard limit (/orbis hardlimit on|off).
- Much faster pre-generation, and a resumed one skips what is already there.
- /orbis export aternos: an upload of your world for an Aternos server.
- Mod Menu settings: downloads by country, a data folder on any drive, performance and
  network settings.

Fixed
- Terrain walls along tile edges where only 30 m terrain data exists (most of the world
  outside the lidar countries, e.g. the Himalaya).
- Dry sea floor and water walls along some coasts; villages and outposts on open sea.
- Trail ruins buried deep underground; buried treasure on mountainsides.
- Road steps at tunnel junctions and uneven bridge decks, and many smaller fixes.
```

---

## Description (paste from here)

**Orbis Terrarum** turns Minecraft into the real Earth. Choose a place — a city, a mountain, a whole country — and explore it with real elevation, coastlines, lakes and rivers, OpenStreetMap buildings in their real colours and shapes, roads with markings, bridges, tunnels, railways, forests, farmland, street lamps and more. Terrain and map data stream in as you explore.

### Features
- **Real terrain** from national lidar models where they exist (Norway 1 m, Switzerland, Austria, Germany, Japan, the USA, …) and the bare-earth GEDTM30 everywhere else (Copernicus GLO-30 with forests and buildings taken out), with a real sea floor.
- **Buildings** from OpenStreetMap with heights, roof shapes and colours matched to aerial imagery.
- **Roads, rails, bridges and tunnels** at their real width, with markings, sidewalks and street furniture.
- **Water, land cover and trees** from OpenStreetMap and ESA WorldCover; trees can follow a real canopy-height map.
- **Your scale**: 1:1 or smaller (1:2, 1:32 … for whole countries); the world height is fitted to the place.
- **Real sky and seasons**: real daylight and moon phase, the real weather, seasons in the grass and leaves, and today's snow depth.
- **Real rock and lake beds**: the real bedrock under the ground, lakes that deepen away from the shore, and kelp and seagrass in the sea.
- **World generator map** before you create a world: search a place or right-click to put the spawn there, select the area like in an image editor (rectangle, ellipse, lasso, add and subtract) and see its size in blocks, chunks, disk size and time to pre-generate; optionally generate only that area.
- **Fast pre-generation** (`/orbis pregen`, or by itself when the world is created): Orbis builds the terrain ahead of the sweep on half your cores, the world stands still while it runs, and nothing that already exists is touched.
- An in-game **world map** (key B) with street, satellite and elevation layers, search, area selection and teleport.
- Vanilla ores, caves, animals and structures adapted to the real terrain.
- Lush Caves, Dripstone Caves and the Deep Dark with its Ancient Cities underground (new worlds from 1.1.1).

### Getting started
1. Install Fabric Loader and Fabric API for Minecraft 26.2 or 26.3, and the Orbis Terrarum jar for that version. For the settings screens also install **YetAnotherConfigLib** and (optionally) **Mod Menu**.
2. Create a new world, set **World Type → Orbis Terrarum**, then **Customize** and open the **World generator map** to choose the place; the other tabs choose the scale and features.
3. Every other world type stays plain vanilla — the mod stays out of those worlds.

**Servers:** install the mod on the server and set `level-type=orbisterrarum\:earth` in `server.properties` before the world is first created. Players can join with plain vanilla clients; the mod on the client only adds the world map and settings screens. Pre-generating the area you want to play in is strongly recommended — generation needs internet access and is much slower than vanilla.

### Internet access and privacy (please read)
Orbis Terrarum downloads map data while you play. It sends **no personal data and no telemetry**; what the services receive is the location of the area being generated (tile numbers or a bounding box), the text you type into a place search, and your IP address as with any download. Downloads are cached on disk under `config/orbisterrarum/`.

| Service | Used for | Sent |
|---|---|---|
| Mapterhorn (tiles.mapterhorn.com) | Terrain elevation; the Elevation map layer | Tile coordinates |
| OpenGeoHub (s3.opengeohub.org) | Bare-earth terrain (GEDTM30) where no national survey exists, for worlds made with 1.1.1 or later | Byte ranges of one global file |
| Open Waters Seascape (tiles.openwaters.io) | Sea floor; sea depths on the Elevation map layer | Tile coordinates |
| Geofabrik (download.geofabrik.de) | OpenStreetMap's file for the region holding a new world's area, only when you agree at Create (or run `/orbis mapdata`); the region list once a month | The region file's name |
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

### Data attribution
Map data © OpenStreetMap contributors (ODbL); street map tiles from OpenFreeMap (openfreemap.org) in the OpenMapTiles schema. Terrain: Mapterhorn and its sources (https://mapterhorn.com/attribution), incl. Copernicus GLO-30 © DLR/ESA/Airbus. Bare-earth terrain outside the surveys: GEDTM30 v1.2, OpenLandMap / OpenGeoHub Foundation, CC BY 4.0. Sea floor: Open Waters Seascape (GEBCO 2026 and regional surveys), CC BY 4.0. Land cover: ESA WorldCover 2021 v200, CC BY 4.0. Imagery: Esri, Maxar, Earthstar Geographics and the GIS User Community; USDA NAIP via USGS; © swisstopo; Beeldmateriaal Nederland (PDOK, CC BY 4.0); © Digitaal Vlaanderen; © SPW; © ACT Luxembourg (CC0); © IGN France (Licence Ouverte); PNOA © IGN España (CC BY 4.0); © Bayerische Vermessungsverwaltung (CC BY 4.0); © Geobasis NRW (dl-de/zero-2.0); basemap.at (CC BY 4.0); © ČÚZK (CC BY 4.0); © GURS (CC BY 4.0); © Maa- ja Ruumiamet (CC BY 4.0); GSI Japan tiles; © NLSC Taiwan (OGDL); © Lands Department, HKSAR; © Spatial Services NSW (CC BY 4.0); © Vicmap (CC BY 4.0); © King's Printer for Ontario (OGL-Ontario); MassGIS. Norway: © Kartverket (CC BY 4.0), NVDB © Statens vegvesen (NLOD), bedrock map © Norges geologiske undersøkelse (NGU, NLOD / CC BY 4.0), lake depth surveys © NVE (NLOD). Lake depth contours: © SYKE (CC BY 4.0), Minnesota DNR, © King's Printer for Ontario (OGL-Ontario). Road widths and lanes: BD TOPO © IGN (Licence Ouverte), Digiroad © Väylävirasto (CC BY 4.0), FHWA HPMS (public domain), BC Digital Road Atlas (OGL-BC). Building registers: BD TOPO © IGN (Licence Ouverte), 3DBAG (CC BY 4.0), © GURS (CC BY 4.0), © Stadt Wien (CC BY 4.0), NYC Open Data. Lidar building heights: © swisstopo (swissSURFACE3D), NRCan HRDEM (Open Government Licence – Canada), AHN (CC0), © IGN (Licence Ouverte), © Geobasis NRW (dl-de/zero-2.0), © Maa- ja Ruumiamet (CC BY 4.0), © ČÚZK (CC BY 4.0), DOGAMI (Oregon), KyFromAbove (Kentucky), DC GIS (CC BY 4.0), GeoNB (New Brunswick, OGL-NB). Rock types elsewhere: Macrostrat (macrostrat.org) and its source maps, CC BY 4.0. Optional lake depths you import: GLOBathy (CC0) with HydroLAKES (HydroSHEDS, CC BY 4.0). Weather: Norwegian Meteorological Institute (MET Norway), CC BY 4.0. Time zone and snow depth: Open-Meteo (open-meteo.com), CC BY 4.0. Optional local data you add yourself: German LoD2 buildings (Geobasis NRW, dl-de/zero-2.0; Bayerische Vermessungsverwaltung, CC BY 4.0), Meta/WRI canopy height (CC BY 4.0), GlobalBuildingAtlas building heights (TUM, CC BY-NC 4.0), Overture Places.

### Known issues
- Generation is network-bound: expect slow chunks while exploring new areas; pre-generate for multiplayer.
- **Voxy + shaders on 26.2:** Voxy 0.2.19's far terrain disappears with Iris 1.11.4 + Sodium 0.9.2 in any world (not an Orbis bug). Use Iris 1.11.2 + Sodium 0.9.1 until Voxy updates.

### Licence
All Rights Reserved — free to download from this page and play, in singleplayer and on your servers. Please don't re-upload it or bundle the jar in modpacks; link to this page instead. The bundled TwelveMonkeys ImageIO jars are BSD-3.
