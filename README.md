# Orbis Terrarum

Play on the real Earth in Minecraft. Pick any place — your town, a city, a mountain, a whole country — and the mod builds it: real hills and valleys, coastlines, lakes and rivers, every building from OpenStreetMap in its real shape and colour, roads with markings, bridges, tunnels, railways, forests, fields and street lamps. The map downloads as you explore.

Minecraft **26.2**, **Fabric**.

---

## What it does

- **Real terrain** – true heights from laser-scanned (lidar) maps where a country has them (Norway, Switzerland, Germany, USA, Japan …) and everywhere else a satellite model of the bare ground, with forests and buildings taken out, so a forest floor isn't raised by its trees. Plus a real sea floor.
- **Buildings** – every building on OpenStreetMap, with its height (measured by laser scans in about ten countries and states, from national building registers in France, the Netherlands, Slovenia, Vienna and New York), roof shape and a colour taken from aerial photos (national survey photos in some 20 countries and states, satellite photos everywhere else). At 1:1 and 1:2 every storey is three blocks, so you can walk the floors and buildings look right beside you; coarser worlds keep heights true to scale (Customize → Buildings → **Building heights**). Every building with a room has a wooden door with a clear way in and out, and buildings are made of everyday blocks, never iron, gold or copper blocks you could strip for free ingots.
- **Roads, rails, bridges, tunnels** – at their real width, with lane markings, sidewalks, street lamps and street name signs at the junctions. Real widths and lane counts come from national road databases in Norway, France, Finland, the USA and British Columbia.
- **Nature** – water, forests, farmland, parks and land cover; trees stand where real trees stand where canopy data is available.
- **Real rock** – the stone in cliffs, caves and under the ground is the real bedrock: granite, gabbro, basalt, schist, limestone, sandstone and more. Detailed survey maps in Norway, a world geological map everywhere else.
- **Real lake beds** – lakes deepen away from the shore instead of having a flat bottom; Norway's surveyed lakes get their measured bed, and rivers are as deep as their width. Surveyed lake beds come from depth surveys in Norway, Minnesota and Ontario.
- **Real sky and seasons** – the sun rises and sets when it really does there, the moon shows its real phase, the weather follows the real forecast, grass and leaves change colour with the real season, and snow lies as deep as it really does today: green valleys, buried mountains.
- **Your scale** – 1:1, or smaller (1:2, 1:4 … 1:32 for a whole country). The world height is fitted to the place automatically, and a mountain area with no sea gets the world's heights laid over its own ground: around Everest everything from Base Camp to the summit is true 1:1. Prefer every mountain in true proportion to every other? **Uniform heights** scales all heights by one amount, only as much as your area needs (Bergen stays 1:1, the Alps 1.3 times flatter, Everest 2.4).
- **Normal survival still works** – ores, caves, animals and vanilla structures, placed around the real map. Underground you find Lush Caves, Dripstone Caves and the Deep Dark with its Ancient Cities, and buried treasure lies under the beach sand.
- **Area preview** – before creating a world, see the area on a map with its size in blocks, chunks, disk space and how long it takes to generate.
- **Pick the spawn on a map** – right-click where players should appear; the world is centred there.
- **Pre-generation** – generate a whole area in advance so exploring (and servers) run smoothly.
- **World map (key B)** – a real street / satellite / elevation map lined up with your world, with search, teleport and a view of the Minecraft blocks from above. The street map is drawn from OpenStreetMap like Google Maps shows it: street names, buildings, and shops, restaurants, hotels, stops and other places with symbols showing what they are, plus house numbers as you zoom in. The sun / moon button at the map's right edge switches the street map between day and night colours, and an orange haze shows the chunks not generated yet.
- **Friends don't need the mod** – anyone can join an Orbis server with plain Minecraft.

---

## Install

1. Install **Fabric Loader** for Minecraft 26.2 and put **Fabric API** in `mods/`.
2. Put `orbisterrarum-<version>.jar` in `mods/`.
3. Recommended: **Mod Menu**, to open the mod's settings. YetAnotherConfigLib, which the settings screens need, comes inside Orbis Terrarum.

An internet connection is needed while new areas generate. Everything downloaded is cached in `config/orbisterrarum/`, so each place is only downloaded once.

---

## Create a real-world world

