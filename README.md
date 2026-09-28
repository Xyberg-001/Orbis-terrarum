# Orbis Terrarum

Play on the real Earth in Minecraft. Pick any place — your town, a city, a mountain, a whole country — and the mod builds it: real hills and valleys, coastlines, lakes and rivers, every building from OpenStreetMap in its real shape and colour, roads with markings, bridges, tunnels, railways, forests, fields and street lamps. The map downloads as you explore.

Minecraft **26.2**, **Fabric**.

---

## What it does

- **Real terrain** – true heights from laser-scanned (lidar) maps where a country has them (Norway, Switzerland, Germany, USA, Japan …) and satellite height data everywhere else, with a real sea floor.
- **Buildings** – every building on OpenStreetMap, with its height, roof shape and a colour taken from aerial photos.
- **Roads, rails, bridges, tunnels** – at their real width, with lane markings, sidewalks and street lamps.
- **Nature** – water, forests, farmland, parks and land cover; trees stand where real trees stand where canopy data is available.
- **Your scale** – 1:1, or smaller (1:2, 1:4 … 1:32 for a whole country). The world height is fitted to the place automatically.
- **Normal survival still works** – ores, caves, animals and vanilla structures, placed around the real map.
- **Area preview** – before creating a world, see the area on a map with its size in blocks, chunks, disk space and how long it takes to generate.
- **Custom spawn point** – choose exactly where players appear.
- **Pre-generation** – generate a whole area in advance so exploring (and servers) run smoothly.
- **World map (key N)** – a real street / satellite / topographic map lined up with your world, with search, teleport and a view of the Minecraft blocks from above.
- **Friends don't need the mod** – anyone can join an Orbis server with plain Minecraft.

---

## Install

1. Install **Fabric Loader** for Minecraft 26.2 and put **Fabric API** in `mods/`.
2. Put `orbisterrarum-<version>.jar` in `mods/`.
3. Recommended: **YetAnotherConfigLib (v3)** (needed for the settings screens) and **Mod Menu**.

An internet connection is needed while new areas generate. Everything downloaded is cached in `config/orbisterrarum/`, so each place is only downloaded once.

---

## Create a real-world world

1. **Singleplayer → Create New World → World** tab.
2. Set **World Type: Orbis Terrarum** and press **Customize**.
3. Choose your place and settings (tabs below) and press **Done**.
4. **Create New World.** A short "Downloading the map around your spawn" screen appears first; the game starts by itself when it's ready.

All other world types (Default, Amplified, Flat …) stay plain vanilla.

### The settings tabs

| Tab | What you set there |
|---|---|
| **Location** | Where the world is. Pick a preset (Bergen, London, Tokyo, Mount Everest …) or type any place or `lat, lon` and press **Search**. Also here: **Spawn point** and **Area preview**. |
| **Scale & Height** | Metres per block (1 = real size, 2 = half size …), how high mountains are handled, sea level. |
| **Features** | Turn on/off buildings, roads, water, land cover, trees, street furniture and lights, landmarks, ores, caves, animals, vanilla structures, bedrock. |
| **Style** | Look of the world: hollow buildings, windows, doors, storey height, road centre-line colour, sidewalks, tree density, climate. |
| **Data Sources** | Aerial-photo colours and high-resolution elevation on/off. |

The settings are saved inside the world, so it keeps generating the same way later. The same screens (plus **Performance & Network**) are under **Mod Menu → Orbis Terrarum → Configure**; those become the defaults for new worlds.

### Area preview (Location tab)

Type an area (a place name like `Bergen`, or a number like `5` for a 5 km circle; leave it empty for the city at your location) and press **Preview**. You get a map of the area and:

- **Size in blocks** and **ground area**, and how long it takes to walk or sprint across it
- **Chunks**, **region files** and **world size on disk**
- **Time** to pre-generate it
- **− / +** changes the scale (metres per block) and all numbers update
- **Street / Satellite / Topo** map background
- **Right-click** the map to put the spawn point there

### Spawn point (Location tab)

Switch on **Custom spawn point**, then type a place and press **Look up spawn**, enter latitude/longitude, or right-click the preview map. **Exact spawn** makes everyone land on exactly that block. The spawn is always placed on the surface, never underground or inside a wall.

---

## In game

### World map – press **N**

| Action | How |
|---|---|
| Move / zoom | Drag; scroll or double-click; **Me** jumps back to you |
| Find a place | Type in the search box (addresses and landmarks work too) |
| Teleport | Right-click → **Teleport here** (operators only) |
| Mark a place | Right-click → **Mark this place** (yellow pin, saved per world) |
| Copy coordinates | Right-click → **Copy coordinates** |
| Map style | **Map:** street / satellite / topographic |
| See Minecraft blocks | **Blocks** – shows the world from above over the real map (off / 35 % / 70 % / 100 %), handy for finding villages and structures |
| See what's generated | **Grey** – darkens areas that aren't generated yet |

You are the white arrow, other players are cyan arrows, the world spawn is a green square. The key can be changed in **Controls → Orbis Terrarum**.

