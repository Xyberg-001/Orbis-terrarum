# Modrinth upload kit — Orbis Terrarum 1.1.0

Everything to paste into Modrinth's project settings and "Upload version" forms.
The description below is the project body (Modrinth renders Markdown).

1.0.0 (28 Sep 2026) was the beta; 1.1.0 (11 Oct 2026) is the first full release, a major overhaul. (A first 1.1.0
build of 3 Oct was on GitHub only; this 1.1.0 replaces it.) On Modrinth, 1.0.0 stays
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
| Client side | Optional (settings screens, area preview and the B world map) |
| Server side | Required (it generates the world) |
| License | All Rights Reserved (`LicenseRef-All-Rights-Reserved`), matching `LICENSE` and `fabric.mod.json` |
| Source code | https://github.com/Xyberg-001/orbis-terrarum |
| Issues | https://github.com/Xyberg-001/orbis-terrarum/issues |
| **Contains AI-generated content** | **Tick it.** Modrinth rule 6.1 requires it: a substantial part of the code and this description were produced with an AI assistant. |
| Icon | `E:\Software dev projects\Orbis Terrarum\art\Orbis terrarum icon.png` (800×800, 241 KiB; limit 256 KiB). The jar carries the same artwork at 128×128. |
| Gallery | Real in-game screenshots only (e.g. Bergen from above, a street with buildings, a bridge, the B map). |

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
| Dependencies | Fabric API — required; Mod Menu — optional (YetAnotherConfigLib comes inside the jar, LGPL, unmodified) | the same |

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
  and keep the world to it with a hard limit.
- Much faster pre-generation, and a resumed one skips what is already there.
- The world map (B) does most of the work the commands did: search a place and
  teleport there or select its outline (its largest part or all of it); right-click
  What's here (place, elevation, climate, road, building); landmarks as pins, ticked
  once found; the i window with the world's facts, the real sky switches, the hard
  limit, Draw all for the Blocks layer and Download map data; pre-generation progress
  with Stop and Resume. The /orbis commands it replaces are gone.
- Map data from Geofabrik: when you create a world (or with Download map data on the
  world map), Orbis fetches OpenStreetMap's file for its region, keeps only your area (a
  country-scale world keeps the whole country's main features) and reads the map from disk.
- Far view with Voxy in singleplayer: land shown from the map data before it exists.
- Mountains at 1:1: an area high above the sea (the Himalaya) gets the world's height
  laid over its own ground, 1:1 from the valleys to the summits.
- True height (optional, Minecraft 26.3): with Cubic Worlds installed, a world can be
  cubic: the Earth at 1:1 from the deepest sea floor to the top of Everest.
- Lush Caves, Dripstone Caves and the Deep Dark underground.
- Bare-earth terrain (GEDTM30) outside the lidar countries: no more forests and
  buildings baked into the ground.
- YetAnotherConfigLib comes inside the mod: nothing extra to install.
- Export for Aternos (world map, World window): an upload of your world for an Aternos server.
- Mod Menu settings: downloads by country, a data folder on any drive, performance and
  network settings.

Fixed
- Terrain walls along tile edges where only 30 m terrain data exists (most of the world
  outside the lidar countries, e.g. the Himalaya).
- Dry sea floor and water walls along some coasts; villages and outposts on open sea.
- Village houses, pillager outposts and abandoned camps no longer filled with earth on
  slopes; villages also appear where only footpaths, tracks or streams cross the land.
- Trail ruins buried deep underground; buried treasure on mountainsides.
- Road steps at tunnel junctions and uneven bridge decks.
- /locate, eyes of ender, explorer maps and dolphins no longer freeze the server: the
  structure search runs in the background.
- Trees no longer fill houses with leaves; doors open onto a room inside and clear
  ground outside.
- Cities with many shops stay smooth: shop display frames are shown within 48 blocks
  (a setting), and new shops hang fewer.
