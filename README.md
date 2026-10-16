# Orbis Terrarum

Play on the real Earth in Minecraft. Pick any place, from your town to a whole country, and the mod builds it from real map data: hills,
coastlines, rivers, every building, roads, bridges, railways and forests. Minecraft **26.2** and **26.3**, **Fabric**.

## Features

- **Real terrain** from laser-scanned maps where a country has them, a bare-earth satellite model everywhere else, and a real sea floor.
- **Every building** from OpenStreetMap (and from Overture where OpenStreetMap is thin), with its real height, roof shape and colour; walkable floors and doors.
- **Roads, rails, bridges and tunnels** at their real width, with markings, sidewalks, street lamps and street name signs.
- **Nature:** water, forests of the real kind (conifer or broadleaf), fields and parks; real rock under the ground, real soil (peat, podzol, clay, sand) as deep as it really is, glaciers with thick ice, rivers at their measured width, and real lake beds.
- **Real sky:** real daylight and moon, real weather, seasons and today's snow depth.
- **Any scale:** 1:1 down to 1:32 for a whole country.
- **Survival still works:** ores, caves (Lush, Dripstone, Deep Dark), animals, villages and other structures, placed around the map.
- **World map (B):** street, satellite and elevation map of your world, with search and teleport.
- **Fast pre-generation** of a whole area before you play.
- **Friends don't need the mod:** anyone joins an Orbis server with plain Minecraft. The world map (B) and the settings screens need the mod on the player's own game too.

## Install

1. **Fabric API** for your Minecraft version.
2. `orbisterrarum-<version>+<minecraft>.jar` in `mods/`.
3. **Mod Menu**: needed to open the settings (map data and storage folder, performance, logging). A server doesn't need it: it uses `/orbis settings`.

New areas need an internet connection while they generate; everything downloaded is cached.

## Create a world

**Create New World → World Type: Orbis Terrarum → Customize**, open the **World generator map**, search a place or right-click
where you want to spawn, and create the world. The other tabs (Features, Look & Climate, Data Sources, Advanced) have sensible defaults.

## World map (B)

Players need the mod installed on their own game to open it (on a server too); without it they play normally, just without the map.

| Action | How |
|---|---|
| Find a place | Type in the search box: the card has **Teleport** and **Select area** |
| What's here | Right-click → **What's here** |
| Teleport / mark / copy coordinates | Right-click |
| Map style | Street, satellite or elevation; day / night |
| See the blocks | **Blocks** shows the world from above |
| Pre-generate | **Select area**, draw it (green), choose **Map data: Live** (online servers) or **Download** (Geofabrik first), **Generate**. Unfinished ones are listed under the button on the right, shown only while there are any (also after a restart): click to choose, then **Resume**, **Pause**, **Stop** or **⇄** (switch its map data between Live and Download); what is left shows purple |
| World info and switches | The **i** button (real sky, hard limit, Draw all, Export for Aternos) |

## Commands

`/orbis settings` is the only command, for operators: a server has no Mod Menu, so its performance and network settings are
changed here (in singleplayer, use Mod Menu → Orbis Terrarum instead). Changes are saved at once; the ones marked *restart* apply
after a restart.

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

Everything else is on the world map (B): Teleport, What's here, pre-generating (Select area, Generate, Stop, Resume), and the
World window (**i**) with your position, the real sky and hard limit switches, Draw all and Export for Aternos.

## Servers

- Install the mod on the server only; set `level-type=orbisterrarum\:earth` in `server.properties` before the first start.
- Easiest: create and pre-generate the world in singleplayer, then upload it.
- Aternos: **Export for Aternos** in the world map's World window (**i**) makes the upload and a how-to file.

> **Pre-generating on a server:** it gets slower when the server is short of memory (4 GB is tight for big areas; give it 6–8 GB, more for cubic worlds). **Map data: Download** fetches the region's whole Geofabrik file onto the server's storage (tens of MB for a small region, several GB for a big country); the area cut out of it stays there for good, and the file itself is deleted afterwards unless `keepDownloadedMapFiles` is on. Check the server has the space, or use **Live**.

## Tested on

Intel i7-9750H, RTX 2060, 16 GB for Minecraft. Bergen at 1:2 (566 km²): pre-generated in 1.5 hours (about 103 chunks/s), 3.3 GB on
disk, 150–260 FPS.

## Known issues

- Generation needs internet and is slower than vanilla: pre-generate for multiplayer.
- On 26.2, Voxy 0.2.19's far terrain disappears with Iris 1.11.4 + Sodium 0.9.2; use Iris 1.11.2 + Sodium 0.9.1.

---

[Data sources and credits](docs/CREDITS.md) · [Internet and privacy](docs/PRIVACY.md) · [How it works and every setting](docs/technical-notes.md)
