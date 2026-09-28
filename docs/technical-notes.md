# Orbis Terrarum — technical notes (how it works inside)

Orbis Terrarum generates the real world at true 1:1 scale: real elevation, real
coastlines, lakes and rivers, every OpenStreetMap building extruded with its
real colour, shape and roof, roads with markings and sidewalks, bridges,
tunnels, railways, forests, farmland, parks, street lamps, fences, and
hand-authored schematics for famous landmarks. Data streams in on demand as
you explore, so there is no world-size limit and nothing to pre-download.

Minecraft `(0, y, 0)` is the configured origin (default: Bergen, Norway).
The dimension spans the engine maximum, Y -2032..2031, with real sea level
at Y -1700, so a column's Y is roughly `elevation - 1700` (see "Vertical
mapping" for what happens above 1500 m).


## Installing

*Orbis terrarum* is Latin for "the circle of the lands", the Roman name for
the whole known world. The mod was called TellusPlus until 0.3.0; an existing
`config/tellusplus` folder (with its caches) is migrated automatically and
worlds created under the old name still load.

Put `orbisterrarum-<version>.jar` in `mods/` together with Fabric API. Two
optional mods make the settings screens available: **YetAnotherConfigLib**
(`yet_another_config_lib_v3`) and **Mod Menu**. Without them the mod still
works and everything can be edited in `config/orbisterrarum/orbisterrarum.json`.

## Choosing a place: the world settings screen

Orbis Terrarum is a world type of its own; every other world type (Default,
Large Biomes, Amplified, Flat, ...) stays plain vanilla, Customize included.
On the **Create New World** screen, open the **World** tab, set
**World Type: Orbis Terrarum** and press **Customize**. The world
type comes from `data/orbisterrarum/worldgen/world_preset/earth.json`; its
overworld keeps vanilla's `minecraft:overworld` dimension type name (so
shaders, Voxy and other mods treat it as the overworld) and gets its height
from a pack written into the new world (`datapacks/orbis_world`), which is
why vanilla worlds keep vanilla's height. The screen has these tabs:

- **Location** — a preset list (Bergen, Oslo, London, Paris, Tokyo, New
  York, Sydney, Mount Everest, …), or type any place name or "lat, lon" and
  press *Search*: the name is resolved through OpenStreetMap's Nominatim and
  the latitude/longitude fields are filled in.