### Commands

| Command | What it does |
|---|---|
| `/tpll <place or lat, lon>` | Teleport to a place ("Eiffel Tower", "Bergen, Norway") or coordinates (op) |
| `/wherell` | Your position as latitude/longitude |
| `/orbis here` | Everything about the spot you stand on: place name, real elevation, land cover, road, building … |
| `/orbis info` | The world's location, scale and cache stats |
| `/orbis prefetch <0-6>` | Download map data around you in advance |
| `/orbis pregen <km>` | Pre-generate everything within `<km>` real kilometres of you (op) |
| `/orbis pregen at <lat> <lon> <km>` | Same, around a coordinate (op) |
| `/orbis pregen area <place>` | Pre-generate a whole town, municipality or country by its outline (op) |
| `/orbis pregen status` / `stop` | Progress / stop (op) |
| `/orbis pregen map` | Draws a picture of what's generated; opens in your browser with `/orbismap` (op) |
| `/orbis map render` / `status` | Draws the whole world into the N map's **Blocks** layer at once (op) |
| `/orbis import <file.osm.pbf>` | Imports a downloaded OpenStreetMap file so generation doesn't need the online map servers (op) |
| `/orbis extracts` | Lists imported map files |
| `/orbis landmarks` | Rebuilds the landmark advancements |

---

## Pre-generating (recommended)

Generating the real world is much slower than vanilla because map data has to be downloaded and built. For smooth play, generate your area first:

1. Open the world, stand anywhere, and run for example `/orbis pregen area Bergen` or `/orbis pregen 5`.
2. Leave the game running (don't pause it; alt-tabbing pauses singleplayer). Progress shows in chat every 30 s.
3. When it's done, optionally run `/orbis map render` so the **Blocks** layer of the map is complete.

**Speed tip:** download your country from [Geofabrik](https://download.geofabrik.de) (`.osm.pbf`) and run `/orbis import <file>`. Generation then reads the map from your disk instead of the public map servers, which is faster and never rate-limited.

---

## Playing with friends / servers

- The mod only has to be on the **server**. Friends join with **plain Minecraft**; with the mod installed they also get the world map.
- **Easiest:** create and pre-generate the world in singleplayer, then upload the world folder to the server (you can leave out the world's `voxy` folder). Pre-generated areas need no internet on the server.
- **New world on a dedicated server:** set `level-type=orbisterrarum\:earth` in `server.properties` before the first start. The place and settings come from `config/orbisterrarum/orbisterrarum.json`.
- The server needs outbound internet for areas that aren't generated yet.
- Small servers (2–4 GB RAM): in the config set `regionCacheSize` to 16–24, `regionPrefetchRadius` to 1, `imageryTileCacheSize` and `demTileCacheSize` to 128.

---

## Tested on

**PC**

| | |
|---|---|
| Laptop | Acer, Windows 10 Home (22H2) |
| CPU | Intel Core i7-9750H (6 cores / 12 threads, 2.6 GHz) |
| GPU | NVIDIA GeForce RTX 2060 (laptop) |
| RAM | 32 GB DDR4-2400 — **16 GB given to Minecraft** |
| Storage | 256 GB NVMe SSD (game and world), 2 TB HDD |
| Java | JDK 25.0.3 |
| Launcher | Prism Launcher |
| Other mods | Fabric API 0.159.0, Sodium 0.9.1, Sodium Extra 0.9.4, Iris 1.11.2, Voxy 0.2.19-beta, Xaero's World Map 1.46.1, MiniHUD, YetAnotherConfigLib 3.9.6, Mod Menu 20.0.2, Zoomify |

**Test world: Bergen, Norway at 1:2** (1 block = 2 m)

| | |
|---|---|
| Area | Bergen municipality (mainland), fully pre-generated |
| World height | fitted to 1,408 blocks (Y −2032 to −625) |
| Chunks generated | 569,932 (629 region files) |
| Pre-generation speed | about **40 chunks/s** on average (about 25–27 before the 1.0 speed-ups) |
| World size on disk | 4.7 GB in total: terrain 2.6 GB, entities 234 MB, map layer 49 MB, Voxy far-view data 1.9 GB (optional) |
| Memory in use | about 3–5 GB of the 16 GB |
| FPS | 150–260 (capped at 260), render distance 8, with Voxy |

---

## Known issues

- **Generation needs internet** and is slower than vanilla; pre-generate for multiplayer. The public map servers are sometimes slow or busy — importing a map file (above) avoids that.
- **Voxy + shaders:** on Minecraft 26.2, Voxy 0.2.19's far terrain disappears with shaders when using Iris 1.11.4 + Sodium 0.9.2 (in every world, not just Orbis). Use **Iris 1.11.2 + Sodium 0.9.1** until Voxy updates.
- Very high mountains are squeezed to fit Minecraft's height limit.
- Only Minecraft 26.2 is supported.

How it works inside and all config options: [docs/technical-notes.md](docs/technical-notes.md).