- And many smaller fixes.
```

---

## Description (paste from here)

**Orbis Terrarum** puts the real Earth in Minecraft. Pick any place, from your town to a whole country, and the mod builds it from real map data: hills, coastlines, rivers, every building, roads, bridges, railways and forests.

### Features
- **Real terrain** from laser-scanned maps where a country has them, a bare-earth satellite model everywhere else, and a real sea floor.
- **Every building** from OpenStreetMap, with its real height, roof shape and colour; walkable floors and doors.
- **Roads, rails, bridges and tunnels** at their real width, with markings, sidewalks, street lamps and street name signs.
- **Nature:** water, forests, fields and parks; real rock under the ground and real lake beds.
- **Real sky:** real daylight and moon, real weather, seasons and today's snow depth.
- **Any scale:** 1:1 down to 1:32 for a whole country.
- **Survival still works:** ores, caves (Lush, Dripstone, Deep Dark), animals, villages and other structures, placed around the map.
- **World map (B):** street, satellite and elevation map of your world, with search and teleport.
- **Fast pre-generation** of a whole area before you play.
- **Friends don't need the mod:** anyone joins an Orbis server with plain Minecraft.

### Getting started
1. Install Fabric API and the Orbis Terrarum jar for your Minecraft version (26.2 or 26.3). **Mod Menu** is recommended, for the settings.
2. **Create New World → World Type: Orbis Terrarum → Customize**, open the **World generator map**, search a place or right-click where you want to spawn, and create the world.

Other world types stay plain vanilla.

### Commands

`/orbis settings` is the only command, for operators: a server has no Mod Menu, so its performance and network settings are changed here (in singleplayer, use Mod Menu → Orbis Terrarum instead). Changes are saved at once; the ones marked *restart* apply after a restart.

- `/orbis settings` lists every setting with its value
- `/orbis settings <name>` shows one
- `/orbis settings <name> <value>` changes it, e.g. `/orbis settings regionCacheSize 24`

| Setting | Default | What it does | For your hardware |
|---|---|---|---|
| `regionCacheSize` | 64 | Map regions kept in memory (3–4 MB each, 8–256), *restart* | 24 on 2–4 GB servers; 128 with 16 GB or more |
| `demTileCacheSize` | 384 | Terrain tiles kept in memory (about 100 MB at 384), *restart* | 128 on small servers; 768 with plenty of memory |
| `imageryTileCacheSize` | 512 | Aerial photo tiles kept in memory (about 130 MB at 512), *restart* | 128 on small servers; 1024 with plenty of memory |
| `regionPrefetchRadius` | 2 | How far ahead of players map data is downloaded (0–6; 2 is a 2.5 km square) | 1 on slow internet or small servers; 3 for players flying fast |
| `overpassConcurrentRequests` | 4 | Map downloads at once (1–8) | 2 on slow or shared connections |
| `osmMaxWaitMinutes` | 15 | How long a chunk waits for its map data before it generates without buildings | 30 on slow connections |
| `waitForOsm` | true | New chunks wait for their map data | Keep on; off only while the map services are down and land without buildings is fine |
| `imageryZoom` | 18 | Detail of the aerial photos used for colours (14–20) | 17 on slow internet (a quarter of the downloads); 19 for sharper colours |
| `fastPregen` | true | Pre-generation builds terrain ahead on several threads | Keep on: it is several times faster |
| `fastPregenThreads` | 0 | Threads for that; 0 = half the processor's | 1–2 on 2–4 core machines or when the machine runs other things |
| `parallelChunkCompression` | true | Chunks are compressed for saving on several threads | Off on 2-core machines |
| `pregenPauseWorld` | true | Mobs, crops, redstone and time stand still while a pre-generation runs | Off if players must keep playing during it (slower) |
| `fastChunkWrites` | true | Chunks are saved without waiting for the disk after each one, *restart* | Keep on, above all on a hard disk; off on unreliable power (a crash loses the last seconds) |
| `prefetchSpawnAtStartup` | true | Downloads the map around the spawn while the server starts | Keep on |
| `keepDownloadedMapFiles` | false | Keeps the Geofabrik files after a map data download | On if you make several worlds in the same country and have the disk space |
| `displayFrameRange` | 48 | Blocks within which shop display frames are sent to players (0 = vanilla's 160) | 24–32 if players lag in big cities |
| `debugLogging` | false | More detail in the server log | On only when reporting a problem |

Ready-made setups:

- **Small server (2–4 GB of memory):** `regionCacheSize 24`, `demTileCacheSize 128`, `imageryTileCacheSize 128`, `regionPrefetchRadius 1`, then restart.
- **Few cores (2–4):** `fastPregenThreads 1`; on 2 cores also `parallelChunkCompression false`.
- **Slow or metered internet:** `regionPrefetchRadius 1`, `overpassConcurrentRequests 2`, `imageryZoom 17`, `osmMaxWaitMinutes 30`.
- **Big machine (16 GB+, 8+ cores, SSD):** `regionCacheSize 128`, `demTileCacheSize 768`, `imageryTileCacheSize 1024`, `regionPrefetchRadius 3`, then restart.

Everything else is on the world map (B): Teleport, What's here, pre-generating (Select area, Generate, Stop, Resume), and the World window (**i**) with your position, the real sky and hard limit switches, Download map data, Draw all and Export for Aternos.

### Servers
Install the mod on the server only and set `level-type=orbisterrarum\:earth` in `server.properties` before the first start. Easiest: create and pre-generate the world in singleplayer, then upload it.

**True height (optional, Minecraft 26.3):** add [Cubic Worlds](https://github.com/Xyberg-001/CubicWorlds/releases) and switch on its **Cubic Worlds** tab when you create the world: the Earth at 1:1 from the deepest sea floor to the top of Everest.

### Internet and privacy
New areas download map data from public map services while they generate (everything is cached). Orbis sends **no personal data and no telemetry**: the services only see the area being generated, the text of your place searches and, as with any download, your IP address. Every service and what it is sent: [PRIVACY.md](https://github.com/Xyberg-001/Orbis-terrarum/blob/main/docs/PRIVACY.md).

### Known issues
- Generation needs internet and is slower than vanilla: pre-generate for multiplayer.
- On 26.2, Voxy 0.2.19's far terrain disappears with Iris 1.11.4 + Sodium 0.9.2; use Iris 1.11.2 + Sodium 0.9.1.

### Credits and licence
Map and terrain data from OpenStreetMap contributors (ODbL) and many open-data providers: [CREDITS.md](https://github.com/Xyberg-001/Orbis-terrarum/blob/main/docs/CREDITS.md). Orbis Terrarum is All Rights Reserved: free to play, in singleplayer and on your servers; please don't re-upload it, link to this page instead.