- **Scale & Height** — metres per block, the mountain mode (see "Vertical
  mapping"), the 1:1 knee, the relief smoothing radius, sea level Y.
- **Features** — buildings, roads, water, land cover, trees, street
  furniture, landmark schematics, bedrock.
- **Style** — hollow buildings, windows, doors, storey height, centre-line
  colour, sidewalks, tree density, climate override, temperature offsets.
- **Data Sources** — aerial imagery and high-resolution elevation on/off.

Pressing **Create New World** first shows a progress screen, "Downloading
the map around your spawn", until the four map regions around the origin
have arrived (vanilla's spawn search generates chunks on the server thread
and would otherwise freeze the game while Overpass answers). It resumes by
itself; *Back* returns to the form.

Pressing *Done* stores the settings **inside the new world's generator**
(level.dat), so the world keeps generating the same way no matter what the
config file says later, and also makes them the defaults for the next world.
The spawn area for the chosen place starts downloading immediately, while
you finish naming the world.

The same screen, plus the **Performance & Network** tab (streaming, caches,
parallel downloads, terrain-only distance), is reachable from **Mod Menu →
Orbis Terrarum → Configure**. That one edits the installation config.

A dedicated server has no screen: it uses `config/orbisterrarum/orbisterrarum.json`
(the same keys) when it creates its world.

## What gets generated

The data-flow diagram in `docs/data-sources.html` (published at
https://claude.ai/artifact/GBuR7JfbKmn8QHfhdHZexG) shows every external
source, the stage it feeds, how it gets in and its current status. Keep it
in step with this section whenever a source is added or changes.

### Elevation
- **Mapterhorn terrain tiles** (`demTileUrl`, the default since config
  version 6): the open successor of the AWS terrain tiles, 512-pixel
  terrarium-encoded lossless WebP on the slippy grid up to zoom 16, built
  from the national lidar terrain models wherever a country publishes one
  (Norway's Kartverket 1 m DTM, Switzerland, Austria, Germany, Japan, the
  USA, and more, see https://mapterhorn.com/attribution) over Copernicus
  GLO-30 everywhere else. At the default zoom 15 that is about 1.2 m per
  pixel at 60 N and 2.4 m at the equator; zoom 16 takes all of a 1 m
  survey. Java cannot read WebP, so the TwelveMonkeys WebP reader (BSD-3,
  3.15.2) ships as whole jars inside the mod jar
  (`src/main/resources/orbis-libs`) and is loaded through its own class
  loader (`dem/TileImages`). Other mods bundle their own copy (YACL has
  3.12.0, whose lossless decoder scrambled a Mapterhorn tile into a 2 km
  pillar); isolated, neither can take the other's place. A decoded tile
  with heights no place on Earth has is replaced by its parent tile, in
  case a decoder ever fails again. Tiles are cached under
  `dem-cache/tiles.mapterhorn.com/`; the old AWS cache is left alone. The
  sampler is unchanged: bicubic (Catmull-Rom) interpolation, tile-seam
  stitching, outlier smoothing, a memory budget counted in 256-pixel tile
  units (a Mapterhorn tile costs four), disk cache. Mapterhorn only serves
  the zooms an area's data justifies (zoom 12 where only Copernicus GLO-30
  exists, 14 around Sydney, 16 over 1 m lidar such as Bergen, Tokyo or New
  York); a tile the server does not have is built from its parent one zoom
  up (bilinear, remembered as `.missing`), so one zoom setting works
  everywhere on Earth and simply carries no more detail than the data
  has. Tile downloads run on a pool of eight threads (vanilla's spawn
  selection samples biomes over a 20 km circle, which at zoom 15 would
  otherwise be hundreds of simultaneous requests on one connection), and
  biome decisions use a zoom-11 sampler over the same tiles, since a
  climate call needs the rough height only. Existing worlds keep
  their stored zoom and switch source on upgrade, so chunks generated
  afterwards can show a seam against old ones; new worlds are consistent.
- **Sea floor from Open Waters Seascape** (`useBathymetry`, on by default):
  wherever the land tiles report the ground at or below sea level (they
  carry 0 over water), the depth comes from Seascape's terrarium tiles,
  GEBCO 2026 with regional surveys such as EMODnet and NOAA where they
  exist (regional detail to zoom 14, global below), capped at
  `maxSeaDepthMeters` (300) because the dimension has 332 blocks under sea
  level. Byfjorden off Bergen comes out at 280 m, Vågen harbour at 7 m, the
  open Atlantic at its real kilometres (capped). Cached under
  `dem-cache/tiles.openwaters.io/`. Lakes and rivers keep the fixed depth
  rules. Licence CC BY 4.0, (c) Open Waters.
- **GeoTIFF web services** (`elevationSources`, off by default now): any
  service returning a float32 GeoTIFF for a lat/lon box (USGS 3DEP,
  Kartverket WCS are configured). They only make sense where a service is
  finer than what Mapterhorn already carries. Each source has a coverage
  box; a failing source disables itself for ten minutes.
- **Building heights from the GlobalBuildingAtlas** (`atlasBuildingHeights`,
  on by default): TUM's atlas gives every building on Earth a height
  (satellite-derived, 3 m height maps: expect a metre or two of noise on
  small houses). Taylor Geospatial mirrors it as public GeoParquet, one
  file per 5 x 5 degree tile (Norway's is 113 MB, big cities' tiles run to
  10 GB), licence CC BY-NC 4.0 (free use, not for sale). Fill the store for
  your area once:

  ```bash
  pip install pyarrow
  python tools/gba_heights.py --bbox 60.20 5.05 60.55 5.65 --out <instance>/config/orbisterrarum/heights
  ```

  The tool reads only the row groups that overlap the box with HTTP range
  requests (a few megabytes for a city, whatever the tile's size) and
  writes 0.1-degree cell files of `[osmId, lat, lon, height]`. In the
  rasteriser the order is: OSM height or level tags, then the lidar
  surface model, then the atlas (matched by OSM way id, else the nearest
  atlas building within 20 m of the centroid, so buildings the atlas took
  from Microsoft or Google count too), then the storey guess. `/orbis here`
  reports `atlas` as the height source. In Bergen 90 % of the atlas
  buildings carry an OSM id, so nearly every untagged building gets a
  measured height.
- **Surface models** (`surfaceModelSources`): where a lidar surface model
  (tree/building tops) covers a building, its real height is measured as
  surface minus terrain over the footprint, replacing the storey-count
  guess. Shipped: Kartverket 1 m DOM (Norway). `/orbis here` shows whether
  a building's height came from tags, the surface model, or a default.

### Vertical mapping (`dem/VerticalMapping`)
Minecraft allows at most 4064 blocks between the floor and the ceiling of a
dimension; Everest alone is 8 849 m. The world therefore uses the full
Y -2032..2031 range with sea level at Y -1700 (3 731 blocks for mountains,
332 for the sea floor) and converts metres to Y in one of three modes
(`verticalMode`):
- **relative** (default) — exactly 1:1 up to `reliefKneeMeters` (1500 m),
  which covers every coast and almost every city. Above the knee the terrain
  is lowered by how far the *regional* average elevation (the global DEM
  smoothed over `reliefSmoothingKm`, 25 km) exceeds the knee: high plateaus
  and valleys sink, but every peak keeps its full local relief above them.
  Whatever still comes within `softCeilingBlocks` (900) of the ceiling is
  squeezed by a smooth asymptotic curve, so nothing is ever cut flat.
  Examples: Bergen unchanged; Galdhøpiggen (2469 m) → Y 769, true height;
  Mont Blanc → 2 380 blocks above Chamonix (real: 3 770); Everest → Y 1948,
  2 350 blocks above the Khumbu glacier (real: 3 850), and Lhotse is 30
  blocks lower than Everest, as it should be.
- **compress** — no regional shift, only the smooth squeeze near the ceiling.
- **clamp** — plain 1:1 cut off at Y 2031 (mountains above 3.7 km become mesas).
`/orbis here` and the F3 screen show the relief shift applied at a column.
Because vanilla paints the sky black below the horizon whenever the camera
is under Y 63 (unless the world is flat), the server flags every Orbis Terrarum
world as "flat" in the login/respawn packet: that moves the horizon to the
world floor on **every** client, vanilla ones included, and the flag is used
for nothing else. Clients with the mod also patch it locally.

### World height fitted to the place (`worldgen/WorldHeight`)

Every Orbis world used to be the full engine range, Y -2032..2031 (4 064
blocks, 254 sections), so that the whole Earth fits at 1:1. Vanilla pays
per section whatever the section holds: sky light runs down through all of
them, heightmaps span them, saving and loading serialise them, and each one
takes memory while the chunk is loaded. Around a city 1 000 m high, 200 of
those sections are empty air paid for on every chunk; a thread profile of a
sweep put more than half of the busy worker time into that vanilla work.

New worlds therefore get a **ceiling fitted to their place**: when a world
is created, the terrain within 30 km of the origin is sampled from the
coarse elevation tiles, and the world's height is set to the highest point
plus 512 blocks (flight, the cloud layer, tall buildings), rounded to 16,
never below 1 024 nor above 4 064. Bergen comes out around 1 800 to 2 200
blocks tall, which makes every chunk roughly twice as cheap to generate,
light, save and keep in memory, and halves the size of a pregenerated world
and the RAM a server needs per loaded chunk. The floor (Y -2032) and sea
level (Y -1700) never move: the sea floor and the underground need them,
and the vertical mapping is unchanged inside the range. Terrain above the
ceiling (far outside the fitted area) gets the same soft squeeze the
Himalaya gets in a full world, only lower.

The height reaches vanilla through the dimension type, which is data: the
mod's own `data/minecraft/dimension_type/overworld.json` sets the full
height, and a small data pack written into the world's `datapacks` folder
(`orbis_world`) overrides it with the fitted one. World data packs load
before the world's registries, are sent to every client at login (vanilla
clients included) and travel with the world folder, so the world stays
self-describing. In singleplayer the pack is written into the folder the
new world takes its data packs from and the creation context is reloaded
before the world is created (the same wait screen as the spawn data); a
dedicated server gets the pack written next to its `level-name` folder at
start-up when that world does not exist yet. A world's height cannot change
after creation; the setting's value is resolved once and stored with the
world's settings, and on every start the model is checked against the
level's actual dimension type.

`worldHeight` (config and world settings screen): 0 = fit (default), or a
fixed height, a multiple of 16 between 1024 and 4064; 4064 is the old
full-Earth range, for worlds meant to reach Everest. Existing worlds keep
their full height.

### Aerial imagery (real colours everywhere)
- `imagerySources`: orthophoto / satellite tile layers with coverage boxes.
  Shipped: Norge i bilder (Norway), USDA NAIP via USGS (USA) and Esri World
  Imagery (global). Tiles are cached on disk; sources that fail are skipped
  for a while. Attribution for the sources you use is printed at startup.
- **Roof colours**: every building without an OSM roof colour/material gets
  the median orthophoto colour of its footprint interior, matched to the
  closest roof block — so a red-tiled street is red and a tar roof is dark.
- **Ground cover**: every column that OSM only zones (residential,
  industrial, unmapped, …) is classified from the imagery into lawn, tree
  canopy, light pavement, asphalt, bare soil, sand, rock or snow (3×3
  majority filter, building shadows are not treated as asphalt). Detected
  canopy gets trees even outside OSM forest polygons; detected pavement
  never gets trees; clearings inside mapped forests stay open.

### Buildings (`osm/FeatureRasterizer`, `render/ColumnPainter`, `render/Materials`)
- Every OSM `building` and `building:part` footprint, including multipolygon
  relations with courtyards (inner rings are holes).
- Height from `height`, else `building:levels` × storey (3 m), else a
  type-based default. `min_height` / `building:min_level` for parts.
- **Colour matching**: `building:colour` / `roof:colour` (hex or any CSS /
  common OSM colour name) is matched to the perceptually closest block using
  CIE Lab distance plus a hue term, over a palette of ~120 blocks with their
  average texture colours (all concrete + terracotta colours, bricks, stone,
  sandstone, quartz, planks, copper family, …). Textured stone gets a small
  penalty so painted facades don't become cobblestone.
- **Materials**: `building:material` / `roof:material` (brick, stone,
  concrete, wood, plaster, glass, metal, slate, roof_tiles, copper, thatch,
  tar_paper, …) map to matching blocks; material words in the colour field
  are handled too.
- **Defaults** when nothing is tagged: chosen per building type
  (house / apartments / office / industrial / church / mosque / barn / …)
  with a regional style from latitude (Nordic: white, falu-red, ochre wood
  and dark roofs; Mediterranean: white/beige walls, orange tile roofs; …)
  and per-building variation from a hash of the OSM id — no identical clones.
- **Roof shapes** (`roof:shape`): flat, gabled, hipped, half-hipped,
  pyramidal, skillion, gambrel, mansard, round, dome, onion, cone; ridge
  along the footprint's longest edge; hipped/pyramidal use true distance
  to the outline so L-shapes work; `roof:height` / `roof:levels` honoured.
  Mosques and `dome=yes` get domes; mosques get four corner minarets.
- **Facades**: hollow interiors with a floor slab per storey, window rows
  (glass; tinted glass curtain walls for tall offices; stained glass for
  churches), corner quoins in an accent block, parapet band on flat roofs,
  a door on the wall facing the nearest street (wood or iron to suit the
  wall). `building=roof` becomes a roof on corner posts.
- Buildings sit on the lowest footprint elevation; uphill terrain inside
  the footprint is cut, downhill gets a foundation. Buildings inside a
  landmark's schematic radius are suppressed.

### Roads, paths, rails (`render/Materials.roadSurface`)
- Width from `width` / `lanes` or by highway class; `surface` tag mapped
  (asphalt, concrete, paving stones, sett/cobbles, gravel, dirt, grass,
  wood, sand, brick, tartan, …).
- Sidewalks (smooth stone) on residential/tertiary/secondary/primary
  streets unless `sidewalk=no/separate`; dashed centre line (white or
  yellow, configurable) on two-way roads ≥ 5 wide; solid edge lines on
  motorways/trunks/primaries; zebra stripes at `highway=crossing`.
- `bridge=yes` → a deck that actually clears what it crosses: ground ways
  are drawn first, then bridges from the lowest `layer` up, and each deck is
  lifted wherever it passes over a road (5.5 blocks), a railway (6.5), a
  path (3.5), and never lower than a structure of its class would be over
  open ground or water (main roads and railways 5, minor roads 3,
  footbridges 2 — a viaduct over a car park is still a viaduct), with every lift spread at a 1:8
  grade so flyovers and stacked interchanges read as real ramps. The street
  underneath keeps its asphalt (a separate "under" layer in the raster), and
  the ground ways connected to a bridge end climb to the deck as embankments
  at the same grade, continuing through split ways. Stone-brick underside,
  railings (stone wall / fence / iron bars by road type), pillars every 12 m
  where nothing runs below.
- `tunnel=yes` → the road between the two portals is pushed down wherever it
  would come within 6.5 blocks of the ground above it, across its whole
  width (so a hillside falling away beside it does not open the wall), of a
  street crossing above, or of the floor of a building it passes under,
  spread at the same 1:8 grade. The ground cover and the building floors are
  sampled along the whole way from the terrain and the map, not through the
  region's raster, so a tunnel crossing several regions gets the same floor
  in each of them (that raster-bound sampling used to leave 2–3 block steps
  at region boundaries). The tunnel is carved as a 4-high passage under
  streets, buildings and lake beds alike; a deeper tunnel crossing under a
  shallower one is carved under it; a flyover over a street never cuts the
  tunnel below that street; and the vanilla underground band (caves,
  mineshafts, geodes, monster rooms) is pushed at least 24 blocks below the
  lowest tunnel floor in the region, so nothing vanilla breaks into it. A
  portal that ends up below ground gets a cutting along its approach road.
  Passages through buildings (`tunnel=building_passage`/`covered`) stay at
  street level and keep the building's ground floor open along the passage.
- Where one tunnel way hands over to the next (OSM splits long tunnels at
  junctions, lane changes and name changes), the shared node is inside the
  rock, not a portal: no portal margin there, and every way through the node
  meets it at one floor. A first pass works out, for each way ending at such
  a node, the floor it can reach there (terrain along the whole way at the
  way's width, buildings within 64 blocks of both its ends, both ends treated
  as inside the rock); the lowest value wins for all of them, and each way's
  profile is then pinned to that floor at the node and ramped away from it
  at 1:8. For a node near or beyond the edge of a region's data the ways and
  buildings around it come from a lookup on the map source (`WayLookup`),
  one hop further for the ends of the ways that lookup adds, so every region
  decides the same about the same node (used to leave 12–27 block steps at
  junctions and along region boundaries). Two carriageways that diverge from
  a shared node at different grades still show a step along their seam
  while they overlap; each one's own floor stays continuous.
- Inside a tunnel: the block over the passage is always a stone-brick lining
  (the subsoil under a street or a field is gravel, and gravel over a void
  drops into the tunnel at the first block update, which used to block
  them), the tunnel itself is unlit, and every 128 m, alternating sides, an
  alcove in the wall holds a chest sized for the fight it takes to reach it
  in the dark (`tunnelLoot`): 12–24 iron, 2–5 diamonds in one chest out of
  four, three to five lots
  of emeralds, gold, copper, lapis, redstone, experience bottles, golden
  apples, torches, arrows, food or TNT, and one enchanted iron tool
  or armour piece (now and then a diamond pickaxe or sword). The loot table
  `orbisterrarum:chests/tunnel_depot` lives in the world's
  `datapacks/orbis_landmarks` pack, which is (re)written on start when it is
  missing, so existing worlds pick it up too.
- Railways: gravel bed + rails; runways/taxiways with markings; piers on
  posts over water; breakwaters. A tram or railway drawn inside a busier
  street (Bergen's Bybanen along Nygårdsgaten) keeps its rail on the
  street's surface: the street wins the surface, the rail centreline is
  remembered as an "embedded rail" cell, and the painter and the transit
  pass treat it like any other rail, so the line stays continuous through
  every junction it shares with a road instead of breaking wherever the
  road covered it.
- Rideable track (`render/Transit`): the painter lays a rail on every
  centreline cell at that column's ground; the transit pass then makes it a
  track vanilla minecarts can actually run: a 45° line that runs between two
  cell columns (two centreline cells per row, a zig-zag of nothing but
  curves) is thinned to one diagonal; a corner rail on every diagonal
  step, chosen on the side that keeps the line's own cells straight (only
  a straight rail can climb) and set at the higher of the two ends; a
  levelling pass that raises any curve sitting below a neighbour and any
  rail in a one-block dip onto its neighbours' level (up to two blocks, on
  fill copied from the block it stood on), because vanilla curves only join
  rails on their own level; then shapes (straight, curve, ascending) from
  the rails around each one. Railways run on into station buildings (the
  hall's ground floor stays open along the track). The offline simulator
  `harness/RailSim.java` lays a region's rails exactly this way and drives
  a virtual minecart over them with vanilla's connection rules: Bybanen
  through the city centre and the Fløibanen funicular come out as unbroken
  lines (0 breaks, 114- and 84-rail rides end to end); in the station yard
  the only "breaks" left are between neighbouring sidings that were never
  one track.
- Junction priority: motorway > … > footway, higher `layer` wins.

### Water
- `natural=coastline` → sea side by multi-source flood fill (land on the
  left, water on the right), so fjords and harbours are exactly right.
- Lakes/ponds/reservoirs/basins/pools (incl. multipolygon islands) get one
  flat surface at the median shoreline elevation and a real bed; tidal /
  fjord water is pinned to sea level. Rivers, streams, canals, ditches are
  drawn from their centreline and width, cut one block below the banks.
- Outside coastline data, anything below sea level is ocean (bathymetry).
- Ice in cold climates, seagrass in shallow water, lily pads on ponds.

### Land cover and vegetation
- **ESA WorldCover gap filling** (`worldCoverLandCover`, on by default):
  wherever OpenStreetMap has no landuse or natural polygon, the land cover
  comes from ESA WorldCover 2021, a 10 m global map (tree cover, shrubland,
  grassland, cropland, built-up, bare, snow, wetland, mangroves, moss). The
  mod reads the cloud-optimised GeoTIFFs on AWS Open Data directly
  (`landcover/WorldCoverProvider`): one 3 x 3 degree file per granule, of
  which only the header and the 1024-pixel tiles a region touches are
  fetched with HTTP range requests and cached under `worldcover-cache/`.
  No import step, works anywhere on Earth between 60 S and 84 N. Water
  classes are ignored (the coastline and OSM decide water); bare ground
  becomes sand below 35 degrees of latitude and 1500 m, rock elsewhere.
  Licence CC BY 4.0, (c) ESA WorldCover project 2021.
- landuse / natural / leisure / amenity polygons → ~50 cover classes:
  forests (conifer / broadleaf), scrub, heath, meadow, farmland (with
  rotating crops per field), orchards, vineyards, parks, gardens,
  residential, industrial (gravel), parking (asphalt with bays), pitches
  (grass / artificial turf / hard), playgrounds, cemeteries, beaches, bare
  rock, scree, wetlands, glaciers, quarries, construction, pedestrian
  squares, piers, aprons, …
- Procedural trees (oak, birch, spruce, pine, jungle, acacia, cherry, dark
  oak, palm, bush) at cover- and climate-dependent densities with
  blue-noise spacing; `natural=tree` nodes (genus / leaf_type / height) and
  `natural=tree_row`; ground plants, flowers, ferns, mushrooms, cacti.

### Street furniture and structures
- Street lamps (lit lanterns), traffic signals, benches, bus stops, waste
  baskets, shelters, fountains, bollards, flagpoles, masts, lighthouses,
  power towers and poles, fences (wood / metal / concrete), walls (stone /
  brick / concrete), hedges, retaining walls, city walls, guard rails.
  Furniture mapped on the carriageway is nudged onto the sidewalk.

### Landmarks (`landmark/`)
- `config/orbisterrarum/landmarks.json` lists landmarks (name, lat, lon,
  schematic file, rotation, yOffset, radius). Schematics in
  `config/orbisterrarum/schematics/` may be WorldEdit/FAWE Sponge `.schem`
  (v1–v3) or vanilla structure-block `.nbt`. They are pasted at the terrain
  height (or a fixed `baseY`) across chunk borders, and OSM buildings inside
  the radius are suppressed. Missing files are just logged.
- Without a schematic, tag-based shapes still avoid "flat slab" landmarks:
  domes, minarets, spires (`roof:shape=cone`), roof profiles.

### Climate and biomes (`biome/`)
- Mean annual temperature from latitude + elevation lapse rate
  (+ configurable offset); zones: ice cap, tundra, alpine, taiga,
  temperate, subtropical, arid, savanna, tropical. `climateOverride`
  ("arid" / "humid") resolves the precipitation ambiguity for a region.
- `RealWorldBiomeSource` picks vanilla biomes per column for grass/foliage
  tint, water colour, snow and spawns from climate + land cover + water
  (ocean variants by temperature and depth, river, beach, swamp, forest,
  taiga, jungle, desert, peaks, snowy variants…). Snow layers / ice where the
  estimated temperature is below `snowTemperatureC`.

### Deep fill written straight into the sections

The rock under a column (some 330 blocks of deepslate and stone under
Bergen), the subsoil and a water column are runs of one block each. They
used to go through the chunk's general block setter one block at a time,
each call updating two heightmaps as well, which made them the bulk of all
block writes in a chunk. The painter's sink now has a `fill` for such runs:
the generator writes them straight into the chunk sections (no per-block
bounds, light or post-processing checks, which none of these blocks need)
and updates the heightmaps once, at the top of the run, which is all a
heightmap records. Blocks that need the full treatment (surface, buildings,
anything that emits light or is post-processed) still use the setter. The
result is identical block for block; it was checked by generating the same
seed and area with both builds and comparing every section.

### Where generation time went, and what was cut (September 2026)

A profile of a Bergen 1:2 sweep (Java Flight Recorder, test server) found:

- **Canopy lookups outside the imported tiles.** Every tree-height sample
  where no canopy tile exists asked the disk again and built an exception
  with a stack trace; a map region takes a million samples, so a sweep
  whose first rows lay outside the canopy import made no chunks for
  minutes. Missing tiles are now remembered (`dem/CanopyProvider`).
- **Block writes.** Painting went through the section's general setter,
  whose per-block fluid and ticking bookkeeping was 10% of all CPU. Blocks
  now go straight into the palette container and each touched section is
  counted once after painting (before ores, which use the normal setter).
  Sections the terrain reaches start with an 8-block palette of the common
  blocks, so the first write does not copy the section into a bigger one.
- **Aerial photos.** Decoding read pixels one at a time through the colour
  model (8%); `dem/TileImages.rgbPixels` reads the JPEG bytes directly
  (pixel for pixel identical, 14 times faster).
- **Biome cache.** Its Long keys hashed to qx ^ qz, so diagonals shared a
  bucket and the map degraded to trees; it is now a lock-free
  direct-mapped array with a mixed hash (`biome/RealWorldBiomeSource`).
- **Elevation and canopy tiles.** Each of a sample's 16 pixel reads built a
  text key and took a lock; the bicubic sampler now keeps the last four
  tiles per thread.

Checked by sweeping the same 3,209 chunks with both builds and comparing
all 1.16 billion blocks: terrain is identical; the few differing chunks
(dungeon cobwebs, mineshaft planks, glow lichen) differ just as much
between two runs of the same build, since vanilla features that cross
chunk borders depend on the order threads finish in.

### Generation pipeline (`worldgen/RealWorldChunkGenerator`)
- OSM is streamed per 512 × 512 m region with a 32 m margin (so nothing
  breaks at region edges), fetched from Overpass, cached on disk (gzip) and
  kept in a bounded LRU. Downloads are priority-scheduled: a region a chunk
  is waiting for always jumps ahead of speculative prefetches, up to four
  run in parallel (per-endpoint limits), and every two seconds the regions
  within `regionPrefetchRadius` of each player are queued so the frontier
  is usually decoded before the player reaches it. The spawn regions are
  downloaded at game launch.
- Chunk generation never blocks a world-generation thread on a download:
  `createBiomes` and `fillFromNoise` chain onto the region's future, so
  chunks in already-decoded regions keep generating at full speed while a
  new region is still in flight. With `waitForOsm=true` (default) a chunk
  still only completes once its region is available, so worlds are never
  missing data.
- Vanilla carvers, structures and biome features are disabled (no villages
  or ravines through a real city). Fences/walls/rails/stairs/doors are
  post-processed so they connect properly.
- Landmark schematics are pasted per chunk as an override layer.

## Commands
- `/orbis tpll <place or lat, lon>` — teleport to a place name ("Kathmandu",
  "Eiffel Tower", "Bergen, Norway") or a coordinate ("27.7172, 85.3240").
  Names are resolved through Nominatim, Photon or an Overpass place lookup,
  whichever answers. `/tpll` is a short alias. Both need operator permission (level 2, like `/tp`).
- `/orbis where` — your position as lat/lon (`/wherell` is an alias).
- `/orbis here` also prints the place name of the column ("Place: Thamel,
  Kathmandu, Nepal") a moment later, from a reverse lookup.
- `/orbis pregen …` — see "Pre-generating an area".
- `/wherell` — your position as lat/lon.
- `/orbis info` — origin, scale, cache statistics.
- `/orbis here` — everything the mod knows about the column you stand on
  (elevation, climate, land cover, road, water, building, decor).
- `/orbis prefetch <radius>` — pre-download OSM regions around you.

## Configuration — `config/orbisterrarum/orbisterrarum.json`
Written with defaults on first start. Key settings:

| key | default | meaning |
|---|---|---|
| `originLat`, `originLon` | 60.39299, 5.32415 | real-world point at block (0,0) |
| `metersPerBlock` | 1.0 | 1.0 = true 1:1 |
| `seaLevelY` | -1700 | block Y of real sea level (dimension is fixed at Y -2032..2031; changing this needs a new world) |
| `verticalMode` | relative | `relative`, `compress` or `clamp` (see "Vertical mapping") |
| `reliefKneeMeters`, `reliefSmoothingKm`, `softCeilingBlocks` | 1500, 25, 900 | relative-mode tuning |
| `demZoom` | 13 | Terrarium zoom (13 ≈ 20 m/px) |
| `useHighResElevation` | true | use `elevationSources` where they have coverage |
| `elevationSources` | USGS 3DEP, Kartverket DTM 1 m | GeoTIFF terrain services with coverage boxes |
| `surfaceModelSources` | Kartverket DOM 1 m | lidar surface models for measured building heights |
| `useAerialImagery` | true | sample orthophoto colours |
| `imagerySources` | Norge i bilder, NAIP, Esri World Imagery | tile layers with coverage boxes |
| `imageryZoom` | 18 | 0.6 m/px at the equator, 0.3 m at 60° |
| `imageryRoofColours`, `imageryGroundClassification`, `imageryTreeCover` | true | what the imagery is used for |
| `overpassUrls` | kumi.systems, private.coffee, overpass-api.de | tried in order; failover on 429/5xx/timeout |
| `overpassConcurrentRequests` | 1 | public instances rate-limit per IP; raise only when self-hosting |
| `osmMaxWaitMinutes` | 15 | how long a chunk waits for its region before generating terrain-only |
| `prefetchSpawnAtStartup` | true | download the spawn regions while you are in the main menu |
| `regionSizeBlocks`, `regionMarginBlocks`, `regionCacheSize` | 512, 32, 24 | streaming granularity / memory |
| `waitForOsm` | true | block chunk generation until region data arrives |
| `terrainOnlyBeyondBlocks` | 0 (off) | chunks farther than this from every player generate at once from elevation only (no OSM wait); for Voxy WorldGen / Distant Horizons style LOD generation. Those chunks are not saved by such mods, so they regenerate in full when visited |
| `generate*` | true | per-feature toggles (buildings, roads, water, land cover, trees, street furniture, schematics) |
| `hollowBuildings`, `buildingWindows`, `buildingDoors` | true | facade detail |
| `roadCenterLineColour` | white | `white`, `yellow` or `none` |
| `roadSidewalks` | true | auto sidewalks on residential+ streets |
| `treeDensityForest` … | 0.045 | trees per column |
| `climateOverride` | "" | `arid` or `humid` |
| `snowTemperatureC`, `temperatureOffsetC` | 0.5, 0 | snow line tuning |
| `metersPerStorey` | 3 | storey height when only levels are known |

## Testing

- Compiles cleanly against the real 26.2 API (every signature was checked
  with `javap` against the game jar — 26.2 renamed a lot: `Identifier`,
  `Blocks.CONCRETE.white()`, `Blocks.COPPER_BLOCK.waxed().oxidized()`,
  `IRON_CHAIN`, Optional-returning NBT getters, …).
- An offline harness booted Minecraft's registries and ran a synthetic
  Overpass response (houses with colours and roof shapes, apartments, a
  mosque, streets with sidewalks, a footway, a river, a lake with an island
  as a multipolygon, a forest, a coastline, a bridge, a fence, a tree, a
  lamp, a crossing) through parse → rasterise → paint, and checked the ASCII
  map, roof profiles, column cross-sections, trees and a Sponge schematic
  round-trip. All behaved as designed.
- **First live run (Bergen, 2026-09-14)**: the mod loaded, and regions 0,0
  and -1,-1 downloaded and rasterised in ~14 s each (418 / 700 buildings,
  830 / 730 roads, coastline detected). The prefetch burst then hit
  overpass-api.de's per-IP limit (HTTP 429, followed by a temporary block),
  and vanilla's spawn search — which generates chunks synchronously on the
  server thread — sat waiting, so the game looked frozen after "Create New
  World". Fixed by: one request in flight, endpoint failover with a shared
  cooldown, a bounded wait, and downloading the spawn regions at game
  launch while you are still in the menu (`prefetchSpawnAtStartup`).
- Third live run (Bergen, then Everest): flyovers came out at ground level
  because a deck was only interpolated between the terrain at its two ends,
  and Everest was a flat mesa at the old Y 767 ceiling. Both fixed as
  described above; the offline harness now includes a flyover (deck 5
  above the crossed street, ramps 1:8, street kept under the deck) and the
  mapping numbers for the Himalaya, the Alps and Norway.
- Fourth live run (Bergen, Nygårdstangen): the "flat flyovers" turned out to
  be the E39 *tunnel* under the interchange: a tunnel was a straight line
  between its portals, and where the DEM dipped it surfaced and ran across
  the streets. Found by replaying the game's own cached Overpass responses
  and DEM tiles through the rasteriser offline (`BridgeProbe`/`SpotProbe` in
  the dev harness): every bridge in central Bergen was already 3–17 blocks
  up (Puddefjordsbroen, Nygårdsbroen), the tunnels were up to 9 blocks
  *above* ground. Tunnels now get the mirror treatment of bridges. The same
  run also showed the sky going black above the horizon: the dimension type
  had lost vanilla's `timelines`/`default_clock`/sky attributes when it was
  first written; it is now a copy of vanilla's with only the height, and cloud
  height changed.
- 0.3.0 added the world settings screen (YetAnotherConfigLib), per-world
  settings stored in the generator, Nominatim place search, Mod Menu
  integration and `terrainOnlyBeyondBlocks`. The settings codec was
  round-tripped through NBT offline; the screens themselves compile against
  the real YACL/Mod Menu jars but were not opened in-game by the author.
- **Still untested live**: the settings screens, the taller dimension's effect on chunk load and
  lighting speed, the client horizon mixin, tunnel portals against real
  DEM noise, long exploration sessions, and the exact URLs of the high-resolution
  services (USGS 3DEP exportImage, Kartverket WCS DTM/DOM, Norge i bilder
  WMTS, NAIP tiles). Each was written from the services' documented request
  formats but not exercised from this machine; every one of them fails
  safe (a log line, then the next source or the global data takes over),
  and the URL templates are plain config entries you can correct without
  rebuilding. The imagery sampling, classification and roof-colour code
  paths were verified offline with synthetic tiles planted in the cache.

## Networking: surviving a broken DNS resolver

Every download (Overpass, elevation tiles, imagery, the place search) goes
through `net/OrbisHttp`. When the operating system's resolver cannot look a
host up (some ISP and router resolvers silently drop names such as
`openstreetmap.org` or `amazonaws.com` while browsers, which do their own
encrypted DNS, keep working), the host is resolved over DNS-over-HTTPS
(Google, then Cloudflare, reached by IP) and the request is repeated over a
TLS connection opened to that address, with the real host name used for SNI
and certificate validation. The first time this happens a line
"The system DNS resolver cannot resolve … using DNS-over-HTTPS" is logged.
No extra software or DNS change is needed on the player's side.

## Survival: ores, animals, and what is still missing

- **Ores** (`generateOres`): coal, iron, copper, gold, redstone, lapis, diamond
  and emerald veins plus granite/diorite/andesite/tuff/gravel/dirt patches,
  with vanilla-like densities and vein sizes, placed by *depth below the real
  surface* (vanilla's absolute Y ranges would leave a world whose ground is at
  Y -1700 empty). Deepslate variants below the deepslate line.
- **Animals** (`spawnAnimals`): spawned at chunk generation exactly as
  vanilla does it, so they only appear where the biome's rules allow (grass,
  daylight). Monsters spawn in darkness as usual; mines are dangerous.
- **Vanilla structures** (`vanillaStructures`): generated by vanilla's own
  placement code, then adapted. Underground ones (mineshafts, strongholds,
  ancient cities, trial chambers, buried treasure) are moved as a whole so
  that vanilla's Y 64 lands 100 blocks below the lowest ground within
  ~100 m of the start: their vanilla depth relationships are kept, they are
  reachable from any surface, and strongholds mean the End is reachable.
  (Vanilla files the stronghold under `surface_structures` and buries it
  with terrain adaptation, so it is recognised by that adaptation; as a
  safety net any start vanilla already put below the band top is sunk too.
  Every sink except mineshafts is logged as `[orbis] minecraft:stronghold at
  chunk … -> Y a..b`.)
  Surface ones (villages, temples, outposts, ruined portals, shipwrecks,
  monuments, igloos, mansions) are placed by vanilla's spread rules but a
  start whose footprint touches a real building, road, rail or water body is
  dropped, so they only appear on empty land. Toggle in the Features tab.
- **Lighting** (`interiorLights`, `streetLights`): every hollow building has
  a sea lantern in every sixth floor block, lighting the storey below and
  above, so nothing spawns indoors and a city does not eat the mob cap. Lamp
  posts stand every 24 m on both edges of lit and urban streets (and
  residential/living streets) in addition to the mapped ones. Parks, forests,
  yards and the countryside stay dark: hostile mobs spawn there at night as
  vanilla intends.
- **Villager residents** (`villagerResidents`, `residentsPerChunk`): a
  vanilla village is villagers plus beds plus workstations, so hollow
  buildings get households: a bed on the ground floor (one more upstairs
  in taller buildings) and an unemployed villager per bed, spawned at chunk
  generation. They claim the beds, sleep at night, wander by day, flee
  zombies and breed when a bed is free; enough of them together makes a
  vanilla village with iron golems. By default (`residentJobs` off) they
  are nitwits, the vanilla profession that never takes a job, and no
  workstations are placed, so the only traders in the world are the ones
  vanilla villages provide and finding a village keeps its value. Their
  children are born nitwits too (a mixin on villager breeding; residents
  carry the entity tag `orbis_resident`, which the children inherit), and
  curing a zombified resident gives a nitwit back, so there is no way to
  turn a city into a trading hall. With
  `residentJobs` on, residents are ordinary villagers and a workstation is
  placed only where OpenStreetMap names a business (bakery/butcher →
  smoker, library/bookshop → lectern, tailor/clothes → loom, hardware/
  blacksmith → smithing table, fishmonger → barrel, pharmacy → brewing
  stand, leather/shoes → cauldron, church → bell, farm buildings →
  composter, tourist information → cartography table, ...), and such a
  building always has a household. `residentsPerChunk` (default 0.5) is
  spread over the buildings of each chunk, capped at 2; each building is
  handled once by the chunk holding its centre. At 1:32 every procedural
  house gets a bed and a villager. Nothing is needed on clients: vanilla
  villagers, vanilla beds. Villager AI is the most expensive thing a server
  runs, and two things keep a city of residents affordable: each resident is
  born knowing its bed (the home memory is set at spawn, so the bed search
  never runs), and a mixin on the point-of-interest manager limits every
  non-portal POI query to the sections from 64 blocks below to 96 blocks
  above the chunk's surface and to loaded chunks. Vanilla walks all 254
  sections of this world for every chunk within 48 blocks of every villager
  looking for a bed or a bell, which at 4 residents per chunk stalled the
  server thread for minutes. Keep the density at 1 or below for smooth play,
  lower on Aternos. Residents can actually get about: every household building has a
  switchback staircase between its storeys (two lanes near the centre,
  each two blocks wide where the building has room, with three blocks of
  clearance over every step and over the foot of each flight, cut through
  the floor slabs where needed, because vanilla's path finder refuses a
  one-block step-up unless the cell being left is clear for the mob's full
  height above the new floor; buildings too small for one only get a
  ground-floor household), villagers spawn beside their bed
  rather than in it, doors are always wooden (villagers cannot open iron
  doors), every building gets a door even with no road nearby, the door
  goes on the side whose ground is closest to the sill (the walls start at
  the lowest corner of the footprint, so on a slope most sides face into
  the hill), and the three cells in front of the door are stepped down or
  up to the sill so the way out is walkable.
- **Name signs** (`buildingSigns`): a waxed wall sign beside every door
  with the building's name from the map, or the kind of business when it
  has no name ("Bakery", "Pharmacy"), so the city can be navigated by the
  names people actually use.
- **Furnished interiors** (`furnishInteriors`, 1:1 and 1:2 only): by what
  the building is used for. In OpenStreetMap the business is usually a
  point inside the building rather than a tag on it, so every tagged point
  inside a footprint (shop, amenity, tourism, office, craft, healthcare,
  leisure) is attached to that building and the first one that says a use
  decides the furniture; door signs use its name too. The extract importer
  keeps those points (a store imported before this needs re-importing) and
  the Overpass query fetches them. Libraries: bookshelves and a lectern.
  Schools: desks in rows facing a lectern. Fishmongers and market halls:
  barrels with cod on the walls and a smoker. Pharmacies: brewing stands
  and a cauldron. Hospitals and clinics: white beds on every floor with
  brewing stands. Hotels: a bed every third wall cell on every floor.
  Cafés and restaurants: tables with chairs, barrels, a smoker. Bars and
  pubs: a counter of barrels along the longest wall with bar stools and
  bottles above, tables, a brewing stand. Nightclubs: a chequered
  magenta-and-black dance floor with redstone lamps in the ceiling, a
  jukebox, note blocks along the walls and a bar. Theatres and cinemas: rows
  of dark seats facing a black screen with a red carpet down the aisle.
  Museums and galleries: exhibits on quartz pedestals (shells, fossils,
  sherds, old weapons, a nether star...) and framed art on the walls, on
  two floors. Supermarkets: aisles of shelves with an aisle between each
  pair and a loot chest. Gyms and sports halls: weights and anvils on the
  floor, punching bags and targets on the walls. Banks: a vault of iron
  bars round a chest of dungeon loot, and desks. Churches: pews and a
  bell. Other shops: shelves of barrels with their goods framed (bread,
  leather, redstone, diamonds, books, ...) and, in one shop in four, a
  chest with the matching vanilla village loot table. Offices: desks.
  Furniture keeps clear of the residents' beds and staircase.
- **Rideable railways** (`transitLines`): every railway, tram and
  light-rail line (Bergen's Bybanen, the Fløibanen funicular) is laid with a
  powered rail over a redstone block every tenth block along the line (on
  diagonal runs too: the joining pass leaves the line's own cells straight
  and moves any powered rail that lands on a curve to the straight rail next
  to it; every ascending rail is powered too, so a climb never stalls a
  cart), rails run through
  tunnels too, and the decoration pass connects the track: corners become
  curves, one-block climbs ascending rails, and diagonal runs, which the
  rasteriser draws as cells touching only at their corners, get a corner
  rail at every step (on whichever corner cell sits at the right height and
  on the track bed), shaped as a curve. Every station or tram stop node gets a minecart waiting on the
  nearest plain rail and a lantern post with the stop's name on a sign.
  Push the cart onto the line and ride the real network; slopes steeper
  than one block per cell still break it.
- **Street life** (`streetLife`): cats in residential streets, small herds
  of sheep and cows on meadows, grass and farmland, a wandering trader that
  never leaves on marketplaces, boats moored on the water of marinas,
  gravestones in rows across cemeteries (which get no lights, so vanilla's
  night spawns make them worth avoiding after dark), swings and slides on
  playgrounds, white touchlines around sports pitches, and flower beds in
  parks and gardens.
- **Parallel bridges share a deck.** Each carriageway of a big bridge is
  its own OSM way, and a footbridge often runs alongside; each used to get
  its deck height from the terrain at its own two ends, which differ when
  one starts on the ramp and another on the quay, so the decks came out
  stepped. Every bridge cell is now lifted to the highest deck of any other
  bridge way running the same direction on the same layer within 12 m, and
  the approach ramps climb to the lifted deck. Crossing flyovers are left
  alone.
- **Surveyed road widths** (`nvdbRoadWidths`, Norway, 1:1 and 1:2):
  OpenStreetMap rarely carries a road's width, so the mod guesses one from
  the road class. NVDB, the Norwegian national road database (Statens
  vegvesen, open under NLOD, no key), has the measured carriageway width of
  the main roads and the lane layout of nearly every link. For each region
  the widths (object type 583: carriageway, paved or total width) and the
  segmented road network (lanes) are fetched alongside the map data, cached
  under `config/orbisterrarum/nvdb-cache`, and matched geometrically: an
  OSM way is sampled along its length and each sample takes the nearest
  NVDB segment within 7 m running within 35 degrees of the same bearing;
  the median width and the commonest lane count win. An OSM `width` tag
  always wins; NVDB lanes feed the class default where no width exists.
  In central Bergen this gives a surveyed width to about a quarter of the
  roads (all the main ones) and a lane count to four in five. If NVDB is
  unreachable the region generates as before after a 90 s wait, and the
  service is left alone for ten minutes.
- **Imported places** (`externalPlaces`): points of interest from Overture
  Maps (Meta and Microsoft's places, CDLA-Permissive 2.0) merged with
  OSM's, so the shops, bars, clinics and hotels OSM does not know get
  furniture and door signs. Get the file with the Overture client and
  import it:

  ```
  pip install overturemaps
  python -m overturemaps download --bbox=5.05,60.20,5.65,60.55 -f geojson --type=place -o bergen-places.geojson
  /orbis import-places E:\Downloads\bergen-places.geojson
  ```

  (or `java -cp orbisterrarum-1.0.0.jar com.berg.orbis.osm.PlacesImporter
  bergen-places.geojson --out config/orbisterrarum/places` outside the
  game). Each place becomes an OSM-style tagged node from its Overture
  category (restaurant, bar, hotel, museum, gym, pharmacy, supermarket,
  clothing store, hairdresser, bank, school, church, office, ...); places
  without a name, with an unusable category or with confidence below 0.35
  are dropped, and a place whose name an OSM point already carries within
  60 m is not doubled. The Bergen area file (13,500 places, 7,900 kept)
  adds about 1,800 businesses to the centre on top of OSM's 3,000.
- **Landmark advancements** (`landmarkAdvancements`): on the world's first
  start a datapack `datapacks/orbis_landmarks` is written into the world
  and enabled with a reload: an "Orbis Terrarum" tab with one advancement
  per real landmark within 4 km of the origin (60 km on coarse maps), up to
  36 of them, at most 12 of a kind: named peaks ("Climb Ulriken, 643 m
  above the sea", a goal frame), attractions, viewpoints, museums, castles
  and forts, monuments, cathedrals and churches, lighthouses, stadiums,
  funicular stations. Each is a vanilla location trigger over a small area
  around the place (60 m for peaks, 30 m otherwise), and a "Grand Tour"
  challenge wants all of them. Places with a Wikipedia entry rank first.
  `/orbis landmarks` rebuilds the pack (for example after changing the
  origin or the extract). Vanilla clients get all of it from the server.
- **Caves and dungeons** (`vanillaCaves`): vanilla's cave tunnels (the
  worm carver with rooms, branches and lava below vanilla Y -56) are
  re-implemented inside the same underground band as the structures: per
  chunk, vanilla Y 64 = lowest ground within ~100 m minus 100. Tunnels never
  rise above the band top nor within ten blocks of a column's real surface,
  so there are no cave mouths in a street; dig down from anywhere to reach
  them. Vanilla's own underground *features* are then placed in the band
  with vanilla's attempt counts and Y ranges: dungeons (`monster_room`, 10 +
  4 deep attempts per chunk, they need a cave wall to attach to), amethyst
  geodes (1 in 24 chunks), fossils, water and lava springs, glow lichen.
  Mineshafts keep vanilla's cave-spider spawners (1 corridor in 23 has one).
- **Weather** — vanilla cools every biome by 0.05 per 40 blocks above sea
  level + 17, which with sea level at Y -1700 makes any town above ~800 m
  arctic (it snowed in Kathmandu). A mixin on `Biome` disables that height
  cooling in Orbis worlds; biomes are already chosen from the real climate at
  the real elevation, so rain, snow and ice follow the biome alone. Snow
  cover is painted at generation where the climate says so; the snow layers
  that pile up around a player during a snowstorm are vanilla's normal
  random-tick behaviour (simulation distance).

## Country map worlds (a whole country at 1:32, for Aternos)

A 1:1 country is out of reach (Norway at 1 m per block is about 1.2 billion
chunks and several terabytes, and it would need millions of Overpass
requests). A *country map* world is the same generator at 32 m per block:
terrain from the elevation tiles, sea and fjords from bathymetry and the
coastline, lakes, rivers, glaciers, major roads and railways from the map,
no buildings. Norway at 1:32 is roughly 1.3 million chunks, a few gigabytes
on disk, and a few hours to generate. Everest-class relief survives: the
Norwegian peaks stand about 75 blocks above the sea.

1. **Get the country's map data as one file.** Geofabrik publishes every
   country as an `.osm.pbf` extract, e.g.
   `https://download.geofabrik.de/europe/norway-latest.osm.pbf` (1.4 GB).
2. **Import it** into the mod's local store. Either outside the game (best:
   the import of a whole country needs 2-3 GB of heap), from the Minecraft
   instance folder (the one with `mods/` and `config/`):

   ```
   java -Xmx4g -cp mods/orbisterrarum-1.0.0.jar com.berg.orbis.osm.extract.ExtractImporter "E:\Software dev projects\Orbis Terrarum\data\norway-latest.osm.pbf"
   ```

   or in game with `/orbis import E:\Software dev projects\Orbis Terrarum\data\norway-latest.osm.pbf`
   (progress in chat). Three passes over the file; Norway takes about three
   minutes on a 3 GB heap and becomes `config/orbisterrarum/extracts/norway/`
   (11,600 cells of 0.1°, about 1 GB: 1.8 million ways, 490,000 water
   polygons, 89 million points).
   `/orbis extracts` lists what is imported. The "map" profile keeps coast,
   water, glaciers, wetlands, rivers, motorway..secondary roads, railways and
   runways, and is used automatically by any world at 8 m per block or
   coarser whose regions it covers; 1:1 worlds keep using Overpass unless
   a **full** import exists: `--full` keeps every tagged way, multipolygon
   and the tagged nodes (trees, lamps, benches, stops), so 1:1 worlds
   generate from disk too. Norway full is about 8 GB and needs a 12 GB heap
   for the import. `--name <store>` names the store (so a map and a full
   import of the same country coexist; coarse worlds prefer the map one),
   `--bbox <south> <west> <north> <east>` keeps only the cells touching that
   box: a city-sized full store, a few hundred MB, that fits a server.
   Example, Bergen for Aternos:

   ```
   java -Xmx12g -cp mods/orbisterrarum-1.0.0.jar com.berg.orbis.osm.extract.ExtractImporter "E:\Software dev projects\Orbis Terrarum\data\norway-latest.osm.pbf" --full --name bergen-full --bbox 60.20 5.05 60.55 5.65
   ```
3. **Create the world**: Customize → Scale & Height → *World type preset:
   Country map 1:32*. That sets 32 m per block, the transverse Mercator
   projection (the country keeps its shape; the default equirectangular
   projection would make northern Norway 30 % too wide), elevation tile zoom
   11, plain vertical mode, major roads only, and switches off the things
   that have a real-world size a 32 m block cannot show (real building
   footprints, street furniture, schematics, sidewalks, street lamps) and the
   city-zoom downloads (imagery, high-resolution elevation). Vanilla gameplay
   stays on: trees at vanilla size, ores, caves, dungeons, structures,
   animals. Put the origin near the middle of the country (Norway: 64.5,
   12.0) so the projection is centred.
   - **Towns at vanilla scale** (`settlementBuildings`): a real house is a
     quarter of a block at 1:32, so instead every built-up area of the map
     (residential, commercial, retail and industrial land use, which the
     "map" import keeps) is filled with vanilla-sized buildings on an
     8-block lot grid: painted and wooden houses with pitched roofs, 3-8
     storey blocks in town centres, low halls on industrial land, all lit
     inside, on foundations on gentle slopes, never on roads or water.
     Bergen becomes a town of a few hundred houses exactly where Bergen is;
     the fjord, the mountains and the E39 are real.
4. **Sweep it**: `/orbis pregen area Norway`. The outline comes from the
   geocoder (a country is 8 polygons for Norway: mainland, Svalbard, Jan
   Mayen, Bouvet Island, ...; the largest one is used, `/orbis pregen area
   all Norway` takes them all). Chunks are generated row by row from the
   north; open-sea chunks (all elevation samples below -40 m) are skipped
   and generate instantly if anyone sails there later. Progress is saved to
   `config/orbisterrarum/pregen/norway.json` every ten seconds, so after
   `/orbis pregen stop`, a crash or a restart the same command resumes at
   the last completed row.
5. **Upload**: the world folder plus `config/orbisterrarum/` (including
   `extracts/`, so the server can generate any chunk that was skipped) to
   the server, with the mod and Fabric API in `mods/`.

## World map (key N)

Press **N** in game (rebind under Controls, Orbis Terrarum) for a full-screen
map of the real world under the Minecraft one (`client/map/WorldMapScreen`):
Esri street map, satellite photos with place names, or topographic map, lined
up with the blocks through the world's own projection, so a street on the map
is the street in the world. Place names come with the map tiles (cities,
districts, streets as you zoom in); the search box finds any place, address
or landmark and drops a red pin on it.

- **You** are a white arrow pointing where you look; other players in sight
  are cyan arrows with their names; the world spawn is a green square.
- **Drag** to move, **scroll** or **double-click** to zoom, **Me** recentres.
- **Right-click** a spot: *Teleport here* (runs `/tpll`; shown only to operators), *Mark this place*
  (a yellow pin, kept per world in `config/orbisterrarum/map-marks.json`),
  *Copy coordinates*.
- A scale bar shows a round number of blocks and the real distance, and the
  bottom line the latitude, longitude, block and distance and direction from
  you for the point under the cursor.

- **Blocks** lays the Minecraft world seen from above over the real map
  (vanilla map-item colours with height shading, one pixel per block when
  zoomed in), to find buildings, villages and other structures; the button
  cycles off / 35% / 70% / 100%. **Grey** veils the chunks not generated
  yet, and the bottom line says "not generated yet" under the cursor.

The Minecraft layer is made by the server (`map/BlockMapStore`,
`map/BlockMapService`): one byte per block column, 512 x 512 per region, in
`<world>/orbis-map/`. Chunks are drawn in as they load and unload, so a
pre-generation fills it for free and building shows up once the area
unloads. A region generated before this existed is drawn from its region
file the first time someone looks at it (about 250 chunks a second), or all
at once with `/orbis map render` (`/orbis map status` for progress); for
Bergen at 1:2, roughly half an hour and 60 MB, so run it in singleplayer
before uploading the world. Players' games ask only for the regions on
screen at the detail the zoom needs (1, 2, 4, 8 or 16 blocks per pixel), in
batches, nearest first, and keep them in
`config/orbisterrarum/mc-map-cache/` so each area is downloaded once and
only re-sent when it changed. Tested on the server: the live drawing and the
drawing from region files produce identical maps.

The server tells each player's game where the world sits on Earth (origin,
scale, projection; `net/WorldInfoPayload`) when they join, only if their game
has Orbis Terrarum installed, so friends without the mod still join with plain
Minecraft. Tiles use the same disk cache as the area preview.

## Choosing the spawn point

The Location tab has a **Spawn point** group for where players of a new
world appear, when that should not be the location itself (block 0, 0,
where the map is anchored). Switch on *Custom spawn point* and either type
a place into *Spawn place* and press *Look up spawn*, type the latitude and
longitude, or right-click the Area preview map (a yellow cross marks the
spawn, a white one block 0, 0). *Exact spawn* (on by default) sets the game
rule `respawn_radius` to 0 so everyone lands on that block instead of up to
10 blocks around it. On a dedicated server the same settings are
`customSpawn`, `spawnLat`, `spawnLon` and `exactSpawn` in
`config/orbisterrarum/orbisterrarum.json`.

The spawn is applied once, on the world's first start (`worldgen/WorldSpawn`):
the chunk there is generated in full and the spawn goes on its surface, so it
is never inside a building or under the ground. `orbis-spawn.json` in the
world folder records it, so a later `/setworldspawn` is kept. The fitted
world height also samples 10 km around a custom spawn, so a spawn on a
mountain away from the location is not cut off. Existing worlds keep their
spawn; use `/tpll <place>` and `/setworldspawn` there.

## Previewing the area before creating a world

The **Location** tab of the world settings screen (Create New World, World
tab, Customize, and Mod Menu's settings) ends with an **Area preview**
group. Press **Preview** and a map opens inside the game
(`client/AreaPreviewScreen`) showing exactly the chunks
`/orbis pregen area <place>` would generate at the location chosen above and
the scale on the Scale tab. Their pending values are used, so there is no
need to press Done first.

- **What is previewed.** With *Area to generate* left empty, it is the city
  or municipality the chosen location lies in (Nominatim reverse lookup at
  municipality level, so Bergen's default origin gives Bergen municipality).
  A place name (Bergen, Fana, Norway) previews that area, and a number
  (`5` or `5 km`) a radius around the chosen location. The area can be
  changed on the preview screen itself; Enter or **Preview** looks it up
  again, and the text is written back to the settings field.
- **The map.** Esri street map, satellite photos with place names, or
  topographic map (the **Map:** button), downloaded as tiles by background
  threads and drawn as textures. Drag to move, scroll or double-click to
  zoom. While a tile loads, its zoomed-in parent is shown. Tiles are kept on
  disk the way a browser keeps them (`client/TileDiskCache`,
  `config/orbisterrarum/map-tile-cache`, up to 300 MB, least recently used
  trimmed first): used as is for the day Esri's Cache-Control allows, then
  revalidated with the ETag (a "not modified" answer has no body), and shown
  from disk when offline.
- **The chunks.** Drawn over the map in green, computed off the render
  thread per view (`client/PreviewRaster`): every screen pixel is mapped
  through the world's own projection to its block, so the chunks sit where
  the game will put them, including the jagged ring of partly covered chunks
  along the red outline. Chunk lines appear once a chunk is 10 pixels
  across, the amber region-file grid once a region is 40 pixels across, and
  the chunk under the cursor is outlined. The spawn (block 0, 0) is marked.
- **The scale.** The **-** and **+** buttons at the top of the panel lower
  and raise the ratio, metres per block (1 block = 0.25 to 64 m; **+** takes
  1:2 to 1:3, a smaller world), and work the chunks out again for
  the same place, so sizes at different ratios can be compared. The chosen
  scale is written back to Metres per block on the Scale tab.
- **The numbers.** The panel lists the real area, the size in blocks (east-west
  by north-south, the distance straight across), the ground area in
  blocks² (block squares covered, not a distance), how long crossing the
  widest side takes walking and sprinting, what one chunk covers (always 16 x 16
  blocks; at 1 block = 2 m that is 32 x 32 m), chunk count, region files,
  world size (6.3 KB per chunk, measured) and time (at about 22 chunks a
  second, measured), and the command to type in the new world. The text is
  drawn at the largest size that fits, so every line shows at any GUI scale.
  The bottom of the map shows the coordinates, block, chunk, region file and
  whether that chunk will be generated, wrapped to the map's width.

It uses the sweep's own row ranges, so the count is the sweep's: 558,012
chunks for Bergen at 1:2 in both. Whole countries work too: the outline's edges are
indexed by north-south band, so Russia at 1:1 (280,000 chunk rows, 75
billion chunks, about 440 TB) takes seconds instead of half an hour, which
also keeps `/orbis pregen area` from freezing the server at its start. An
area that reaches past Minecraft's world edge (30 million blocks from
spawn) is flagged in red. Areas are measured on the sphere, so large
countries get their true size.

Outlines found on Nominatim are cached for 90 days in
`config/orbisterrarum/outline-cache` (`net/OutlineCache`), shared by the
preview and `/orbis pregen area`: the second preview of Russia skips the
half-minute lookup, and a sweep started after a preview uses the very
outline that was previewed. Delete the folder to fetch outlines afresh. **Open in browser** writes the same
preview as a web page (`config/orbisterrarum/preview/index.html`).

## Pre-generating an area (`/orbis pregen`)

`/orbis pregen <radiusKm>` (around you) or `/orbis pregen at <lat> <lon>
<radiusKm>` generates every chunk within the radius (real kilometres at any
scale: 5 km at 1:2 is 2 500 blocks; the limit is 15 000 blocks), nearest first, after
queueing all the map regions of the area for download. Progress is shown in
chat every 30 s as four short lines (place, percent and ETA; rows generated
and on disk; chunks with the current and the average rate; loaded chunks,
disk queue and heap), with "waiting for ..." in yellow when the sweep is
throttled and a red FAILED count if anything fails; `/orbis pregen status`
adds a line with the map-region and in-flight details, and the full report
goes to the log. `/orbis pregen stop` ends it. `/orbis pregen map` draws
what the world has on disk, one pixel per chunk (green complete, amber a
partly generated ring or an interrupted sweep, dark nothing) with the
area's outline, the rows generated (blue) and on disk (white), the origin
and a 100-chunk grid, into `orbis-pregen/map.png` in the world folder;
`map.html` next to it reloads the picture every 30 s, so a browser tab
shows the sweep advancing. A running sweep writes the map every ten
minutes and when it finishes. It works with no sweep running too; the
outline then comes from the last sweep of the session, or from a place
named on the command (`/orbis pregen map Bergen`), which draws that
place's outline over whatever the world has. Place names are drawn the way
a street map shows them (the city largest, neighbourhoods smallest, fewer
where they would crowd), taken from the local map extract's `place=*`
points and areas, and coloured by status: green where the place's chunk is
complete, amber where complete chunks are close by, red where nothing is
generated yet, grey for places outside the area's outline (orientation
only). `map.html` shows the picture (click to zoom) beside the lists of
places generated, partly and not yet, and chat gets a one-line count with
the most important places still missing.

In singleplayer (and for the host of a LAN world) the chat message has an
**[Open map]** button that opens `map.html` in your browser. It runs
`/orbismap`, a client-side command in your own game that opens that one
file of the world you are playing (Minecraft refuses file links in chat
sent by a server, and the command takes no path from the server, so a
server cannot make anyone open anything else). You can also type
`/orbismap` yourself. On a dedicated server the message shows the file's
path instead; click it to copy it.

`map.html` is an interactive map: the generated chunks are laid over a real
map (street map, satellite with or without labels, or topographic, all from
Esri; OpenStreetMap's volunteer tile servers block pages opened from a
file, so there is no OSM layer) as a
see-through layer with an opacity slider, with the area's outline and a
coloured dot per place (neighbourhoods appear from zoom 13); the lists on
the right are searchable and a click flies to the place. For that the
chunks are also drawn as `overlay.png` in Web Mercator, the projection of
web maps, by mapping every pixel back through the world's own projection
to its chunk, so the overlay lines up with streets and shorelines exactly,
for either projection. The page needs internet for the map library and the
background tiles; `map.png` (the picture with names) works offline. The scan reads only the first
few hundred bytes of each chunk (the status is the first tag of the chunk
data), so a whole municipality takes about 25 s the first time and
seconds after that. Region files are cached by
size and modification time, so only the ones still being written are
re-read. Chunks
are saved as they unload, so afterwards the world folder contains the whole
area and can be uploaded to a server together with `config/orbisterrarum/`.

Sizes: a 2 km radius is ~50 000 chunks, 5 km ~300 000, 15 km (the cap)
~2.8 million. Expect roughly 10–15 KB per chunk on disk. Generation itself
runs at a few hundred chunks a second once map data is cached; the disk is
the limit. Requires operator level (cheats on in singleplayer).

**Every chunk is held until it is done.** A chunk request made from the
sweep's own thread only carries a one-tick ticket: the moment the server
ticks, the ticket expires, the generation is cancelled, the request fails
and a 1 KB stub is all that reaches the disk. (A sweep of Bergen once
reported 1.9 million chunks "generated" at 200 a second and left a 205 MB
world of stubs; the failures were counted as generated.) The sweep now
gives each chunk a non-expiring loading ticket, releases it when the chunk
is complete, and counts failures separately ("N FAILED" in the status line
when there are any). Verified on a headless dedicated server: a 317-chunk
sweep left exactly 317 full chunks on disk. Expect a few to a few dozen
chunks per second of real generation, not hundreds.

**Tiles, not rows, and neighbours kept warm.** A finished chunk drags a
ring of neighbours through the early generation stages. Swept in rows of
the area's full width, that ring was unloaded, written to disk as stubs and
read back from disk for the very next chunk one step over; a thread profile
showed more than half of all worker time in that churn and 3 of 11 cores in
use. The area is now walked in 16-chunk tiles (tile-rows in serpentine
order), the tickets of the last 256 finished chunks are kept until newer
ones replace them, and 128 requests are in flight instead of 32, so the
neighbourhood of the next chunk is normally still resident and the worker
pool stays fed. The map regions of the current and the next tile-row are
queued ahead of the chunks that need them, and the biome lookup's column
cache no longer takes a global lock (worker threads were queueing on it).
Progress on disk is recorded per row as before; a resume re-does at most
one tile-row. The progress file lives in the world folder
(`orbis-pregen/<area>.json`), so a new world never resumes where another
one stopped (it did once, and skipped its first 126 rows; a file left in
`config/orbisterrarum/pregen/` by an older version is adopted by the next
world that runs a sweep). For the sweep's duration the map-region cache is
widened to hold three region rows across the whole area (a tile-row
touches every region across the width, and with the default 64 regions an
area 28 regions wide was rasterising the same regions again a few thousand
times per sweep, at up to a minute each, which had halved the rate after
the first hour), and the loaded-chunk limit is scaled up for worlds with
a fitted height, whose chunks are smaller.

**An outage does not flatten terrain.** A terrain tile that cannot be
downloaded used to fall back to sea level, and a chunk generated on it would
be saved flat for good. Now the chunk waits and retries every 15 s: during a
sweep for as long as the outage lasts (the status line says "waiting for
terrain downloads"), in normal play for up to two minutes, and never while
the server stops. The row recorded as on disk also no longer freezes when a
player stands near spawn: chunks kept loaded by the simulation distance and
the spawn chunks are allowed for.

**The game does not pause during a sweep.** In singleplayer the integrated
server pauses with the Escape menu and whenever the window loses focus;
a paused server keeps generating what was already requested but never
ticks, so nothing unloads or is saved and the sweep freezes (four hours were
lost that way once, just by switching to another window). While a sweep runs
the client reports itself as not paused, like a world opened to LAN, so you
can alt-tab freely. `/orbis pregen stop` pauses normally again.

**The disk sets the pace.** A chunk is written to the region file only when
it leaves memory, and the game writes chunks one at a time; with the
`syncChunkWrites:true` default in `options.txt` every one of those writes
is also synchronous, which on a typical SSD means a few chunks a second.
The sweep therefore watches the writer's queue and pauses generation while
more than 2 000 chunks are waiting for the disk (the status line shows
"queued for the disk" and, when paused, why). It also pauses when the heap
is still three quarters full after a garbage collection, and progress is
only recorded for rows the disk has (a write barrier every 10 s), so a
resume after a crash never skips rows that were generated but never
written. Without this, a sweep of Bergen generated 1.1 million chunks in
100 minutes, wrote 600 of them, and ran a 16 GB heap out of memory with
the rest queued. For a much faster sweep set `syncChunkWrites:false` in
`options.txt` (restart the game); the start message reminds you when it is
on. On a dedicated server, also set `pause-when-empty-seconds=-1` in
`server.properties`: 26.2 pauses an empty server after 60 s, and a paused
server neither ticks the sweep nor unloads and saves its chunks.

## Exploring a whole country

Pre-generation is only needed for a *server* that must not download. For
your own play, the world already streams: you can walk, fly or `/orbis tpll`
across an entire country at 1:1 and only the places you actually visit are
ever generated or stored. Nothing has to be prepared in advance.

If you want a country to feel *traversable* (Norway is 1 700 km long; at
1 block = 1 m that is 1.7 million blocks, an elytra flight of many hours),
set `metersPerBlock` in the Customize screen to 2, 4 or 8. Everything,
including heights, scales together, so the map stays correct and a 4×
world is 16× fewer chunks for the same area. The cost is detail: at
1 block = 4 m a house is two blocks wide, roads are one block, and
buildings become coloured blocks rather than architecture. 1:1 to 1:2 is
the range where the city detail survives; 1:4 and beyond is a landscape
scale. The two are different worlds; keep a 1:1 world for cities and a
scaled one for roaming, and share caches between them (they are keyed by
real coordinates, so nothing is downloaded twice).

## Lidar and AI inputs (trees from the canopy map, lidar roofs, ground classes)

Three optional inputs refine what the OSM data alone cannot say. All three
are on by default (`treesFromCanopy`, `roofsFromSurfaceModel`,
`groundClassesFromModel`) and all three fall back silently to the older
rules when their data folder is empty, so nothing changes until the data is
there.

### Trees where they really stand (`treesFromCanopy`)
Instead of scattering trees at a per-cover density, the rasteriser places a
tree at every local maximum of a canopy height model that is at least 2.5 m
tall (crown radius from the height, `osm/FeatureRasterizer.rasterizeCanopyTrees`).
When a region has canopy data for at least 30 % of its cells the random
scatter is switched off for that region. Two sources:
- In Norway, when the Kartverket lidar services answer, canopy = surface
  model (DOM) − terrain (DTM) from the configured `surfaceSources`.
- Anywhere else (and in Norway while Geonorge is down, as it has been all
  week), Meta / WRI's 1 m global canopy height map, converted once with
  `tools/canopy_tiles.py` into `config/orbisterrarum/canopy-cache/16_<x>_<y>.png`
  (terrarium-encoded PNGs, same encoding as the terrain tiles;
  `dem/CanopyProvider`):

```bash
pip install rasterio numpy pillow
python tools/canopy_tiles.py --bbox 60.25 5.10 60.55 5.60 --out <instance>/config/orbisterrarum/canopy-cache
```

Keep the box tight: the source is one 434 MB GeoTIFF per zoom-9 web-mercator
tile (Bergen is tile 120020133, 60.24–60.61 N, 4.92–5.62 E). The download
resumes if interrupted and the source file is kept in `canopy-src/`. Tiles
with no canopy at all (open sea, bare rock) are not written. Data:
Tolan et al. 2024, CC BY 4.0.

### Roof shapes from the lidar surface model (`roofsFromSurfaceModel`)
For buildings without a `roof:shape` tag, when a lidar surface model covers
the footprint, a 9 × 7 grid of DOM samples inside the outline gives the eave
height (20th percentile) and ridge height (92nd percentile); a ridge much
higher than the eaves along the long axis becomes a gabled roof, a peak in
the middle a hipped one, otherwise flat, and the building height itself is
taken from the ridge (`FeatureRasterizer.roofFromSurfaceModel`). This needs
Kartverket's DOM WCS to be reachable; until it is, nothing changes.

### Ground classes from a segmentation model (`groundClassesFromModel`) — experimental
`imagery/ImageryProvider.classAt` reads `config/orbisterrarum/ground-classes/<z>_<x>_<y>.png`
(grey PNG per imagery tile, pixel value = ground class code) and uses it
instead of the colour rules wherever a tile exists. `tools/classify_ground.py`
is meant to produce those tiles from the cached aerial imagery with IGN's
FLAIR-INC RGB U-Net (weights in `config/orbisterrarum/models/`). The
checkpoint loads (278 tensors), but on Esri imagery its predictions are not
usable yet: with the model card's normalisation, forests come out as water
and Paris blocks as farmland, and with raw 0–255 input everything is
"building". Until the preprocessing that model really expects is found, do
not install its output; the mod behaves as before when the folder is empty.
The working, worldwide answer to the same problem is the ESA WorldCover gap
filling described under "Land cover and vegetation".
Class codes: 0 unknown, 1 grass, 2 canopy, 3 light paving, 4 dark paving,
5 bare soil, 6 sand, 7 rock, 8 snow, 9 water.

## Clouds

The cloud layer sits about 800 m above the ground at the world's origin
(Kathmandu: ~2100 m, Bergen: ~850 m), computed per world and pushed into the
dimension data the server sends at login, so vanilla clients see it too.

## Playing with friends (dedicated servers, Aternos)

The mod is a server-side generator: the server does all the downloading and
building. **Vanilla clients can join** and see everything correctly, sky fix
included; only the world settings screen and the world map need the mod on
the client. An uploaded Orbis world stays an Orbis world. For a **new** world
on a dedicated server, set `level-type=orbisterrarum\:earth` in
`server.properties` (the world settings come from
`config/orbisterrarum/orbisterrarum.json`); without it the server makes a
vanilla world and the mod stays out of it. What a server needs:

- **Outbound internet** to Overpass (OSM), `tiles.mapterhorn.com` (terrain),
  `tiles.openwaters.io` (sea floor), the ESA WorldCover bucket on AWS (land
  cover), `data.source.coop` (building heights) and the imagery hosts; see
  `docs/data-sources.html` for the full list. Hosts that block outbound HTTP
  will generate bare terrain.
- **Memory**: the region cache is the big consumer (`regionCacheSize` ×
  ~6 MB; the default 64 is ~400 MB). On a 2–4 GB host set `regionCacheSize`
  to 16–24, `regionPrefetchRadius` to 1, `imageryTileCacheSize` to 128 and
  `demTileCacheSize` to 128.
- **Patience on first visit**: every new 512 m region is one Overpass
  request (5–40 s, sometimes minutes when the public mirrors are busy) plus
  rasterisation. Public Overpass mirrors rate-limit per IP, and shared
  hosting IPs are often already throttled.

Best approach for a shared world: **generate at home, upload the result.**
Explore the area you want with friends in singleplayer first (everything you
walk through is saved in the world), then upload the world folder *and*
`config/orbisterrarum/` (the `osm-cache`, `dem-cache` and `imagery-cache`
folders) to the server. Chunks you visited need no network at all; regions
whose data is cached rasterise in a second without any download; only truly
new ground goes to Overpass. Aternos-class free hosting can run that with
the memory settings above; it is CPU-starved and sleeps when empty, so a
world that is mostly pre-generated is the difference between playable and
frustrating. A cheap 6–8 GB host (or one PC in the group hosting with a
tunnel such as e4mc or playit) handles fresh exploration comfortably.

## Known limitations
- Equirectangular projection: exact at city scale, drifts over hundreds of
  km (a conformal projection would fix continent-scale maps).
- Roads follow the DEM column by column; on steep noisy slopes they can be
  bumpy (a longitudinal smoothing pass would help).
- Embankment ramps have vertical sides (no side slopes yet).
- Relief mode is not 1:1 above 1500 m of regional elevation, and the very
  top of the Himalaya is squeezed; a 4064-block engine cannot hold Earth.
- One building per column: a `building:part` that floats above another
  building (skybridge) is dropped in favour of the lower building.
- Roof slopes are stepped full blocks, not stairs.
- Interiors are empty shells (floors only).
- Climate has no precipitation data; use `climateOverride` where the
  latitude default is wrong.