1. **Singleplayer → Create New World → World** tab.
2. Set **World Type: Orbis Terrarum** and press **Customize**.
3. Choose your place and settings (tabs below) and press **Done**.
4. **Create New World.** If your world uses a service that needs something from you (Kartverket's lidar needs a VPN to Norway outside Norway), a short notice says so and checks whether it answers from your network. Then a short "Downloading the map around your spawn" screen appears; the game starts by itself when it's ready.

All other world types (Default, Amplified, Flat …) stay plain vanilla.

### The settings tabs (Create New World → Customize)

Everything about a new world is set here, and only here. Customize remembers your last choices for the next world; **Reset all to defaults** (World tab) starts over.

| Tab | What you set there |
|---|---|
| **World** | The big decisions. Where: open the **World generator map** and search a place or `lat, lon`, or right-click to put the spawn there (the spawn is the middle of the world, block 0, 0); **Preset location** (**Choose…**: Bergen, London, Tokyo, Mount Everest …) gives the map a place to start from. A new world starts at Mount Everest until you choose a place. How big: **Metres per block** (1 = real size, 2 = half size …). **World type** (**Choose…**: City 1:1, Country map 1:32) fills in everything else for that kind of world. |
| **Features** | What gets generated, in groups: **Cities** (buildings, roads, interiors, name and street signs, street furniture, lights, landmarks), **Life** (villager residents, street life, transit), **Nature** (water, land cover, trees), **Minecraft gameplay** (ores, caves, structures, animals, bedrock, landmark advancements, tunnel loot) and **Fine-tuning** (folded). |
| **Look & Climate** | **Real sky** first (real daylight, weather, seasons and snow depth, villager clock hours), then, folded: how buildings and roads look, and climate and tree tuning. |
| **Data Sources** | Where the real-world data comes from (all groups folded; the defaults are right for most worlds). Which sources a world uses is automatic: a country's own services (sharper aerial photos, rock map, road widths, lake surveys) wherever they cover, worldwide data everywhere else. Switches for **downloaded automatically** (satellite photo colours, sea floor, land cover, rock types, lake depths), **needs a one-time import** (building height atlas, canopy trees, extra places) and how the **photos** are used. Each option names its source. |
| **Advanced** | How the terrain becomes blocks: the **world height** (leave it at 0 and it is fitted to the place, which makes generation and pre-generation much faster than the full 4,064 blocks), sea level, projection, terrain detail and how high mountains are handled. Also **Lidar building heights**: measured heights and roof shapes from national laser scans are used by themselves in Switzerland, the Netherlands, France, North Rhine-Westphalia, Estonia, Czechia, Canada (where it has been scanned), Oregon, Kentucky and Washington DC (at 1:4 or finer); in Norway, Kartverket's is used by itself whenever it answers from your network (its switch here forces it on, for networks where a VPN to Norway is needed). |

The settings are saved inside the world, so it keeps generating the same way later.

### The mod's own settings (Mod Menu → Orbis Terrarum → Configure)

Nothing here changes a world; it is about the mod on this computer.

| Tab | What's there |
|---|---|
| **Overview** | The mod's version, the data folder and how much it holds, the OpenStreetMap extracts and other data you imported, and your last pre-generations (chunks, time, chunks per second). |
| **Storage** | **Downloads by country**: how much each source keeps on disk, the countries around your last world first, with **Re-import data** (delete it so it downloads again, fresh) and **Delete** (free the space). Then the data folder. |
| **Performance** | Fast pre-generation, pausing the world while pre-generating, fast chunk writes, how far ahead map data is fetched and kept. |
| **Network** | Parallel downloads, how long to wait for map data, tile caches, debug logging. |

**Short of space?** The mod's downloads (terrain, photos, map data, country extracts, lidar) can take many gigabytes. Set **Storage → Data folder** to another drive with **Choose folder…** (or type a path such as `D:/OrbisData`), then press **Move downloads**: everything already downloaded moves there (from the main menu, not while a world is open) and the new folder is used from then on. The field always shows the folder in use, and **Open** shows it in your file manager. Point the import tools' `--out` at the new folder too.

**Real daylight, weather, seasons and snow depth** (Look & Climate → Real sky) are on by default and saved with each world. With real daylight, villagers keep the town's real clock hours (at work 08:00–16:00, in bed at 22:00) unless you switch **Villagers keep clock hours** off, when they follow the sun instead. With real daylight, sleeping does not skip the night. The season changes the next time a world is opened. To change them in a world that already exists, use `/orbis daylight off` (or `weather`, `seasons`, `snow`, `clockhours`, with `on` or `off`): it takes effect at once and stays with the world. **Real snow depth** lays today's snow on new ground (deeper the higher you go, and faster in snowy climates such as Japan's or the Cascades' than in dry ones like Colorado's) and lets the snow already there settle or melt a layer at a time as the real snow does; it is checked every six hours.

### World generator map (World tab)

Open the **World generator map** to choose where the world is and what it generates (see [Pre-generating](#pre-generating-recommended) below). Search a place or coordinates at the top, or **right-click** the map: players spawn there, and it is the middle of the world (block 0, 0). Searching a place elsewhere moves the spawn there too. Without pre-generation that is all you need: the world generates around players as they go. Beside the map you see the selection's:

- **Size in blocks** and **real area**, and how long it takes to walk or sprint across it
- **Chunks**, **region files** and **world size on disk**
- **Time** to pre-generate it

**‹ ›** under **World scale** change the metres per block and all numbers update; the map background switches between street, satellite and **elevation** (heights in colour with hillshading, sea depths in blue, a colour key, and the height or depth under the cursor). The spawn is always placed on the surface, never underground or inside a wall, and everyone lands on exactly that block (**Exact spawn**, Advanced tab).

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
| Map style | **Map:** street / satellite / elevation (heights in colour, with the height under the cursor); the sun / moon button beside the street map switches day and night colours |
| See Minecraft blocks | **Blocks** – shows the world from above over the real map (off / 35 % / 70 % / 100 %), handy for finding villages and structures |
| See what's generated | **Grey** – a light grey haze over areas that aren't generated yet; with a hard limit, the area that never generates is tinted red |

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
| `/orbis pregen resume` | Carry on with the area chosen when the world was created, after a stop (op) |
| `/orbis export aternos` [`orbis`] [`zip`] | Make an upload of this world for an Aternos server (op); see below |
| `/orbis hardlimit` [`on`/`off`] | Show or change the hard limit: only the selected and pre-generated area generates (op) |
| `/orbis pregen map` | Draws a picture of what's generated; opens in your browser with `/orbismap` (op) |
| `/orbis map render` / `status` | Draws the whole world into the N map's **Blocks** layer at once (op) |
| `/orbis mapdata` [`stop`] | Downloads the map data for the land around you from Geofabrik and keeps only that area (op) |
| `/orbis import <file.osm.pbf>` | Imports a downloaded OpenStreetMap file so generation doesn't need the online map servers (op) |
| `/orbis extracts` | Lists imported map files |
| `/orbis landmarks` | Rebuilds the landmark advancements |
| `/orbis sky` | Shows which of the real sky features are on in this world (op) |
| `/orbis daylight` / `weather` / `seasons` / `snow` / `clockhours` `on`/`off` | Switches real daylight, real weather, real seasons, real snow depth or the villagers' clock hours on or off in this world, at once and for good (op) |
| `/orbis settings` [`<name>` [`<value>`]] | Lists, shows or changes the mod's performance and network settings, e.g. `/orbis settings regionCacheSize 24` (op; for servers, which have no settings screen) |

---

## Pre-generating (recommended)

Generating the real world is much slower than vanilla because map data has to be downloaded and built. For smooth play, generate your area first:

1. Open the world, stand anywhere, and run for example `/orbis pregen area Bergen` or `/orbis pregen 5`.
2. Leave the game running. You can switch to other windows: the game doesn't pause while a pre-generation runs. A bar at the top of the screen shows how far it is and what it's doing right now: generating (with chunks per second and time left), or which data it's downloading first (map data, terrain, building heights, photos …), so you can tell it's working even before the first chunks are done. Everyone on the server sees it. A summary also shows in chat every 2 minutes.
3. When it's done, optionally run `/orbis map render` so the **Blocks** layer of the map is complete.

**What makes it fast** (all on by default, in **Mod Menu → Orbis Terrarum → Configure → Performance & Network → Streaming**):

- **Fast pre-generation**: Orbis builds the ground, water, roads, buildings and caves of the chunks just ahead of the sweep itself, on half your processor cores, and Minecraft only adds decoration, light and animals. Chunks that already exist are never touched.
- **Pause the world while pre-generating**: the world stands still while it runs (mobs, crops, redstone, time and weather; like `/tick freeze`), so all the game's time goes into generating. You can still move around, and everything carries on when the pre-generation ends or stops.
- **Fast chunk writes**: Orbis worlds save chunks without waiting for the disk after each one. Your own `options.txt` and other worlds are left as they are.
- A fitted **world height** (Advanced tab, 0 = fitted) is the biggest single difference: a world for one region doesn't need 4,064 blocks of mostly empty air in every chunk.

**Mountains you add later.** A world's height is fitted when the world is created, to the land around its spawn and inside the area you select on the World generator map, and it can't change afterwards. If you later select an area with higher mountains (Mount Everest in a Kathmandu world), their tops are squeezed to fit, and the map's selection panel and the chat warn you before and when it starts. If you know you'll want such an area, select it when you create the world, or set **World height** to 4064 then.

**Or draw the area on the map.** Open the world map, press **Select area** and draw what you want with the **Rectangle**, **Ellipse** or **Lasso**, just like selecting part of a picture in an image editor:

- Drag to draw. With the lasso you can also click corner after corner and double-click to close.
- Hold **Shift** to add to the selection, **Alt** to cut a piece out (or pick **Add** / **Subtract** on the mode button). Hold **Ctrl** while dragging for a perfect square or circle.
- **Skip open sea** (the waves button) leaves deep-sea chunks out of the area's pre-generation; off at first.
- **Expand selection** and **Shrink selection** (the two buttons under the tools) grow or shrink the whole selection all round, by about a twentieth of its size; hold **Ctrl** while clicking for a small step (one chunk). Press again for more.
- **Ctrl+Z** undoes the last change (a shape, Expand, Shrink, Deselect), **Ctrl+D** deselects. Right-drag moves the map while you select.
- The map shows the selected chunks, how many there are, roughly how much disk space and time they take, and how many are generated already. Press **Generate** to start; press it again with the same selection to resume after a stop.

Selections are for operators and are remembered per world.

**Or set it all up when you create the world.** In the **World** tab, open the **World generator map**. It starts at your spawn with nothing selected. Select the area with the tools on the left (hand, rectangle, ellipse, lasso), or type a place (`Bergen`) or a radius (`5 km`) at the top and press **Select**; Shift adds and Alt cuts out, as above. The panel beside the map shows the selection's real area, size in blocks, chunks, region files, disk space and generation time. Switch **Generate on creation** on, and the new world generates the selection by itself a few seconds after it opens. If you leave before it's done, it carries on the next time you open the world. `/orbis pregen stop` pauses it and `/orbis pregen resume` carries on.

**Hard limit.** Switch **Only the selection** on (on the World generator map) and nothing outside the selected area ever generates, even if players go there: it stays empty, nothing is downloaded for it, and an invisible wall along the edge keeps players inside. Areas you pre-generate later (map **Generate**, `/orbis pregen`) join the allowed area, and `/orbis hardlimit on` / `off` changes it in an existing world.

**Skipping open sea.** Chunks that are deep sea all over can be left out of a pre-generation: the world is smaller to upload and the sweep shorter, and those chunks generate quickly when someone sails there (Bergen 1:2: about 0.45 GB less). For an area drawn on the map, the **Skip open sea** button (the waves, under the tools) decides; it is off at first, so the area is generated sea and all. For the `/orbis pregen` commands, **Skip open sea (/orbis pregen)** in the Advanced tab decides (on by default). While it is on, the world map darkens everything outside the allowed area, with a red line along its edge, so you can see what will never generate.

**Germany:** measured heights for every building in North Rhine-Westphalia and Bavaria come from the states' 3D building models, which are only offered as big files. Import them once for your area (the tool streams them and keeps a few megabytes per city):

```bash
python tools/lod2_heights.py --bbox SOUTH WEST NORTH EAST --out <instance>/config/orbisterrarum/building-db-cache/de-lod2
```

**Map data on your disk.** When you create a world and no map data on your computer covers its area, Orbis offers OpenStreetMap's file for that place from [Geofabrik](https://download.geofabrik.de): the smallest region that holds the whole area (Norway for Bergen, the Düsseldorf district rather than all of Germany), with its size. Say **Download** and it downloads in the background while the world opens, keeps only your world's area (tens of MB) and deletes the big file. From then on generation reads the map from your disk instead of the public map servers, which is faster and never overloaded. Until it is done the world uses the online servers as before. An area across a border gets no offer (no single file holds it). On a server, `/orbis mapdata` does the same for the land around you. **Offer map data downloads** and **Keep downloaded map files** are in Mod Menu → Network.

---

## Uploading to Aternos

Run `/orbis export aternos` in your world. It saves the world and makes an upload in `orbis-exports/` in your game folder, with a coverage picture and **HOW-TO-UPLOAD.txt** telling you exactly what to do:

- A world that fits becomes one zip (Aternos takes uploads up to 1 GB). A bigger one becomes a small base zip plus folders of region files of about 200 MB, which you add through Aternos' **Files** page; up to about 3.4 GB so it fits Aternos' 4 GB of storage. The terrain goes without its lighting, which the server works out on the first visit to each area: about a fifth smaller, so 566 km² of Bergen at 1:2 fits whole. If there is too much, the chunks nearest the spawn go in first.
- By default it is made for a **plain Minecraft server**, so your friends' server doesn't need Orbis: everything outside the upload becomes open sea, the world border goes round it, and museum and shop display frames are locked. Players who have Orbis installed still get the world map (street, satellite and elevation; the Blocks layer and teleporting by place name need Orbis on the server, and teleporting from the map uses `/tp`, for operators).
- `/orbis export aternos orbis` keeps the Orbis generator, for a server that runs Orbis Terrarum. Add `zip` to always get a single zip (trimmed to fit).

## Playing with friends / servers

- The mod only has to be on the **server**. Friends join with **plain Minecraft**; with the mod installed they also get the world map.
- **Easiest:** create and pre-generate the world in singleplayer, then upload the world folder to the server (you can leave out the world's `voxy` folder). Pre-generated areas need no internet on the server.
- **New world on a dedicated server:** set `level-type=orbisterrarum\:earth` in `server.properties` before the first start. The place and settings come from `config/orbisterrarum/orbisterrarum.json`.
- **Running it:** servers have no settings screen. The world's own settings travel inside it (an uploaded or exported world keeps its place, scale, height and sky); the mod's performance and network settings are changed with `/orbis settings` (op), and pre-generation with `/orbis pregen`.
- The server needs outbound internet for areas that aren't generated yet.
- Small servers (2–4 GB RAM): `/orbis settings regionCacheSize 24`, `regionPrefetchRadius 1`, `imageryTileCacheSize 128` and `demTileCacheSize 128`, then restart.

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
| Other mods (26.2) | Fabric API 0.159.0, Sodium 0.9.1, Sodium Extra 0.9.4, Iris 1.11.2, Voxy 0.2.19-beta, Xaero's World Map 1.46.1, MiniHUD, YetAnotherConfigLib 3.9.6, Mod Menu 20.0.2, Zoomify |
| Other mods (26.3) | Fabric API 0.161.0, Sodium 0.9.3-alpha.1, YetAnotherConfigLib 3.9.7, Mod Menu 21.0.0 |

**Test world: Bergen, Norway at 1:2** (1 block = 2 m), Minecraft 26.3

| | |
|---|---|
| Area | Bergen municipality, 566 km², fully pre-generated with the world (hard limit on) |
| World height | fitted to 1,408 blocks (Y −2032 to −625) |
| Chunks generated | 552,873 (666 region files) |
| Pre-generation time | **1.5 hours**, about **103 chunks/s** on average (about 40 with Orbis 1.0, 25–27 before its speed-ups) |
| World size on disk | 3.3 GB in total: terrain 3.0 GB, entities 238 MB, map layer 65 MB |
| Memory in use | about 3–5 GB of the 16 GB |
| FPS | 150–260 (capped at 260), render distance 8 (26.2 with Voxy) |

---

## Known issues

- **Generation needs internet** and is slower than vanilla; pre-generate for multiplayer. The public map servers are sometimes slow or busy — importing a map file (above) avoids that.
- **Voxy + shaders:** on Minecraft 26.2, Voxy 0.2.19's far terrain disappears with shaders when using Iris 1.11.4 + Sodium 0.9.2 (in every world, not just Orbis). Use **Iris 1.11.2 + Sodium 0.9.1** until Voxy updates.
- Very high mountains are squeezed to fit Minecraft's height limit.
- Minecraft 26.2 and 26.3 are supported.

How it works inside and all config options: [docs/technical-notes.md](docs/technical-notes.md).
