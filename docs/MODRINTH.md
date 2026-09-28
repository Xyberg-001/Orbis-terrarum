# Modrinth upload kit — Orbis Terrarum 1.0.0

Everything to paste into Modrinth's "Create a project" and "Upload version" forms.
The description below is the project body (Modrinth renders Markdown).

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
| File | `orbisterrarum-1.0.0.jar` |
| Version number | `1.0.0` |
| Version title | Orbis Terrarum 1.0.0 |
| Release channel | Beta |
| Loaders | Fabric |
| Game versions | 26.2 |
| Dependencies | Fabric API — required; YetAnotherConfigLib (v3) — optional; Mod Menu — optional |

Changelog:

```
First public release.
- Real-world terrain, OpenStreetMap buildings/roads/water/land cover, aerial-imagery colours.
- "Orbis Terrarum" world type; Customize opens the place/scale/feature settings.
- In-game area preview, custom spawn point, pre-generation (/orbis pregen) and a world map (N).
- Teleporting (/tpll and the map's "Teleport here") is for operators only.
```

---

## Description (paste from here)

**Orbis Terrarum** turns Minecraft into the real Earth. Choose a place — a city, a mountain, a whole country — and explore it with real elevation, coastlines, lakes and rivers, OpenStreetMap buildings in their real colours and shapes, roads with markings, bridges, tunnels, railways, forests, farmland, street lamps and more. Terrain and map data stream in as you explore.

### Features
- **Real terrain** from national lidar models where they exist (Norway 1 m, Switzerland, Austria, Germany, Japan, the USA, …) and Copernicus GLO-30 everywhere else, with a real sea floor.
- **Buildings** from OpenStreetMap with heights, roof shapes and colours matched to aerial imagery.
- **Roads, rails, bridges and tunnels** at their real width, with markings, sidewalks and street furniture.
- **Water, land cover and trees** from OpenStreetMap and ESA WorldCover; trees can follow a real canopy-height map.
- **Your scale**: 1:1 or smaller (1:2, 1:32 … for whole countries); the world height is fitted to the place.
- **Area preview** before you create a world: size in blocks, chunks, disk size and time to pre-generate.
- **Custom spawn point**, **pre-generation** (`/orbis pregen`) and an in-game **world map** (key N) with real-map layers, search and teleport.
- Vanilla ores, caves, animals and structures adapted to the real terrain.

### Getting started
1. Install Fabric Loader and Fabric API for Minecraft 26.2. For the settings screens also install **YetAnotherConfigLib** and (optionally) **Mod Menu**.
2. Create a new world, set **World Type → Orbis Terrarum**, then **Customize** to choose the place, scale and features.
3. Every other world type stays plain vanilla — the mod stays out of those worlds.

**Servers:** install the mod on the server and set `level-type=orbisterrarum\:earth` in `server.properties` before the world is first created. Players can join with plain vanilla clients; the mod on the client only adds the world map and settings screens. Pre-generating the area you want to play in is strongly recommended — generation needs internet access and is much slower than vanilla.

### Internet access and privacy (please read)
Orbis Terrarum downloads map data while you play. It sends **no personal data and no telemetry**; what the services receive is the location of the area being generated (tile numbers or a bounding box), the text you type into a place search, and your IP address as with any download. Downloads are cached on disk under `config/orbisterrarum/`.

| Service | Used for | Sent |
|---|---|---|
| Mapterhorn (tiles.mapterhorn.com) | Terrain elevation | Tile coordinates |
| Open Waters Seascape (tiles.openwaters.io) | Sea floor | Tile coordinates |
| Overpass API mirrors (overpass-api.de, overpass.kumi.systems, overpass.private.coffee, overpass.openstreetmap.fr) | OpenStreetMap buildings, roads, water | Bounding box of the area |
| Nominatim (nominatim.openstreetmap.org), Photon (photon.komoot.io) | Place search and area outlines, only when you search or preview | Your search text / coordinates |
| Esri ArcGIS Online (server.arcgisonline.com) | Aerial-imagery colours; map backgrounds in the preview and world map | Tile coordinates |
| ESA WorldCover (AWS S3, eu-central-1) | Land cover | 3° tile name |
| Kartverket / Geonorge (wcs.geonorge.no, opencache.statkart.no), Statens vegvesen NVDB (nvdbapiles.atlas.vegvesen.no) | Norway only: 1 m lidar terrain and surface model, orthophotos, road widths | Bounding box / tile coordinates |
| USGS National Map (elevation.nationalmap.gov, imagery.nationalmap.gov) | USA only: 3DEP elevation, NAIP imagery | Bounding box / tile coordinates |
| Google (8.8.8.8) and Cloudflare (1.1.1.1) DNS-over-HTTPS | Only if your system DNS fails to resolve one of the hosts above | The host name |

The generation sources (imagery, sea floor, land cover, lidar, road widths, the tile and Overpass URLs) can be switched off or replaced in `config/orbisterrarum/orbisterrarum.json`. The map backgrounds load only while the area preview or world map is open, place search only runs when you search, and the DNS fallback only when your system DNS fails.

### Data attribution
Map data © OpenStreetMap contributors (ODbL). Terrain: Mapterhorn and its sources (https://mapterhorn.com/attribution), incl. Copernicus GLO-30 © DLR/ESA/Airbus. Sea floor: Open Waters Seascape (GEBCO 2026 and regional surveys), CC BY 4.0. Land cover: ESA WorldCover 2021 v200, CC BY 4.0. Imagery: Esri, Maxar, Earthstar Geographics and the GIS User Community; Kartverket / Norge i bilder; USDA NAIP via USGS. Norway: © Kartverket (CC BY 4.0), NVDB © Statens vegvesen (NLOD). Optional local data you add yourself: Meta/WRI canopy height (CC BY 4.0), GlobalBuildingAtlas building heights (TUM, CC BY-NC 4.0), Overture Places.

### Known issues
- Generation is network-bound: expect slow chunks while exploring new areas; pre-generate for multiplayer.
- **Voxy + shaders on 26.2:** Voxy 0.2.19's far terrain disappears with Iris 1.11.4 + Sodium 0.9.2 in any world (not an Orbis bug). Use Iris 1.11.2 + Sodium 0.9.1 until Voxy updates.
- Minecraft 26.2 only.

### Licence
All Rights Reserved — free to download from this page and play, in singleplayer and on your servers. Please don't re-upload it or bundle the jar in modpacks; link to this page instead. The bundled TwelveMonkeys ImageIO jars are BSD-3.
