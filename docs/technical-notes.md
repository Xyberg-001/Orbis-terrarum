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

- **World** — the world generator map (where the world is, its spawn and
  what it generates, see below), a preset list (Bergen, Oslo, London, Paris,
  Tokyo, New York, Sydney, Mount Everest, …; a list on its own screen,
  `PickScreen`, since the settings library's dropdown drew its list over the
  options below without a background) that gives the map a place to start
  from, metres per block and the world type (the same kind of list; it fills
  in every setting for a City 1:1 or Country map 1:32 world). Until 3 Oct 2026
  the tab also had a place search with Look up, latitude/longitude fields, a
  Spawn point group with its own search and coordinates, and the
  pre-generation switches: all of it is on the map now. Exact spawn and Skip
  open sea (/orbis pregen) moved to the Advanced tab.
- **Features** — groups Cities, Life, Nature, Minecraft gameplay and
  Fine-tuning (folded: road grading and ramps, major roads only, towns at
  vanilla scale).
- **Look & Climate** — Real sky first (daylight, weather, seasons, snow
  depth, villager clock hours); buildings, roads and climate & trees folded.
- **Data Sources** — every group folded.
- **Advanced** — world height, sea level Y, projection, terrain detail
  (DEM zoom), the mountain mode (see "Vertical mapping"), the 1:1 knee, the
  relief smoothing radius, sea from elevation.

The tabs run from most to least impact on the world; options and their
keys are unchanged, only the screens moved them (the scale and terrain
options are built once per screen, `YaclScreens.ScaleOptions`, since an
option can be placed only once).

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
  has. The pixels just past the parent's edge are read from the
  neighbouring parent tile (`DemTileProvider.parentPixel`); until 3 Oct 2026
  they were clamped to the edge, which put the whole step between two
  parent pixels into one child pixel along every zoom-12 tile edge: an
  11-block wall straight across a slope at Everest (1:2), wherever only
  Copernicus exists. Tile downloads run on a pool of eight threads (vanilla's spawn
  selection samples biomes over a 20 km circle, which at zoom 15 would
  otherwise be hundreds of simultaneous requests on one connection), and
  biome decisions use a zoom-11 sampler over the same tiles, since a
  climate call needs the rough height only. Existing worlds keep
  their stored zoom and switch source on upgrade, so chunks generated
  afterwards can show a seam against old ones; new worlds are consistent.
- **Bare-earth terrain outside the surveys** (`bareEarthTerrain`, per world;
  `dem/BareEarth`, `dem/BareEarthBlend`, 4 Oct 2026). Copernicus GLO-30 is a
  surface model: it measures the tops of forests and roofs, so wherever
  Mapterhorn has no survey the ground stood several metres too high under
  every forest and town. Measured against lidar at 11 sites (1,760 points,
  scratchpad `dembench.py`): Copernicus averaged 11.9 m too high (RMSE 26.0),
  GEDTM30 v1.2 5.7 m (23.5); Oregon's Coast Range forest 18.7 → 1.8 m too
  high (RMSE 22.5 → 10.1), Sihlwald 24.5 → 10.1, Danish farmland RMSE 5.3 →
  2.2, Bergen's forest 7.8 → 5.1. Neither model can draw skyscraper districts
  (Chicago Loop about 29 m too high in both) or 300 m cliffs (Lauterbrunnen,
  a 30 m grid); in Oslo's Nordmarka GEDTM30 cut 6.5 m too deep where
  Copernicus was 4.4 m high, its one worse site. FathomDEM measures better
  in the literature (about 25 % below GEDTM30) but is access-restricted on
  Zenodo and CC BY-NC-SA; FABDEM is CC BY-NC-SA too. GEDTM30 is CC BY 4.0
  (its publishers still call v1.2 a test release).
  The model is one 432 GB cloud-optimised BigTIFF on s3.opengeohub.org
  (EPSG:4326, 1 arc-second, EGM2008 like Copernicus, 2048 x 2048 blocks,
  deflate with the integer predictor, Float32 stored to 0.1 m). GDAL wrote
  the image directory at the end of the file; its layout is read once into
  `dem-cache/gedtm30/layout.properties`, the block tables a row at a time
  (`table/`), and each block a world touches (about 4 MB, 60 km square) in 128 kB range requests, 16 at a
  time, appended in order into `blocks/` (continued after a failure). The host limits each connection, not the
  total: one piece at a time came at 26-63 KB/s from India, 16 at a time at 300-800 KB/s (5 Oct 2026). A new
  place's bare earth went from about 4 minutes (K2) to 65 s (Kilimanjaro), cold.
  Decoded blocks are kept as 16-bit steps over the block's lowest height
  (8 MB each, six in memory) on their own two threads, never the terrain
  tile threads that wait for them.
  `BareEarthBlend` is a `DemTileProvider.TileCorrection` on the world's
  terrain zoom (13 or finer; coarser scales are left alone, where a few
  metres are less than a block). Mapterhorn serves zoom 13 and finer only
  over national surveys (Copernicus alone stops at 12), so the presence of
  a zoom-13 tile is the survey test, answered once per tile and kept on
  disk as the tile or its `.missing` marker. Iceland, the Faroes and Latvia
  have 10-20 m surveys Mapterhorn only serves to zoom 12; their boxes count
  as surveyed (Latvia's takes in edges of its neighbours, which keep
  Copernicus). Survey tiles pass unchanged. Elsewhere each pixel becomes
  `m + w (g - m)`, Mapterhorn's height m moved toward GEDTM30's g, with w
  rising from 0 at the nearest survey tile to 1 at 400 m (smoothstep), so
  the two meet without a step. The sea and the shore at sea level (|m| ≤
  0.5 m) are left as they are, and land is never lowered into the sea. The
  corrected tile is what the memory cache holds; the tile on disk stays as
  downloaded, and the survey test does not cache the zoom-13 tile in memory
  (at a zoom-13 world it would be served uncorrected).
  Only new worlds: `WorldSettings.bareEarthTerrain` defaults to false, so a
  world saved before 1.1.1 (no entry) keeps Copernicus and its new chunks
  meet its old ones; `fromConfig` gives a new world the config's value
  (true). A dedicated server whose world has no stored settings takes the
  terrain from its config, so config version 18 switches it off there on
  upgrade.
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
  Near the shore the land tiles' 0 m blends into the bathymetry over tens
  of metres, so there the depth is unknown. `FeatureRasterizer.measureSeaShore`
  stores each sea column's distance to the shore (`RegionRaster.seaShore`,
  exact EDT, within the 32-block margin so regions meet without seams) and
  `ColumnPainter` shelves the bed down a block per three blocks out, to
  about 12 m (`SHELF_M`, at most the water's own depth), following the
  bathymetry wherever it is deeper. Before this, those columns took a fixed
  6-block depth: a trench along every shore and a step up where the first
  bathymetry of 1-2 m began (Store Lungegardsvann at 1:2).
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
  guess. Shipped (checked 1 Oct 2026, all answering for a lat/lon box
  themselves, 2 to 6 s a 256 px tile): Kartverket 1 m DOM (Norway, only with
  `lidarSurfaceModel`: VPN outside Norway), AHN 0.5 m (Netherlands, WCS 2.0;
  `geotiff:predictor=None`, since Java's TIFF reader cannot undo the
  floating-point predictor), IGN MNS (France, WMS 1.3.0 GeoTIFF), NRW nDOM and
  Estonia's nDSM (WCS 2.0, heights above ground), ČÚZK DMP 1G (Czechia),
  DOGAMI (Oregon, feet), KyFromAbove (Kentucky, feet), DC nDSM (heights above
  ground) and New Brunswick (ArcGIS exportImage). `valueScale` turns feet into
  metres; `aboveGround` services get the terrain (Mapterhorn) added, so the
  height over the footprint is the service's own number; `needsSwitch` marks
  Kartverket's. Each is asked only inside its country's outline (a tile
  outside it is answered "nothing here" without a request). Left out: Spain's
  MDSn (certificate chain fails in Java), England's EA DSM (500 on every
  lat/lon request), Flanders' DHMV (403 to non-browser clients), Canada's HRDEM
  (no WCS 2.0 coverage of that name; served as files instead, below). Config
  version 12 adds the new ones to older files.
- **Surface models as files in a national grid** (`dem/CogDemSource`,
  `dem/NationalGrids`): swissSURFACE3D (Switzerland, 0.5 m, 1 km files in
  LV95) and NRCan's HRDEM 1 m mosaics (Canada, files 500 000 pixels across in
  Canada Atlas Lambert, BigTIFF). A place is projected with closed formulas
  (swisstopo's approximate WGS84-LV95 formulas, about 1 m; Lambert conformal
  conic on GRS80 for EPSG:3979), the STAC item search lists the files for its
  0.02 degree cell (kept in `dsm-cache/<source>/stac/`), and only the 512 x 512
  blocks under the place are read with range requests (kept raw in
  `dsm-cache/<source>/files/`). The header comes with the first 64 kB; big
  files' block tables are read one row of blocks at a time. LZW (written here:
  48 ms a block), deflate, predictors 2 and 3, float32 and 16/32-bit integers.
  Checked against landmarks: Zürich's Prime Tower 126.8 m (real 126 m), the CN
  Tower's mast found inside a 7 m search grid. swisstopo answered this network
  at 40 to 170 kB/s a connection (6 in parallel about 750 kB/s); a built-up
  square kilometre is about 7 MB. Left out: New Zealand's LINZ files (LERC
  compression).
- **Building databases** (`buildingSources`, `osm/BuildingDatabases`):
  national and city registers that know each building's height or floors,
  used after tags and the surface model and before the building atlas, at
  any scale: BD TOPO (France, `hauteur` to the gutter, so the roof goes on
  top; `nombre_d_etages`), 3DBAG (Netherlands, `b3_h_70p` minus
  `b3_h_maaiveld`, `b3_dak_type` horizontal gives a flat roof), Slovenia's
  building cadastre (`VISINA_H2` minus `VISINA_H3`, `STEVILO_ETAZ`), Vienna's
  building-part model (`O_KOTE`), New York's footprints (`height_roof`, feet).
  WFS 2.0 GeoJSON in EPSG:4326 or Socrata, with `PROPERTYNAME`/`$select`
  asking for those fields only (Slovenia sends 6 kB a building otherwise),
  per 0.01 degree tile, pages of 5 000; kept as one line a building
  (height, floors, flat, outline) in `building-db-cache/<source>/`. The region's
  tiles are fetched together before its buildings. A building is matched by
  its OSM centre: the register building it stands in, else the nearest within
  8 m. Checked: Tour Montparnasse 207 m (real 210), the Empire State Building's
  roof 378 m (381). Vienna's model has a record per building part, so the
  part under the centre counts (the Ringturm's podium, 43 m, not its tower).
  Left out: San Francisco's lidar heights (no outlines, parcel numbers only),
  Spain's Catastro (needs a parcel reference per query; licence unconfirmed).
  Config version 13 adds them to older files.
- **Road databases outside Norway** (`roadSources`, `osm/RoadDatabases`):
  lines with a width or lanes, turned into NVDB segments so the NVDB matcher
  (a line running the same way within 7 m) pairs them with OSM roads: BD
  TOPO `troncon_de_route` (France, carriageway width, lanes), Digiroad
  (Finland, `dr_leveys` width in cm; `dr_kaistojen_lukumaara` lanes, one
  record a direction, summed per `link_id`), FHWA HPMS (USA, federal-aid
  roads: `through_lanes`, `lane_width` in feet, one ArcGIS service a state:
  `{state}` from every state outline touching the tile), British Columbia's
  Digital Road Atlas (lanes). Per 0.01 degree tile, pages of 2 000, kept one
  line a road in `road-db-cache/<source>/`. HPMS counts both directions:
  matched to a one-way OSM carriageway (one side of a divided road) it gives
  half (`Segment.bothWays`, `FeatureRasterizer.halfForOneWay`), except for
  records that are one-way roadways themselves (`facility_type` 1:
  Manhattan's avenues). Same switch and scale limit as NVDB
  (`nvdbRoadWidths`, 1:2 or finer). Checked: Paris 1 662 lines with widths
  and lanes in a 900 m square, Helsinki 665, Manhattan 264, Washington DC 702,
  Vancouver 792. Left out: Spain's (GML only, lanes only), Brazil's (1:250 000
  lines, too far off OSM's), Estonia's (no width layer found), Sweden's and New
  Zealand's (keys). Config version 14 adds them to older files.
- **Root certificates Java lacks** (`net/OrbisHttp`, resources
  `orbis-roots/`): HARICA TLS RSA and ECC Root CA 2021 (GÉANT's certificates
  for Europe's universities and public bodies chain to them since 2025:
  Finland's SYKE, Spain's IDEE) and Telekom Security TLS RSA Root 2023 / ECC
  Root 2020 (Berlin's geoportal). Taken from Mozilla's bundle; SHA-256 D95D0E8E…7B1D,
  3F99CC47…D401, EFC65CAD…8646, 578AF4DE…A8E1. OrbisHttp's client trusts a server
  when Java's own list or these roots vouch for it (`EitherTrustManager`, both
  X509ExtendedTrustManagers, so host names are checked as before); an expired
  or wrong-host certificate is still refused (checked against badssl.com).
  Added with the user's go-ahead on 1 Oct 2026; config version 17 brings back
  Berlin's photos and Spain's surface model and switches Finland's lake surveys
  on. Drop a root once Java ships it.
- **Lake depth surveys outside Norway** (`lakeSurveySources`,
  `water/LakeSurveys`): depth contours and sounded points (Minnesota DNR, feet;
  Ontario MNRF, negative metres; Finland's SYKE contours and deepest points),
  per 0.05 degree tile, kept in `lake-depth-cache/<source>/`. Contours become
  NVE-style soundings every 5 m in order, so `WaterBeds.Surveyed` joins them
  into lines and solves the same smooth bed; an OSM lake with 8 or more of them
  inside (and one deeper than 0.5 m) gets it, after NVE and before GLOBathy.
  The deepest contour is the greatest depth unless a sounded point says more.
  A lake over 400 tiles keeps its bowl. Checked: Bde Maka Ska's deepest
  contour 27.4 m (recorded 26.5 m). Left out: Saskatchewan's layer (an index of paper
  maps), swissBATHY3D and the IJsselmeer (bed heights, not depths: need the
  lake level). Config version 15 adds them to older files.
- **Bulk downloads: German LoD2** (`tools/lod2_heights.py`, source
  `de-lod2` in `buildingSources` with no address: imported data, read from
  disk only). The states' LoD2 CityGML in the UTM 32 grid: North
  Rhine-Westphalia's 1 km tiles (`LoD2_32_<E>_<N>_1_NW.gml`, dl-de/zero-2.0)
  and Bavaria's 2 km tiles (`<E>_<N>.gml`, CC BY 4.0). 50 to 160 MB a city
  tile, so the tool streams each (`iterparse`, elements cleared) and keeps
  only each building part's ground-surface ring (UTM to WGS84 with the
  Krueger series), `measuredHeight` and whether `roofType` is 1000 (flat),
  written as the building databases' 0.01 degree tiles and merged with
  earlier runs. Checked on central Cologne: 3 009 parts in a tile, 769 in the
  box, 65 s. LoD2 stores a building's parts separately, so the part under an
  OSM building's centre counts (the cathedral's nave, 67 m, not its towers).
  Other bulk datasets in the atlas stay out for now: the other German states
  (each its own download portal), Dutch WKD road widths (national roads only),
  Poland's BDOT10k and Mexico's and Korea's road networks (unreachable from
  here), Hong Kong's 0.5 m lidar (ZIPs in the HK1980 grid), US 3DEP point
  clouds (need a point-to-surface step), Japan's PLATEAU (city CityGML of
  several GB), Estonia's and Buenos Aires' 3D buildings.
- **Trees from the surface model**: where no canopy map covers a region, its
  trees are read off the surface model at every open column. The region's
  tiles are now fetched together first (`FeatureRasterizer.setCanopyPrefetch`);
  before, each column waited for its tile in turn, up to a minute a region in
  the Netherlands. `/orbis here` shows whether a building's height came from tags,
  the surface model, or a default.

### Vertical mapping (`dem/VerticalMapping`)
Minecraft allows at most 4064 blocks between the floor and the ceiling of a
dimension; Everest alone is 8 849 m. The world therefore uses the full
Y -2032..2031 range with sea level at Y -1700 (3 731 blocks for mountains,
332 for the sea floor) and converts metres to Y in one of two modes
(`verticalMode`). Since 1.1.1 this is not a setting: a new world gets **relative**,
or **compress** where its scale leaves room for Everest under the ceiling's
squeeze (about 1:3.2 and coarser, so the 1:32 country map; at such scales relative
would lower Tibet for nothing) or where the high-altitude window applies.
A world keeps the mode it was made with.
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
- **compress** — no regional shift, only the smooth squeeze near the ceiling
  (and floor): 1:1 throughout wherever the world has room.
- **clamp** (worlds made before 1.1.1 only, mostly 1:32 country maps) — plain 1:1
  cut off at Y 2031. Removed as a choice with compress: at any scale where it is
  safe, compress is the same, and at 1:1 it cut every mountain above 3.7 km flat.

**High-altitude window** (1.1.1, `WorldHeight.window`, new singleplayer worlds
with an automatic height). The engine's 4 064 blocks cannot grow (a block
position keeps 12 bits for Y, on every client), but anchored to sea level a
mountain world wastes much of them: around Everest the ground within 16 km
lies 4 125-8 702 m, so Y -2032..-751 held nothing, and the relative mode
still had to squeeze the mountain (Base Camp to summit 2 039 blocks for
3 400 m; South Col to summit 139 blocks for 900 m). When a world is created
and the terrain sampled for its height fit (30 km round the origin, the
custom spawn, the selected area) has no ground at or below 50 m (no sea or
coast), the world's range is laid over that ground instead: sea level moves
under the floor so that the lowest ground but the lowest 5 % lies 416 blocks
above it, mode **compress** (1:1, no regional shift), a 64-block squeeze
under the ceiling. If the ground spans more than the world, its highest point
goes 80 blocks under the ceiling instead and the deepest valleys ease
towards the floor. The bottom squeeze of such a world (sea level under the
floor) eases over 400 blocks, not 150, so the valleys keep their shape
(with 64 they lay flat on the floor). Heights above sea level stay real.
Checked 7 Oct 2026 (Everest 1:1): sea level Y -6754; South Col 7 819 m and
Base Camp 5 314 m both 1:1 (2 505 blocks apart, was 1 900), the summit at
Y 1960 (0.6 blocks per metre over its last 10 m, was 0.1), Lobuche 0.62,
Dingboche (4 323 m, 14 km) eased to 0.14 instead of 1:1, Namche and Lukla
on the floor. Denver: ground 1 561-2 563 m, all 1:1, and a world of 1 936
blocks instead of the usual fit. Bergen (sea): no window. The window is
stored with the world (its sea level, mode and squeeze); dedicated servers
creating a world from `server.properties` keep the sea-level anchor. A
window world's real sea level lies under the floor, so its sea is a token
one: land far outside the sampled area eases down to a plain 8 blocks over the
floor (Y -2024 at Everest from about 2 400 m down to the coast), and the sea
beyond it is shallow water level with that plain (`OrbisConfig.waterLevelY`:
sea level, or floor + 8 under a window), its bed a block down at the shore and
on the floor from 60 m deep (`VerticalMapping`: elevations at or below 0 m).
Everything that decides "at sea level" (sea masks, sea-level water features,
bridge clearances, structure bases, the far view) goes by the water level; the
metres-to-Y mapping and the vanilla sea level (weather, snow) by the real one.
`sanitize` used to raise any sea level under Y -1968 back to it, which undid
every window when its world loaded; a sea level under the floor is now kept,
and a window must put it there (between the floor and -1968 it is not one).
`highAltitudeWindow` (Advanced →
Terrain, "Mountain worlds: true heights", default on, also per world) turns
it off for a mountain world meant to reach the sea.

**Uniform heights** (1.1.1, `uniformHeights`, Advanced → Terrain, default off,
per world). Every height divided by one factor, `heightSquash` (real metres
per block of height over the scale's own, `OrbisConfig.verticalMetersPerBlock`),
in mode compress with a 64-block squeeze under the ceiling: no region is
lowered, so every mountain keeps its true proportions to every other. The
factor is worked out when the world is created (`WorldHeight.uniform`, from the
same samples as the window: 30 km round the origin, the custom spawn, the
selected area): just enough for the area's highest ground to stay under the
squeeze, never below 1. Probe, full height: Bergen 1.00 (true 1:1, world fitted
to 1 952 blocks), Chamonix 1.29 (Mont Blanc Y 1999), Everest 2.38 (Fløyen's
320 m would be 134 blocks; the summit Y 1990). Ground taller than the area's
is squeezed at the ceiling. Buildings, trees, snow depth and river channels
keep their real size; snow, treelines and climate go by real metres (the Y
back through the factor). Uniform heights take the window's place. A world
made from `server.properties` gets factor 1 (no creation screen to sample the
area from).

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
is created, the terrain within 30 km of the origin, within 10 km of a custom
spawn and inside the area selected on the world generator map (the middle of
its chunks on a grid of at most 6 000 samples, `WorldHeight.peak`) is
sampled from the coarse elevation tiles, and the world's height is set to
the highest point plus 512 blocks (flight, the cloud layer, tall buildings), rounded to 16,
never below 1 024 nor above 4 064. Bergen comes out around 1 800 to 2 200
blocks tall, which makes every chunk roughly twice as cheap to generate,
light, save and keep in memory, and halves the size of a pregenerated world
and the RAM a server needs per loaded chunk. The floor (Y -2032) and sea
level (Y -1700) never move: the sea floor and the underground need them,
and the vertical mapping is unchanged inside the range: a fitted world
stores its own squeeze range (`softCeilingBlocks` in its settings,
`WorldHeight.FITTED_SOFT_CEILING` = 480), so the squeeze starts 16 blocks
above the highest terrain the fit measured. Until 1.1 fitted worlds used the
installation's 900, which started the squeeze some 400 blocks below the
highest terrain: every hill above that came out lower than real (in a 1:2
Bergen world above about 160 m, Gullfjellet by about 50 blocks). Worlds
made before keep 900 (no stored value), so their new chunks still match the
old ones. A dedicated server creating a fitted world itself takes the
config's value; set `softCeilingBlocks` to 480 there first. Terrain above
the ceiling (an area added later, far from the fitted one) gets the same
soft squeeze the Himalaya gets in a full world, only lower. Because a
world's height is fixed at creation, a selection whose highest point would
be lowered by more than 8 blocks is reported: on the world map's selection
panel before Generate (singleplayer), and in chat when its pre-generation
starts (`PregenTask.warnIfTooLow`). A Kathmandu world (2 272 blocks) would
lower Solukhumbu's peaks by about 565; created with Solukhumbu selected it
gets 3 280 blocks and lowers nothing.

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

### Data sources by country (`config/DataSources`, `client/DataSourcesScreen`)
`DataSources.list` names every source with its country, box, outline key and
cache folders: worldwide ones (Mapterhorn, Seascape, Overpass, WorldCover,
Macrostrat, Esri, map backgrounds, the old AWS tiles), national ones (each
photo service but Esri, NGU, NVE, NVDB, the lidar services) and what the
tools import. Which sources a world uses is automatic: a national service is
asked wherever it covers, the worldwide data everywhere else. (An ask-first
version with per-service yes/no lived for a day; players should not have to
answer questions to get the best data.)

Coverage: a service's box, then its country's or state's outline
(`orbis-coverage.json`: Natural Earth 1:50m countries and 1:10m states, public
domain, simplified to about 400 m, 228 kB), with 2 km to spare: France's box
holds Bern and Geneva, Norway's half of Sweden. `DataSources.covers(id, box)`
is the test at each fetch (photo tile, rock region, NVE/NVDB region, lidar
prefetch), so no service is asked about ground outside its country.

What a world needs of the player: `DataSources.requirements` lists the
services with special needs that the settings would use around the world
(30 km round the origin, 10 km round a custom spawn, the drawn selection):
Kartverket's lidar building heights (a VPN to Norway outside Norway; slow),
only when switched on (Advanced tab → Lidar building heights) and the scale
is 1:4 or finer. `CreateWorldScreenMixin` asks
`SpawnGate.showNeeds` first: a notice with each need and whether the service
answers from this network (`reachable`: one real request, checked while the
notice is up); Generate creates the world, Back returns. A pre-generation
puts the same lines in chat (`PregenTask.tellNeeds`). Nothing is shown when
nothing applies.

The storage screen (Mod Menu → Storage → Downloads by country) groups by
country (This world / All countries, search; "this world" is the last world's
location), shows each source's size (counted on a
background thread folder by folder, the photo folder last in one listing;
250 000 files take about a minute cold, the counts are kept between visits),
**Re-import data** (delete a downloaded source's copies: fetched again, fresh,
when new chunks need them) and **Delete** (free the space; imported data and
the old AWS tiles only have this). Deleting keeps the folders and skips files
in use. Chunks already generated keep their data.

Kartverket answers a tile with no data at all (open sea) with an 848-byte
TIFF that declares 256 x 256 but holds no pixels; decoding it threw, which
counted as the service failing and switched it off for three minutes (the
buildings around lost their measured heights). Such a tile is now read as
"nothing measured here".

### Settings screens: world vs mod
Create New World → Customize holds everything that is generated (World,
Features, Look & Climate, Data Sources, Advanced) and remembers the last
choices as the defaults (`saveWorldDefaults`); "Reset all to defaults" puts a
fresh `OrbisConfig`'s world settings back. The photo-use options (imagery zoom,
roof colours, ground classes, tree cover, surface-model heights) live there too
but are installation-wide: Customize's save copies them into the installation
config. Mod Menu holds only the mod's own settings: Overview (version, data
folder, imported extracts and data, `pregen-history.txt` in the config folder:
the last 30 pre-generations with world, scale, area, chunks, time and rate,
written when one finishes, is stopped or is put aside), Storage (Downloads by
country, data folder), Performance, Network. Servers have no screen:
`/orbis settings [name [value]]` (op) lists and changes the performance and
network fields by reflection, clamped by `sanitizeValues` and saved.

Real seasons, daylight and villager clock hours are decided by each world's own
settings only. They used to also need the installation's switch on, so Mod Menu
could switch them off everywhere; with world settings set only in Customize,
which writes the same fields as the next world's defaults, creating one world
without seasons would have switched seasons off in every world.

The lidar terrain services (Kartverket DTM, USGS 3DEP) were dropped on
1 Oct 2026: Mapterhorn carries the same terrain. Config version 10 removes
them from `elevationSources` and switches `useHighResElevation` off; their old
tiles (`dem-hires-cache`) show as "Old lidar terrain" in Downloads by country.
Kartverket's surface model (building heights) stays.

### Aerial imagery (real colours everywhere)
- `imagerySources`: orthophoto / satellite tile layers with coverage boxes.
  Shipped: 21 national and state orthophoto services (Switzerland, the
  Netherlands, Flanders, Wallonia, Luxembourg, France, Spain with the Canaries,
  Bavaria, North Rhine-Westphalia, Austria, Czechia, Slovenia, Estonia, Japan,
  Taiwan, Hong Kong, New South Wales, Victoria, Ontario, Massachusetts; open
  licences, checked 1 Oct 2026), USDA NAIP via USGS (USA) and Esri World
  Imagery (global). Templates take `{z}/{x}/{y}`, `{-y}`, `{quadkey}`, or
  `{bbox}` (the tile's EPSG:3857 corners) for WMS GetMap and ArcGIS
  export / exportImage services. The smallest coverage box is asked first
  (neighbours' boxes overlap). Outside its border a service draws white,
  black or transparent: when such fill covers 3 % of a tile or more, those
  pixels come from the next source (a border tile is half one country's photo,
  half the neighbour's); smaller specks are left alone (transparent ones are
  skipped when sampling). Left out: Berlin's TrueDOP (its certificate chain
  fails in Java), New York State's orthos (7 s a tile; NAIP covers the state),
  key-only services (Denmark, Finland, Sweden) and non-commercial ones
  (Tasmania, EOX after 2017). Configs older than version 8 get the national
  services put ahead of their own list once. The national servers answer in
  0.9 to 2.3 s a tile against Esri's 0.6 s, so the fetch pool has 8 threads.
  Norge i
  bilder's open tile cache (opencache.statkart.no) closed in 2025-2026; its
  successor (tilecache.norgeibilder.no) needs a token given only to Norwegian
  public bodies (Norge Digitalt), so Norway uses Esri too, and configs that
  still list the old cache have it removed when loaded. Tiles are cached on disk; sources that fail are skipped
  for a while. Attribution for the sources you use is printed at startup.
- **Roof colours**: every building without an OSM roof colour/material gets
  the median orthophoto colour of its footprint interior, corrected to the
  material's own colour and matched to the closest roof block. The
  correction uses the streets in the same photo as the neutral reference
  (asphalt is a dark neutral grey): the median street colour of the region
  (brightest and darkest fifth dropped) gives the photo's cast, removed per
  channel, and its brightness, mapped so a roof as bright as the street is
  grey #585858 with a curve (exponent 1.3) spreading darker and lighter roofs
  (`FeatureRasterizer.correctRoofColour`). Measured on the 80 roofs in central
  Bergen that OSM tags with a colour, with Esri imagery (which shows black
  roofs mid-grey and everything with a pinkish haze): the nearest roof block
  is of the tag's kind (dark / grey / light / red-brown / green) for 46
  instead of 37, black roofs dark for 24 of 29 instead of 6.
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
  a wooden door on the wall facing the nearest street. `building=roof`
  becomes a roof on corner posts.
- **No free ore in buildings**: nothing a building is made of crafts back
  into ingots or gems. Metal cladding is light grey concrete (metal roofs
  already were), metal paving smooth stone, gold roofs and minaret tops
  yellow concrete, copper roofs and domes waxed *cut* copper (a copper block
  gives nine ingots back, cut copper nothing), gym weights blackstone and
  basalt instead of anvils and iron blocks. Iron, gold, lapis and copper
  blocks are also kept out of the photo-colour palette. Iron bars, chains
  and lanterns stay (they do not craft back). Powered rails still stand on
  redstone blocks.
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
- `natural=coastline` → sea or land by a vote (land on the left, water on
  the right): the coastlines are drawn as a 4-connected barrier, which splits
  the region into pieces, and each piece takes the side most samples along
  its coastline point to (a sample 1.6 blocks either side every 2 blocks), so
  fjords and harbours are exactly right. It used to be a race between two
  flood fills: a breakwater a few blocks wide put "land" samples on its far
  side and land ran out across the sea; and coastline ways that only pass
  near a region (the map cells around it hand them over, no point inside)
  seeded nothing, so the whole region was taken for land. Both left dry sea
  floor with grey or light grey concrete (the photo's colour) and buildings
  over the water floating at sea level (Bergen 1:2, 2 Oct 2026: 4,963 chunks,
  two whole regions; a scan of 70 regions went from 2.86 % dry sea floor to
  0.002 %, flooded land 0.005 % → 0.004 %). A region whose coastline gives no
  samples is now treated as one without coastline; a piece with no samples
  (or a tie) is sea where it lies below sea level. A region without coastline
  of its own gets its sea from the heights written into the raster
  (`seaFromHeights`, after a coarse look every 16 blocks) and counts as having
  coastline from then on: the painter always decided such regions by height,
  but the vanilla structure filter (`footprintIsFree`), trees, settlements and
  street life asked only the coastline's sea, so 65 villages stood on open
  water in a Stavanger 1:2 test world. The 70-region sea scan is unchanged
  (0.002 % dry, 0.004 % flooded). An audit of every structure start in the
  Bergen and Stavanger 1:2 worlds (made before the fix; water per column =
  MOTION_BLOCKING - OCEAN_FLOOR heightmaps, which counts liquids only) found
  on water: villages 13/25 and 49/50, pillager outposts 4/14 and 4/10,
  modded abandoned camps 18/120 and 0/35, ruined portals standing in open
  water 3/225 and 8/84 (the rest buried in rock or on land; small "wet" shares
  of portals are their own lava); every one at sea level in a region without
  coastline. Shipwrecks and ocean ruins: none on land (271 and 220). With the
  fix all ten sampled cases see sea in the raster and are rejected.
- Lakes/ponds/reservoirs/basins/pools (incl. multipolygon islands) get one
  flat surface at the median shoreline elevation and a real bed; tidal /
  fjord water is pinned to sea level. Rivers, streams, canals, ditches are
  drawn from their centreline and width, cut one block below the banks.
- Outside coastline data, anything below sea level is ocean (bathymetry).
- **Lake and river beds** (`realWaterDepths`, `water/WaterBeds`): each
  column of a lake, reservoir, pond or river area gets its own depth
  (`RegionRaster.bedDepth`) instead of one depth with vertical banks, all
  computed from the water body's whole outline so a lake crossing many
  regions gets one bed. Distance to the shore (outer edge and islands) comes
  from an exact Euclidean distance transform over the outline filled into a
  grid of at most 1024 cells a side.
  - Norway's surveyed lakes (NVE Innsjødatabase, `water/NveLakes`, about 600
    lakes sounded by echo sounder): the lake outlines per 0.1° tile, then a
    matched lake's depth contours and sounded points (whole lake, cached in
    `lake-survey-cache/`). The contours, drawn as unbroken lines, and the
    sounded points are fixed heights on the lake's grid, land is fixed at
    0 m, and the bed is the harmonic surface through them (Laplace's
    equation by over-relaxation, coarse to fine): it slopes evenly between
    contours. (Inverse-distance averaging of the nearest points drew a flat
    shelf along every contour and a cliff between them, since the nearest
    points all lie on one line: 10, 15, 20 blocks in 20- to 60-block steps
    at Svartediket at 1:2; now 8 to 19 one block at a time.) Past the deepest
    contour the bed deepens to NVE's greatest depth with the distance from
    it. An OSM lake is matched to the NVE lake covering most (over half) of
    it.
  - Any other lake is a bowl, as GLOBathy draws beds: depth = greatest depth
    × (distance to shore / furthest distance)^0.8. Greatest depth: NVE's
    figure, else GLOBathy's (`tools/lake_depths.py` imports it per area into
    `lake-depths/`; matched by outlet within 150 m of the shore and area
    within a factor 3), else 8 m × area(km²)^0.3 (3 to 60 m; ponds 2 m).
    GLOBathy is an estimate and underrates Norway's glacial lakes: the lake
    at Kalandsvatnet's outlet has 17.9 m in GLOBathy, NVE's contours reach
    100 m.
  - Rivers: river areas are bowls and river/canal lines parabolic channels,
    as deep as a river of their width (0.3 × width^0.6 m: 1.3 m at 12 m,
    3.3 m at 50 m, 7.5 m at 200 m). Streams and ditches stay one block.
  Measured offline on the Kalandsvatnet region at 1:1 (8 s once cached):
  Kalandsvatnet 1 block at the shore, median 20, 90th percentile 40, 50 at
  the deepest part in that region (was 12 everywhere).
- Ice in cold climates, lily pads on ponds.
- Underwater plants by water biome (`worldgen/SeaVegetation`, in the decoration
  step after the decorator): vanilla's own kelp, seagrass, sea pickle and warm
  ocean coral features, looked up by name, with vanilla's placements copied by
  hand (counts, kelp/coral patch noise via `Mc.biomeInfoNoise`, the -7..7
  spread, the floor checks and a biome check at each spot), because placed
  features with a biome filter cannot be placed outside vanilla's decoration
  loop in 26.3. Ocean/deep/cold: seagrass + kelp; lukewarm: seagrass + warm
  kelp; warm: coral, seagrass, sea pickles; river (also lakes and ponds):
  short seagrass; frozen: nothing. Until 2 Oct 2026 the painter only put a
  seagrass block in 7% of shallow columns and there was no kelp.

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
  becomes sand below 35 degrees of latitude and 1500 m (real metres:
  until 3 Oct 2026 it was 1500 blocks above sea level, 3000 m at 1:2),
  rock elsewhere. Sand only gets a beach biome by the water (OSM beaches,
  or below 15 m); inland it takes the land's biome, so no buried treasure
  turns up on a mountainside.
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
- Mountains by relief (`reliefBiomes`, worlds made with 1.1.1 or later): the
  highest and lowest ground within 500 m (nine coarse samples 500 m apart,
  the same measurement the Deep Dark uses). A column of natural cover (no
  town, farm, park, water or sand, no building) in the upper 60% of 300 m or
  more of relief becomes: on rock, Stony Peaks, or Frozen Peaks / Jagged
  Peaks (600 m, upper 30%) where snowy; in arid and savanna climates
  Windswept Savanna; in forest, Grove where snowy, Windswept Forest where the
  mean year is below 9 °C; open ground Snowy Slopes where snowy, Windswept
  Hills below 9 °C; warmer mountains keep their usual biome. Seasons: the
  season pack cools every biome by 0.3 (Dec–Feb), 0.2 (Mar), 0.15 (Nov) and
  0.1 (Apr), and snow falls below 0.15, so Windswept (0.2) gets snow from
  November to April and rain in summer, while Grove, Snowy Slopes and the
  peaks (below 0) snow all year and are only used where the climate is snowy
  all year anyway; Stony Peaks (1.0) and Windswept Savanna (2.0, dry) never
  get snow. Their colours follow the months like every other biome. Vanilla's
  spawns come with them (Windswept: sheep, cows, pigs, chickens, llamas;
  Grove: wolves, foxes, rabbits; slopes and peaks: goats).

### Bedrock (`geology/Rocks`, `BedrockMap`, `MacrostratMap`)
`Rocks` asks its maps in order and takes the first answer: NGU's map in Norway,
then Macrostrat's world map (also where NGU has nothing: Sweden and Finland
inside NGU's box, NGU tiles that fail). Each map keeps its downloads in a folder
of its own (`geology-cache/`, `geology-macrostrat-cache/`), so a later cache
manager can list, clear and refresh them per source. Rock codes are the
`Rocks.Rock` ordinals; new rocks are appended (BASALT, smooth basalt, came
last) because the codes live in region rasters.

In Norway the stone under each column (below the top 4 blocks, in cliffs and
on bare rock, screes and outcrops) is the real rock from the Geological Survey
of Norway's bedrock map (NGU, layer `Berggrunn_sammenstilt_hovedbergarter`:
main rock types at the most detailed scale mapped, down to 1:50 000). The WMS
only draws pictures, so tiles of 0.01° x 0.02° (about 1.1 km, 4 m a pixel) are
fetched into `config/orbisterrarum/geology-cache/`, and each colour covering
30+ pixels of a tile is identified once with GetFeatureInfo at one of its
pixels (the answers are kept in `geology-cache/colours.json`). Grey pixels
(boundary lines, labels) and soft edges take the rock around them. Rock names
map to blocks: gneiss and migmatite stone (most of Norway), granite and the
like granite, diorite / tonalite / anorthosite / quartzite diorite, volcanic
rocks and conglomerate andesite, gabbro / amphibolite / greenstone / basalt /
eclogite / serpentinite deepslate, schist / phyllite / greywacke tuff,
marble / limestone calcite, sandstone sandstone. Ores form in all of them;
cave carvers take all of them. Per world (`bedrockTypes`), stored in the raster
as one byte per column.

Everywhere else the rock comes from Macrostrat's carto vector tiles
(`worldGeologyUrl`, `tiles.macrostrat.org/carto/{z}/{x}/{y}`, layer `units`;
some 300 source maps merged, CC BY 4.0). The tiles show each place at the map
scale Macrostrat picks for the zoom, and coverage per zoom varies a lot (Paris
and SF have maps at zoom 10, Bergen up to 8, Kathmandu and Tokyo only the world
map at 4), so each column takes the most detailed of zoom 10, 8, 6 and 4 with a
unit there. A tile's polygons are filled even-odd into a 256 x 256 grid of rock
codes (255 = no unit: try the next zoom); the raw tiles are kept as
`geology-macrostrat-cache/<z>/<x>_<y>.mvt` (empty file: nothing mapped). Only
zoom 10 is prefetched per region; coarser tiles are few and load when sampled.

A unit's rock comes from `lith` (Macrostrat's harmonised English lithology:
"Major:{...}, Minor{...}" takes the major part; "clay [5%..50%]; limestone
[50%..95%]" the largest share that is rock, since loose ground lies on top of
something), then the source map's `name`, then `descrip`. `Rocks.classify`
reads the words in English, French, Spanish, Portuguese, Italian and German
(accents dropped): the first noun that names a rock wins; without one, the last
adjective ("mafic-intermediate volcanic rocks" are volcanic, mafic + volcanic
basalt); loose ground (alluvium, till, sand, water) only when no rock is named,
as plain stone. Adjectives are told apart by their endings (-ic, -ique, -ico,
-isch, -ed, -ive...), so "granitic gneiss" is a gneiss and "basaltic andesite"
an andesite. Generic world-map units ("sedimentary rocks") stay plain stone.

### Real daylight, weather and seasons (`sky/`)
Settings under Look & Climate → Real sky, saved with each world (new worlds
take the installation's values), and each also switched off everywhere when
the installation (Mod Menu, or a server's config file) has it off. Run by the
server, so players need nothing:
- **Daylight** (`realDaylight`, `SolarClock`, `RealSky`): the overworld's
  26.x world clock is set every 10 s to the real sun at the origin: sunrise
  tick 0, solar noon 6000, sunset 12000, solar midnight 18000, linear in
  between (NOAA solar position; within a minute of met.no's published times
  for Bergen), and its rate set to the real sun's pace so the sky moves
  smoothly. Polar night and midnight sun are clamped to at least an hour of
  light or dark. The day count is chosen so day % 8 is the real moon's phase
  and it never decreases (checked hourly over a year at Tromsø). Sleeping does
  not skip the night (the next update puts the clock back). Off: rate 1 again.
- **Weather** (`realWeather`, `MetWeather`): MET Norway's locationforecast
  (worldwide) for the players' mean position (the origin when nobody is on),
  asked every 2 minutes but only fetched when the last answer's Expires has
  passed (If-Modified-Since, identifying User-Agent, 4 decimals, as its terms
  ask). The coming hour's symbol gives clear, rain (rain, sleet, snow,
  showers) or thunder, set like /weather with a 30-minute hold.
- **Seasons** (`realSeasons`, `Seasons`): a data pack `orbis_season` in the
  world overrides every overworld biome (the version's own biome JSON, read
  from the jar) with grass and foliage colours blended from the biome's own
  (the map colour table) towards the month's (spring green; September to
  November yellow, orange, brown, the grass 35/65/70% of the way to straw since
  5 Oct 2026 (40% in October read as a slightly duller green); winter dull) and, in winter, a temperature
  0.15-0.3 lower on land, so rain falls as snow where it is cold. Swamp and
  dark forest grass is left to the game's modifiers. Biome data reaches the
  game with the registries at load, so the pack is written before a world
  opens (`WorldOpenFlowsMixin`), before a dedicated server loads its world
  (`WorldHeight.prepareDedicatedServer`), and at server start for the next
  load. Summer removes the pack. A data pack reload (the landmark pack being
  switched on) keeps every `file/orbis_*` pack on: Minecraft records an
  available pack left out of a reload as disabled, and until 5 Oct 2026 that
  switched the season pack off for good in every world with landmarks (all
  26.3 test worlds). At server start a season pack that is not switched on is
  switched on with a reload once the server is idle, so such a world shows
  the season from its next opening. The south is six months apart; within 23.5°
  of the equator there are no seasons. `ServerLevelSeasonMixin` thaws in
  spring: on Minecraft's precipitation tick, a snow layer loses a layer and
  surface ice turns to water where the biome is warm enough to rain (snow
  layers only when real snow depth is off; it takes over the snow).
- **Snow depth** (`realSnow`, `sky/SnowCover`): Open-Meteo's current
  `snow_depth`, `freezing_level_height` and `temperature_2m` for the centre of
  every 0.25° cell, one request per cell (1-4 s from the user's network; 8
  threads), kept in `snow-cache/` and asked again after 6 hours (offline at a
  refresh: the old value stays, retried in 10 minutes; never fetched: 10
  minutes' rest, and the year-round climate rule paints). A weather model has
  one depth per cell for all its heights, so each cell becomes a snow line:
  with snow d0 at the cell's model elevation e0 the line is e0 - d0 / rate;
  without snow it is the freezing level less 300 m, or 0.5 °C on a 6.5 °C/km
  lapse rate, never below e0. The rate (snow per metre of height) follows the
  climate: the last full season's (July-June) winter precipitation from
  Open-Meteo's archive (ERA5) for each 0.5° cell — October-April north,
  April-October south, 7/12 of the year within 15° of the equator — as
  0.0015 × (P / 837 mm)^0.8, between 0.0004 and 0.004; Finse's 837 mm gives
  15 cm per 100 m. Probed winters 2025-26: Denver 117 mm (4 cm/100 m), Lhasa
  121 (4), Mt Elbert 256 (6), Jotunheimen 551 (11), Zermatt 588 (12), Daisetsu
  1256 (22), Bergen 1461 (24), Mt Rainier 2430 (36), Aoraki 4130 (40).
  Monthly sums rather than snowfall, because snowfall is counted at the
  model's grid height (Bergen's is at sea level, under snowy mountains). A
  year of daily archive data counts as about 13 calls in Open-Meteo's fair
  use, hence the coarser cell and one fetch per season (`rate-*.txt`, until
  the next season is in the archive in August; failed: Norway's rate, cell
  asked again in 10 minutes). Each of the four nearest cells gives a depth
  (its rate × height above its line, at most 3 m), blended by distance.
  Measuring the rate from today's depths at nearby model points was tried and
  left: in early October almost none of nine points per cell had snow. The column
  painter lays it as snow layers (eight to a block at the world's metres per
  block; at least one from 2 cm, at least one on ice caps): whole snow blocks
  then the rest as layers, one layer on roads and flat roofs. Generation waits
  up to 20 s for a cell. Trees and street furniture stand on the ground under
  deep snow (the decorator walks down through snow blocks). Biomes keep the
  year-round `snowy` guess, so only the blocks follow the day. On the server
  (`ServerLevelSeasonMixin`, one precipitation tick in three), the pile at the
  top of a column moves one layer towards today's depth: grown only on
  natural ground (dirt, stone, gravel, sand, ice tags) past the first layer,
  melted down anywhere, left alone while it snows there. The ground height
  comes from the Y back through the vertical mapping (relief shift per 256
  blocks, computed off-thread), and since 7 Oct 2026 generation takes the
  depth for that same height (and the settler keeps the ice-cap layer):
  from the real elevation generation laid 18-19 layers at Everest where the
  settler wanted 16-17 (heights there are squeezed to fit the world), all 441
  probed columns, so the settler took them off one at a time for some 20
  minutes per chunk, every chunk saved again and again and Distant Horizons
  turning each into LODs again (547 updates of 169 chunks in 5 minutes).
  Now all 441 agree. Checked 2 Oct 2026: Bergen, Finse, Tromsø,
  Zermatt and Denver bare, Galdhøpiggen 0.27 m, Matterhorn summit 1.3 m, Mt
  Elbert 0.35 m, Nyainqentanglha 0.83 m, Asahidake 1.6 m, Everest 2.4 m,
  Denali, Rainier and Aoraki 3 m (the cap).
- **Villager clock hours** (`villagerClockHours`, with real daylight on;
  `sky/LocalClock`, `VillagerClockMixin`): villagers in Orbis worlds take
  their activity from the town's real local time instead of the schedule
  attribute (which follows the sun under real daylight): rest 22:00-07:00,
  idle 07:00-08:00, work 08:00-16:00, meet 16:00-18:00, idle 18:00-22:00;
  children play by day. The mixin redirects `Brain.updateActivityFromSchedule`
  inside `UpdateActivityFromSchedule` for villagers only, checked once a
  second as Minecraft does. The time zone comes from Open-Meteo once per world
  (`<world>/orbis-timezone.txt`; Bergen: Europe/Oslo), estimated from the
  longitude until then. Under the sun's schedule a Bergen villager would work
  about 10:16-13:49 and sleep 18 hours in December.

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

### Map data from Geofabrik (`osm/extract/MapDataJob`, `client/MapDataOffer`)

At Create, when no imported store covers the new world's area at its scale
(finer than 8 m per block; the area is the selection, else about 4,000 blocks
round the spawn, 8 to 40 km), Orbis looks up Geofabrik's region list
(`index-v1.json`, 554 regions with their outlines, cached in `extracts-src/`
for a month) and picks the smallest region whose outline holds the whole area
(its corners, side middles and centre). Geofabrik cuts its files with a few
km to spare past borders, so a summit on the border still fits one region
(Everest: Tibet, 47 MB). No region short of a continent (over 8 GB) means no
offer. The offer names the region and its size; Download starts the job and
creation goes on: the file streams to `extracts-src/<id>-latest.osm.pbf.part`
(`OrbisHttp.download`: no file in memory, a part continued with a Range
request, dropped after 60 s without data; redirects followed by hand because
Geofabrik sometimes sends the dated file's address as plain http), then only
the area is imported at full detail (the area import above: memory by area)
as `extracts/<id>-area-<lat>_<lon>`, the store is rescanned and running
worlds pick it up for the regions they load next, and the file is deleted
unless `keepDownloadedMapFiles`. Measured: Malta end to end in under a
minute (8 MB file, 6 MB kept); Geofabrik gave 165 KB/s from India, so Norway
(1.3 GB) takes over two hours there, shown as time left in the toast.
`/orbis mapdata` does the same for the land around an operator (servers have
no Create screen); `/orbis mapdata stop` stops it (the part is continued
next time, or started again when 12 h old).

### Pre-generation bar (`PregenTask.showBar`)

A vanilla boss bar for every player while a sweep runs (friends on vanilla
clients see it too), updated every second: the percentage, and what the sweep
is doing now. Green while chunks finish (chunks per second over the last few
seconds, time left from the rows done); yellow while it waits, with the data it
is downloading named by kind and counted (`OrbisHttp.activeRequests`: every
request through `get`, `post` and `download` counts against its host while it
runs; hosts map to map data, terrain, bare-earth terrain, sea floor, building
heights, aerial photos, land cover, road widths, rock types, map file; weather
and time zone are left out), else the region workers preparing map data, else
the sweep's own wait (building terrain ahead, writing to disk, unloading,
freeing memory). Players saw "0.0 chunks/s" in chat for minutes while a fresh
area downloaded (Oslo, 4 Oct 2026) and could not tell a working sweep from a
stuck one.

### Generation pipeline (`worldgen/RealWorldChunkGenerator`)
- OSM is streamed per 512 × 512 m region with a 32 m margin (so nothing
  breaks at region edges), fetched from Overpass, cached on disk (gzip) and
  kept in a bounded LRU. Downloads are priority-scheduled: a region a chunk
  is waiting for always jumps ahead of speculative prefetches, up to four
  run in parallel (per-endpoint limits), and every two seconds the regions
  within `regionPrefetchRadius` of each player are queued so the frontier
  is usually decoded before the player reaches it. The spawn regions are
  downloaded when a model is built for a world: at launch on a dedicated
  server, and on a client when a world is created or opened (since 3 Oct
  2026 a client no longer fetches the remembered defaults' spawn area at
  launch; the world it then creates or opens has its own).
- The extract store (`extracts/`) is asked first; where none covers a
  region, Overpass. New worlds get their area's store from Geofabrik when the
  player agrees at Create (see Map data from Geofabrik).
- Chunk generation never blocks a world-generation thread on a download:
  `createBiomes` and `fillFromNoise` chain onto the region's future, so
  chunks in already-decoded regions keep generating at full speed while a
  new region is still in flight. With `waitForOsm=true` (default) a chunk
  still only completes once its region is available, so worlds are never
  missing data.
- Preparing a region is mostly waiting on downloads, so the waits overlap:
  the aerial photos come at the coarsest zoom that still gives about two
  pixels per block (`ImageryProvider.zoomFor`: zoom 16 at 1:2 in Bergen,
  17 at 1:1, never finer than `imageryZoom`), the terrain tiles under a
  region are fetched in parallel before its roads and water read them
  point by point (`DemTileProvider.prefetch`), and the road database runs
  beside all of it (see Surveyed road widths). A road-database fetch under
  way is shared by box across world models (`NVDB_IN_FLIGHT`): the model
  built at Create and the one rebuilt with the fitted height used to fetch
  the same boxes twice, 50 to 100 s each. Tile pixels are read
  straight from the image buffer (`TileImages.argbPixels`, 7 to 11 times
  faster than `getRGB`). Nine fresh regions at 1:2 went from 172 to 220 s
  to 80 s. `-Dorbis.profileRegions=true` logs each region's steps.
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
| `verticalMode` | relative | set per world when it is created, not by hand (see "Vertical mapping") |
| `reliefKneeMeters`, `reliefSmoothingKm`, `softCeilingBlocks` | 1500, 25, 900 | relative-mode tuning |
| `uniformHeights`, `heightSquash` | false, 1 | every height scaled by one factor, worked out per world when it is created (see "Vertical mapping") |
| `demZoom` | 13 | Terrarium zoom (13 ≈ 20 m/px) |
| `useHighResElevation` | true | use `elevationSources` where they have coverage |
| `elevationSources` | USGS 3DEP, Kartverket DTM 1 m | GeoTIFF terrain services with coverage boxes |
| `surfaceModelSources` | 12 national services | lidar surface models for measured building and tree heights (Kartverket's only with `lidarSurfaceModel`; `stacSearch`/`stacAsset`/`crs` for files in a national grid) |
| `lidarSurfaceModel` | false | the surface model on its own (without `useHighResElevation`'s terrain services); per world |
| `kartverketWhenReachable` | true | with `lidarSurfaceModel` off, Kartverket's surface model is used anyway while it answers from this network (`ImageServiceDemSource.useWhenReachable`: an 8 x 8 pixel GetCoverage of central Bergen on first use from a generation thread, two tries; a yes is trusted for 15 min across the models a world creation builds, a no is asked again after 2 min. GetCapabilities was the probe until it answered 502 Bad Gateway on 4 Oct 2026 while tiles worked, and an Oslo world was generated without the surface model). Kartverket answered directly from India on 4 Oct 2026 (12/12 tiles, 1.6 s each) after weeks of needing a VPN. Per world; worlds made before 1.1.1 have no entry and stay off, config v18 turns it off on dedicated servers. Rejected as a VPN-free stand-in the same day: ArcticDEM 2 m (satellite stereo) measured buildings about 40 % too low against Kartverket over 3,349 Bergen buildings (median error 4.4 m, 22 % within a storey; OSM tags 3.9 m, 32 %), and OpenTopography's copy of Kartverket's models is for academics and paying members only |
| `dataFolder` | "" | where downloads and imports are kept (every cache, extracts, places, heights, lake depths, map tiles); empty = the config folder; absolute, `~/...` or relative to the game folder. Nothing is moved when it changes: the log lists the folders still in the config folder. The config file, landmarks, schematics and map marks stay in the config folder |
| `useAerialImagery` | true | sample orthophoto colours |
| `imagerySources` | 21 national services, NAIP, Esri World Imagery | tile layers with coverage boxes (smallest box first) |
| `imageryZoom` | 18 | 0.6 m/px at the equator, 0.3 m at 60° |
| `imageryRoofColours`, `imageryGroundClassification`, `imageryTreeCover` | true | what the imagery is used for |
| `overpassUrls` | kumi.systems, private.coffee, overpass-api.de | tried in order; failover on 429/5xx/timeout |
| `overpassConcurrentRequests` | 1 | public instances rate-limit per IP; raise only when self-hosting |
| `osmMaxWaitMinutes` | 15 | how long a chunk waits for its region before generating terrain-only |
| `prefetchSpawnAtStartup` | true | download the spawn regions while you are in the main menu |
| `fastChunkWrites` | true | Orbis worlds skip Minecraft's per-chunk synchronous disk writes |
| `fastPregen` | true | Orbis builds the terrain ahead of a pre-generation outside the chunk system |
| `fastPregenThreads` | 0 | threads for that; 0 = half the processor's threads |
| `parallelChunkCompression` | true | Orbis worlds compress chunks for saving on Minecraft's background threads |
| `pregenPauseWorld` | true | the world stands still (like `/tick freeze`) while a pre-generation runs |
| `regionSizeBlocks`, `regionMarginBlocks`, `regionCacheSize` | 512, 32, 24 | streaming granularity / memory |
| `waitForOsm` | true | block chunk generation until region data arrives |
| `generate*` | true | per-feature toggles (buildings, roads, water, land cover, trees, street furniture, schematics) |
| `hollowBuildings`, `buildingWindows`, `buildingDoors` | true | facade detail |
| `roadCenterLineColour` | white | `white`, `yellow` or `none` |
| `roadSidewalks` | true | auto sidewalks on residential+ streets |
| `treeDensityForest` … | 0.045 | trees per column |
| `climateOverride` | "" | `arid` or `humid` |
| `snowTemperatureC`, `temperatureOffsetC` | 0.5, 0 | snow line tuning |
| `metersPerStorey` | 3 | real metres per floor for buildings known only by floor count, and the blocks between floors inside (walkable at every scale); grand buildings at least 4 |
| `buildingHeights` | auto | `walkable`: one block a metre (three a storey) at any scale; `scale`: true to scale like the terrain (a 4-storey house is 6 blocks at 1:2, 3 at 1:4); `auto`: walkable at 1:2 and finer, true to scale coarser. Per world: worlds made before 1.1.1 have no entry and stay `scale` (1.1.0 made every height true to scale; a Bergen 1:2 world from 29 Sep, made before that, stands 3-10 blocks higher on a fifth of its columns, in steps of 1.5 blocks a storey). Every metre-to-block conversion of a building (tags, registers, atlas, lidar walls and roofs, min_height) goes through `FeatureRasterizer.buildingBlocks`. Also: a `building:part` with no height tags of its own is measured like a building (atlas, registers, lidar) instead of taking the storey guess, and a part outside every building outline counts as a building; the atlas falls back to the tallest atlas building inside a big outline when none is within 20 m of its centre |

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
  integration and `terrainOnlyBeyondBlocks` (removed in 1.1.1: the far view
  options feed Distant Horizons and Voxy from Orbis's own data). The settings codec was
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
  Trail ruins are not sunk (since 2 Oct 2026; 366 in the Bergen 1:2 world had
  gone 100 blocks down with the caves): vanilla starts them 15 blocks under
  the surface for brushing and covers the rest with its "bury" terrain
  adjustment, which Orbis's terrain does not apply, so after the surface
  check below the ruin is moved up or down until its top is two blocks under
  the lowest ground of its footprint (`buryJustBelowGround`). Measured on the
  pieces' own boxes: `StructureStart.getBoundingBox()` is inflated by 12
  blocks on every side for structures with a terrain adjustment, and using it
  left the ruins 14 to 41 blocks deep.
  Surface ones (villages, temples, outposts, ruined portals, beached
  shipwrecks, igloos, mansions) are placed by vanilla's spread rules but a
  start whose footprint touches a real building, road, rail or water body is
  dropped, so they only appear on empty land. Sea ones (shipwrecks, ocean
  ruins, monuments; vanilla still asks for ocean biomes) need 90% of the
  footprint to be open water at least as deep as they are tall: 8 blocks for
  a wreck, the ruin's height (5..12) for a ruin, 24 for a monument
  (`depthNeeded`; the shelf near the shore is never that deep, so they stay
  off the shallows; before this ruins stood half out of the water at the
  shore). Vanilla builds a monument at a fixed Y 39..61 for sea level 63, which
  here is 1,700 blocks above the sea; it is moved to the same place under this
  world's sea level (`moveMonumentToSeaLevel`). Toggle in the Features tab.
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
  handled once by the chunk holding its centre. A bed needs three blocks of
  air over its floor, not two: a villager that cannot step off its bed when it
  wakes stands on top of it, 3.5 blocks up, and in Kathmandu's low houses at
  1:2 (a 3 m house is two blocks of air) residents suffocated every morning
  (Oct 2026). At 1:32 every procedural
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
- **Doors are chosen last** (1.1.1), once every footprint and
  `building:part` is in the raster. Chosen while each building was drawn,
  a door often opened onto a neighbour drawn after it or had a part's wall
  drawn over the room behind it: in central Bergen 411 of 773 doors had a
  wall right behind them, 33 opened into another building and 33 faced a
  one-cell gap. Now a door needs an open room cell behind it and open,
  dry ground in front, a one-cell gap before the next building costs a lot,
  and the side it opens to is stored in the two top bits of its flags
  (the painter used to re-guess it in a fixed east-west-south-north order).
  A ground-level part is a room closed off by its own walls, so it gets a
  door of its own. Two cells straight in from a door are kept free of
  furniture, beds, workstations and staircases; the three cells straight
  out (the doorstep) are kept free of fences, walls, hedges, trees,
  benches, lamps, bushes and berry bushes (a fence across a doorstep gets
  a gap).
- **Name signs** (`buildingSigns`): a waxed wall sign beside every door
  with the building's name from the map, or the kind of business when it
  has no name ("Bakery", "Pharmacy"), so the city can be navigated by the
  names people actually use.
- **Street name signs** (`streetSigns`, 1:2 and finer, with street
  furniture): `FeatureRasterizer.rasterizeStreetSigns` collects the nodes
  of named ground-level streets (residential, living street, tertiary,
  secondary, primary, trunk, unclassified, pedestrian; no bridges, tunnels
  or other layers) by block position; where two or more names meet, each
  name gets a STREET_SIGN decor cell: walking along its own street away from
  the node, the first step (2-24 blocks) whose right-hand side is clear of
  every other street's carriageway, on the first column off the carriageway
  (sidewalk or verge, not a building or water). A name already signed
  within 16 blocks is skipped (dual carriageways, roundabouts). The decorator
  puts two iron bars and a spruce standing sign on top, its rotation (16
  steps) along the street, the name on both sides (`Mc.setSignBack`), waxed.
  Bergen sentrum region at 1:1: 167 signs for 62 street names.
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
  Every item frame the furnishing places is fixed, like the frames in
  vanilla's structures (`ItemFrameAccessor`): survival players cannot take
  the item, turn it or break the frame, so museums and jewellers are not a
  free source of nether stars, ancient debris and diamonds (a player got
  debris within half an hour on a Bergen server before this). Worlds
  furnished earlier are locked as their frames load
  (`Interiors.lockOldDisplay`, from `ServerEntityEvents.ENTITY_LOAD`): an
  exhibit (facing up, on a quartz pillar) or a wall picture is recognised by
  its item, which is a hash of its position; valuable shop goods (diamonds,
  emeralds, gold, iron, redstone, pickaxes) by the mapped shop of the
  building they hang in. Players' own frames do not match and stay free.
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
  alone. The lifting repeats (up to four rounds) until nothing moves: a way
  lifted to its neighbour in one round lifts the ways beside it in the next
  (the cycleway on Puddefjordsbroen stayed 2-5 blocks under the deck when
  every way was compared with the others' pre-lift heights).
- **Road fixes found by the road tests (2026-09-30)** (`port/tests`,
  private; run before every install):
  - A tunnel under a shallower tunnel keeps `TUNNEL_STACK` (7) blocks under
    its road (4 blocks of air, a roof, gravel): `tunnelProfile` used to skip
    other tunnels, and Nygårdstunnelen's layer -1 and -3 tubes shared a floor.
  - The graded profile is slope-limited (`limitSlope`: a block per block up
    to 1:2, half the metres per block beyond, pinned ends kept): the road-bed
    search switching strips between samples made 2-block jumps, a sawtooth
    across diagonal streets once rounded.
  - Bridge ramps and tunnel cuttings are decided per point along the road,
    against its graded height, not per cell against the ground under it
    (half a road took the ramp; an 8-block drop in O.J. Brochs gate).
  - Each cell of a graded road takes its height from the nearest piece of
    the road (as its surface does), not the first piece to reach it: on a
    bend two pieces meet at different distances along it.
  Counts on the four test areas (roads, 1:1 / 1:2): steps 94 / 70 -> 7 / 22
  (the rest mostly the touching legs of the Nordre Skuteviksveien
  switchback), road-under-road headroom 43 / 5 -> 0 / 0, bridge-deck
  mismatches (paths) 154 / 46 -> 0 / 3.
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
  roads (all the main ones) and a lane count to four in five. A region's
  NVDB fetch starts when the region is queued, not when a worker takes it
  up; the widths and the lanes of each quarter of the region are asked for
  at once, gzip-compressed, and a page not answered within 30 s is asked
  for once more (NVDB now and then streams one answer for close to a
  minute while the next is quick). If NVDB is unreachable the region
  generates as before after at most a 60 s wait, and the service is left
  alone for ten minutes.
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

  (or `java -cp orbisterrarum-1.1.0+26.3.jar com.berg.orbis.osm.PlacesImporter
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
- **Underground layout 2** (`undergroundVersion`, worlds made with 1.1.1 or
  later; older worlds keep layout 1 so their new chunks meet the old caves,
  and a dedicated server's existing config is migrated to 1):
  - *Roof by surroundings* (`UndergroundBand.roofDepth`): the band top is the
    lowest ground within 96 blocks minus 100 only where a building or road is
    within 32 blocks (sampled every 16 blocks); with none within 96 blocks it
    is minus 30, linearly in between. Road tunnels still push it 24 blocks
    under their floor. Caves stay continuous across the steps because each
    worm is carved with its origin chunk's offset.
  - *Deepslate from the band*: the deepslate line is the band's vanilla Y 0
    instead of 60 under sea level, so caves show stone above deepslate as in
    vanilla. The line is blended between the four nearest chunk centres (no
    steps at chunk edges) and rises and falls 0..7 blocks in a smooth wave
    (24-block cells; `UndergroundBand.deepslateLine`). Until 5 Oct 2026 each
    column took a random 0..7 of its own: stone and deepslate mixed block by
    block were about 80 MB of the 1:2 Bergen world. Ores take the variant of
    the rock they replace (stone: plain ore, deepslate: deepslate ore).
  - *Fewer underground structures* (`undergroundStructureShare`, 0.33): a data
    pack written into the world when it is created (`orbis_structures`,
    `worldgen/StructureDensity`, with the height pack in the same creation
    reload; on a dedicated server with the new world's height pack) sets the
    placement frequency of vanilla's mineshafts (0.004 x share), trial chambers
    and Ancient Cities (share) structure sets. Vanilla then places fewer, and
    /locate, explorer maps and eyes of ender agree with what generates.
    Vanilla's densities are per block, so a 1:2 world had four times vanilla's
    per real km² (Bergen 1:2: 2,155 mineshafts, 424 trial chambers, 136
    Ancient Cities in 566 km²). The share is kept in the pack's pack.mcmeta and
    the sets are rewritten from vanilla's when another Minecraft version opens
    the world; worlds without the pack keep vanilla's. Together the three were
    about 78 MB of that world's 3.6 GB, so the saving is small; it is for
    gameplay.
  - *Cave biomes* (`RealWorldBiomeSource`): below the band top minus 8, at
    the band's vanilla Y -64..55, a column is Deep Dark below vanilla Y 0 in
    patches (two-octave value noise, 320-block cells) covering about half
    the underground under mountains and 3% under flat land, by the relief
    within 500 m (120 m flat .. 420 m mountains), as vanilla keeps it under
    low-erosion high ground; else Lush Caves or Dripstone Caves in patches covering about 35% under
    land that suits them (wet and wooded / dry and mountains) and 10%
    elsewhere, else the surface biome. Vanilla checks a structure's biome at
    its own vanilla Y before it is moved, so while `createStructures` runs
    (`VANILLA_FRAME`) a Y in -64..63 more than 16 blocks from the real ground
    is read in the band's frame: an Ancient City asks at Y -27 and finds its
    Deep Dark, then is sunk with the other underground structures. Its
    templates hold no air (vanilla's terrain noise digs a hollow around
    "beard_box" structures), so before structures are placed in a chunk each
    piece's box from its floor up is emptied (`hollowForCities`, never within
    ten blocks of the ground); the first cities were sealed in rock.
    Vanilla also assembles a jigsaw structure only inside the world's Y
    range, and a fitted-height world can end far below vanilla's Y -27
    (Bergen 1:2: Y -2032..-577): only the city's centre piece went in (5×3
    chunks instead of about 15×15), and trial chambers, whose padding check
    rejects starts outside the range, were missing altogether. While
    `VANILLA_FRAME` is set, `JigsawPlacementMixin` hands the assembly the
    world's range widened to include Y -64..319 (never narrowed). The band
    top is computed before structures and biomes and only peeked by the
    biome source (it is also asked on the server thread, which must not wait
    for map data). Their decoration (`UndergroundBiomes`): vanilla's moss,
    clay pools, azaleas, cave vines, spore blossoms and vines; large
    dripstone, clusters and spikes; sculk veins and patches (with shriekers
    and sensors), with vanilla's counts scaled by 128/320 (the band is 128
    blocks of vanilla's 320), the ceiling / floor scans and the biome check.
  - *Buried treasure* is no longer moved into the band (its piece looks for
    its own spot when it is built: down from the surface to the first block
    on sandstone or stone), must have a free footprint like other surface
    structures, and the painter puts sandstone under beach sand so the chest
    lands three blocks down instead of searching past a region's own rock.
- **Weather** — vanilla cools every biome by 0.05 per 40 blocks above sea
  level + 17, which with sea level at Y -1700 makes any town above ~800 m
  arctic (it snowed in Kathmandu). A mixin on `Biome` disables that height
  cooling in Orbis worlds; biomes are already chosen from the real climate at
  the real elevation, so rain, snow and ice follow the biome alone. Snow
  cover is painted at generation from today's real depth (or, with real snow
  off or offline, where the climate says so); the snow layers
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
   java -Xmx4g -cp mods/orbisterrarum-1.1.0+26.3.jar com.berg.orbis.osm.extract.ExtractImporter "E:\Software dev projects\Orbis Terrarum\data\norway-latest.osm.pbf"
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
   java -Xmx12g -cp mods/orbisterrarum-1.1.0+26.3.jar com.berg.orbis.osm.extract.ExtractImporter "E:\Software dev projects\Orbis Terrarum\data\norway-latest.osm.pbf" --full --name bergen-full --bbox 60.20 5.05 60.55 5.65
   ```
3. **Create the world**: Customize → World → *World type:
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
   north; open-sea chunks (all five elevation samples below -40 m) are skipped
   and generate instantly if anyone sails there later. Whether any sweep skips
   them: for `/orbis pregen` (radius, outline) the world's
   `pregenSkipOpenSea` (World tab, Pre-generation, on by default); for a drawn
   area its own Skip open sea toggle in the select tool strip (off by
   default): the world map sends it in `PregenSelectionPayload` (a leading
   boolean), the world preview's goes with the new world as
   `pregenSelectionSkipsSea` (taken at Create like the shapes, never a default
   for the next world) and `AutoPregen` passes it to
   `PregenTask.startSelection(level, selection, skipSea)`. Radius sweeps used
   to never skip sea.
   Bergen 1:2 with it off: 552,873 full chunks, 2.73 GB; with it on (the 26.2
   world): 482,509, 2.29 GB. Radius sweeps count skipped chunks as done. Progress is saved to
   `config/orbisterrarum/pregen/norway.json` every ten seconds, so after
   `/orbis pregen stop`, a crash or a restart the same command resumes at
   the last completed row.
5. **Upload**: the world folder plus `config/orbisterrarum/` (including
   `extracts/`, so the server can generate any chunk that was skipped) to
   the server, with the mod and Fabric API in `mods/`.

## World map (key B; N until 5 Oct 2026)

Press **N** in game (rebind under Controls, Orbis Terrarum) for a full-screen
map of the real world under the Minecraft one (`client/map/WorldMapScreen`):
street map, satellite photos with place names, or elevation (Esri's
topographic map was dropped for it on 3 Oct 2026), lined up with the blocks through the world's own projection, so a
street on the map is the street in the world. The street map (`client/StreetTiles`, since 4 Oct 2026; Esri's
World Street Map before, which named next to nothing in Kathmandu's Thamel) is drawn here from OpenFreeMap's
OpenStreetMap vector tiles (OpenMapTiles schema; free, no key, no usage limits; openstreetmap.org's own picture
tiles may not be used by an app many people run). The TileJSON at tiles.openfreemap.org/planet names the weekly
build; tiles 0-14 come from the server (gzip, kept in `map-tile-cache/openfreemap` for a week through
`TileDiskCache`), finer zooms are drawn from the zoom-14 tile. Each 256-pixel tile is drawn at 512 with Java2D,
text at twice its screen size: land cover, land use and parks; water and waterways; runways; buildings from zoom
14 (outlined from 16); streets by class (casing under fill per level: faded tunnels from 15, ground, bridges),
pedestrian streets as light streets, footpaths and tracks as fixed-width dashes, rail with white dashes, trams
thin; national and regional borders; then labels, most important first, each claiming its space (places,
water names, peaks with heights, street names upright along their longest straight piece from 14, named
places as dots coloured by kind from 15 by rank, all from 17, house numbers from 18). Names are the local ones
in Latin letters, else `name_en`, `name:latin` or `name_int`. Drawing takes about half a second a tile, so a drawn tile is kept as a PNG in `map-tile-cache/street-v1/` (named `.img` so the cache trim counts it) and read back in 20-60 ms while it is newer than its source tile; `STYLE` is bumped when the drawing changes. A source tile is decoded once for all the finer tiles drawn from it (the last 8, shared by the drawing threads), and a finer tile only draws the features whose bounds reach into it (a zoom-17 tile is a 64th of its zoom-14 source). Places carry a white symbol in their dot by kind (bag, trolley, fork and knife, cup, glass, bed, cross, pill, mortarboard, book, columns, star, film, bus, train, boat, plane, P, $, pump, church cross, tree, ball, envelope, shield), drawn with Java2D shapes. The night street map (`orbis-street-dark`, `Layer.STREET_DARK`; a button at the map's right edge, shown with the street map, with a 12-pixel sun (light) or crescent moon (dark) drawn over it for the mode the map is in (the font's ☾ read as a "C"), switches it and remembers the choice in `streetMapDark`; the layer button skips it) is the same drawing with every colour looked up in `StreetTiles.DARK` (streets a little lighter than the ground, as on Google's night map; places' colours lightened; a dark halo), cached in `street-dark-v2/`. Footpaths and sidewalks are quiet white lines with a faint edge (red dashes for each of Oslo's mapped sidewalks covered the centre); cycleways blue, steps short grey dashes, tracks brown dashes. The world map's not-generated haze is orange (0x80F5A04A; grey until 4 Oct 2026, lost on the light street map), its button "Orange: on/off". The elevation layer
(`client/ElevationTiles`, also in the world generator map) is drawn here from
Mapterhorn's terrain tiles, the heights the world is built from, sharing the
generator's `dem-cache` (files and `.missing` markers): a hypsometric tint
(under the sea, where the land tile reads 0 m, Open Waters' Seascape depths
from the finest zoom it has, pale shallows to navy deeps, gently shaded, and
"m below sea level" in the readout; green, tan and brown up to 3000 m; violet to white up to
8500 m, so Himalayan valleys, glaciers and summits differ in colour, not only
in shading) times a hillshade from the north-west (1.6 times exaggerated,
shadows kept at 60 %), Esri's place names on top, a colour key
(upright, bottom right of the map, clear of the readouts) and the height
or depth in metres in the bottom line (a 128-sample grid kept per tile on
the GPU). Where Mapterhorn has no tile at a zoom, the parent's is
drawn, as for the other layers. Place names come with the map tiles (cities,
districts, streets as you zoom in); the search box finds any place, address
or landmark and drops a red pin on it.

- **You** are a white arrow pointing where you look (a slim, notched
  arrowhead); other players in sight are cyan arrows with their names; the
  world spawn is a green square. **Markers** (toolbar) cycles their size,
  with the pins and labels, from 50% to 300% (`mapMarkerScale`, kept in the
  config). **Teleport here** in the right-click menu only appears for players
  the server lets run `/tpll` (operators, like vanilla `/tp`).
- **Drag** to move, **scroll** or **double-click** to zoom, **Me** recentres.
- **Right-click** a spot: *Teleport here* (runs `/tpll`; shown only to operators), *Mark this place*
  (a yellow pin, kept per world in `config/orbisterrarum/map-marks.json`),
  *Copy coordinates*.
- A scale bar shows a round number of blocks and the real distance, and the
  bottom line the latitude, longitude, block and distance and direction from
  you for the point under the cursor.

- **Blocks** lays the Minecraft world seen from above over the real map
  (each block's real colour with height shading, one pixel per block when
  zoomed in), to find buildings, villages and other structures; the button
  cycles off / 35% / 70% / 100%. **Grey** lays a light grey haze over the
  chunks not generated yet, and the bottom line says "not generated yet"
  under the cursor. With the hard limit on, the area outside it is tinted
  dark red with a red edge and gets no haze (`BlockMapClient.veil` leaves
  those chunks out), so the two never stack; the legend above the scale bar
  names each shading that is showing.

The Minecraft layer is made by the server (`map/BlockMapStore`,
`map/BlockMapService`): one colour per block column (RGB565), 512 x 512 per
region, in `<world>/orbis-map/`. The colour is the top block's top face
averaged from that Minecraft version's own texture; grass, leaves and water
are coloured by their biome as the game colours them (the colour maps by
temperature and rainfall, the biome's own colours, the swamp and dark forest
grass). A server has no textures, so the port kit's colours step writes the
table (`versions/<mc>/resources/assets/orbisterrarum/map_colours.json`);
blocks it does not know (other mods') keep their vanilla map colour. Flat
ground is drawn at the true colour, slopes lit from the north as on a map
item. Map files from before this (vanilla map colours) are drawn again from
the region files the first time each region is looked at. Chunks are drawn in as they load and unload, so a
pre-generation fills it for free and building shows up once the area
unloads. A region generated before this existed is drawn from its region
file the first time someone looks at it (about 250 chunks a second), or all
at once with `/orbis map render` (`/orbis map status` for progress); for
Bergen at 1:2, roughly half an hour and 60 MB, so run it in singleplayer
before uploading the world. Players' games ask only for the regions on
screen at the detail the zoom needs (1, 2, 4, 8 or 16 blocks per pixel), in
batches, nearest first, and keep them in
`config/orbisterrarum/mc-map-cache/<world id>/` so each area is downloaded
once and only re-sent when it changed; while the map is open, the regions on
screen are checked again every 4 seconds (an unchanged one costs a version
compare), so newly generated chunks appear as they are made. The world id is a random id the server
keeps in `<world>/orbis-map/world-id` and sends with the world's position:
keyed by the world's name, a new world reusing an old name ("New World")
showed the old world's cached map wherever the new one had not drawn yet. Tested on the server: the live drawing and the
drawing from region files produce identical maps.

The server tells each player's game where the world sits on Earth (origin,
scale, projection, world id; `net/WorldInfoPayload`) when they join, only if their game
has Orbis Terrarum installed, so friends without the mod still join with plain
Minecraft. Tiles use the same disk cache as the area preview.

## Choosing the spawn point

New worlds start at Mount Everest's summit (`PreviewChoices`, at game start and
after each world is created) until a preset, a search or a right-click chooses
another place; they spawn at their centre: the spawn chosen on the world generator map
is block 0, 0. Right-click the map, type coordinates ("60.39, 5.32") into its
search, search a place without an outline (a summit, a building, an address:
the map and the spawn go to its point, nothing is selected), or search a place
outside the spawn's current area (the spawn goes to
the place's own point, Nominatim's, or the middle of its outline when that
point lies outside it); a yellow cross marks it. Moving it re-projects the
selection (it is kept in latitude/longitude) with the new centre
(`AreaPreviewScreen.moveCentre`). The choice, and the map's pre-generation
switches, live in `client/map/PreviewChoices` until Create, which writes them
into the world (`SpawnGate.takeSelection`, like the selection; Customize's save
takes them too) and sets `customSpawn` off. *Exact spawn* (Advanced tab, on by
default) sets the game rule `respawn_radius` to 0 so everyone lands on that
block instead of up to 10 blocks around it. A spawn away from the centre is
still possible on a dedicated server: `customSpawn`, `spawnLat`, `spawnLon`
and `exactSpawn` in `config/orbisterrarum/orbisterrarum.json`.

The spawn is applied once, on the world's first start (`worldgen/WorldSpawn`):
the chunk there is generated in full and the spawn goes on its surface, so it
is never inside a building or under the ground. `orbis-spawn.json` in the
world folder records it, so a later `/setworldspawn` is kept. The fitted
world height also samples 10 km around a custom spawn, so a spawn on a
mountain away from the location is not cut off. Existing worlds keep their
spawn; use `/tpll <place>` and `/setworldspawn` there.

## Previewing the area before creating a world

The **World** tab of the world settings screen (Create New World, World
tab, Customize) has the **World generator map**. Press **Open** and a map opens inside the game
(`client/AreaPreviewScreen`) showing exactly the chunks
`/orbis pregen area <place>` would generate around the spawn and
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
  elevation (the **Map:** button), downloaded as tiles by background
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

**Selections drawn on the world map** (`client/map/MapSelectTool`, operators
only: the button shows when the command tree has `orbis pregen` and the server
takes the message). Rectangle and ellipse are dragged on screen (north up; 32
and 96 corners in lat/lon), the lasso freehand (a corner every 2 GUI pixels)
or corner by corner. Each shape is projected to block coordinates, simplified
(Douglas-Peucker, 2 blocks, harder for long lassos until the whole selection
has at most 6,000 corners) and applied in order, added or cut out
(`worldgen/ChunkSelection`: sorted chunk-X runs per chunk row; a chunk is in a
shape when its centre is, even-odd; a shape holding no centre takes the chunk
under its middle). New replaces the selection, Shift / Add adds, Alt /
Subtract cuts; Ctrl held during a drag constrains a rectangle or ellipse to a square or circle.
Expand / Shrink (`MapSelectTool.resize`) grow or shrink the chunk set with a
round brush (`ChunkSelection.grown` / `shrunk`: per row the union, or the
intersection, of the rows within k widened or narrowed by the disc's half
width; checked against a brute-force erosion) by k chunks, k from about 5 % of
the selection's equivalent diameter rounded to 1/2/5 × 10^n metres (at least a
chunk; exactly one chunk with Ctrl held), worked out off the render thread. The result replaces the shapes as
outlines along chunk edges (`ChunkSelection.outlines`: directed boundary edges
with the selection on their left, linked into loops turning left where two
touch at a corner, straight-run corners dropped; outer loops added and holes
cut, largest first so islands in holes come after them), which select exactly
the same chunks again (tested round trip, 29 M-chunk ellipse grown by 150 in
0.4 s, 14,652 corners, then simplified by the 6,000-corner budget). Undo keeps
the last 50 shape lists, so it also takes back Expand, Shrink and Deselect
(after a restart it falls back to dropping the last shape). The map fills the selected chunks and draws
marching ants on the run edges; the panel gives chunks, km², about 11 KB of
region file per chunk and 25 chunks/s, and the chunks the Blocks layer
already knows as generated. Generate sends the shapes
(`net/PregenSelectionPayload`, zigzag varint deltas: a 2,000-corner lasso is
4 KB of the 32 KB a client message may carry); the server checks the
operator level, rebuilds the same `ChunkSelection` and runs
`PregenTask.startSelection`: an area sweep (`new AreaSweep(selection,
outline)`, rows straight from the runs, no widening), with progress in `orbis-pregen/selection-<fingerprint>.json` so the same
selection resumes. Shapes are kept per world in
`config/orbisterrarum/map-selections.json`. The world preview
(`AreaPreviewScreen`) has the same tool through `client/map/MapView` (the
preview's own map implements it; `MapCanvas` does on the world map), without
Generate: right-drag pans there and a right-click still sets the spawn. Its
shapes are stored under `preview`; the preview's scale buttons re-project their
lat/lon to blocks.

**The world generator map** (`client/AreaPreviewScreen`, World tab →
Pre-generation) starts empty at the location: the old automatic area overlay
(PreviewRaster of a looked-up outline) is gone, and the selection is the only
area. The find box adds a place's outline (`Geocoder.lookupArea`, its 60
largest parts) or a radius circle to the selection under the current mode;
the side panel computes the numbers from the `ChunkSelection` (chunk area,
extent, crossing times, chunks, region files per 32-row band, KB_PER_CHUNK and
CHUNKS_PER_SECOND from PreviewPlan) and carries the Generate on creation
switch (bound to the World tab option). Its scale buttons are ‹ › under "World
scale" (they change the world, not the map's zoom). Changing Metres per
block, there or on the Scale tab, sets the terrain tile zoom to suit
(`OrbisConfig.demZoomFor`: 15 up to 1:2, one less per doubling, 11 at 1:32,
never below 10; a chosen 16 stays up to 1:2): 1:32 with zoom 15 had the
spawn-area warm-up fetch sixteen times the tiles it needs. Coarser than 1:4
(`OrbisConfig.LIDAR_MAX_METERS_PER_BLOCK`) the scale change also switches
`useHighResElevation` and `lidarSurfaceModel` off, and `buildModel` skips the
lidar terrain and surface model there whatever the switches say (logged): at
1:12 a building is about a block, and the 1 m surface model only cost slow
Kartverket requests. The tool is always
on there, with a hand tool for panning; creating a world (the new-world settings saved) clears the
preview selection after copying it into the world.

**Aternos export** (`export/AternosExport`, `/orbis export aternos [orbis] [zip]`,
op). Saves the world, then on a background thread: every fully generated
overworld chunk (PregenMap.regionState == full; with the hard limit on, only
its allowed area; its empty chunks never), nearest the spawn first, until the
budget: one zip of at most 900 MB in all (the world's other files counted; a
941 MB zip was refused by Aternos on 2026-09-28), else up to 3.4 GB as a base
zip (everything but the overworld's region files, folder entries included,
one top folder holding level.dat) plus `region-batch-NN` folders of about
200 MB for the Files page. Region, entity and POI files are rewritten with
only the kept chunks, back to back on 4 KiB sectors. Terrain chunks go without
their light (SkyLight, BlockLight) and heightmaps, marked `isLightOn` false
(since 5 Oct 2026): Minecraft rebuilds missing heightmaps as it reads a chunk
and relights a chunk saved as not lit when it loads (the light steps run for
loaded chunks too), both checked in 26.2 and 26.3. That was 22% of the
terrain: Bergen 1:2 (1.1.1) went from 2.36 to 1.85 GB of terrain and fitted
whole (520,292 chunks, 2.81 GB of data, 2.4 GB on disk, 536 s on 12 threads;
every sampled chunk readable, unlit, without heightmaps), where before the
far ends of the strip were left out. The budget counts the chunks as written.
Each terrain chunk is stripped once, into a copy under `stripped/` in the
export folder (measured there for the budget, then copied from when the
upload is written, then deleted); the work runs on the cores less two at
normal priority (stripping twice on Java's shared pool took 32 minutes in
game, against 9 offline; the lowest priority starved it behind the game's and
Voxy's threads instead of keeping the game smoother). A boss bar shows every
player the export: the step (reading the terrain, choosing the chunks,
copying the world's files, writing the upload, packing), the step's details
(region files done, GB before and after, chunks going in) and the time left,
the steps weighted by their measured share of the run. Plain-server mode (the
default): world_gen_settings' overworld generator becomes flat cold ocean
(bedrock, stone, 4 gravel, 20 water up to the sea level), world_border.dat
goes round the kept chunks' square, the frame-lock datapack is added (with
this version's pack format; it had 26.2's 107 alone) and orbis-map dropped.
The world's placement for the map goes in that pack as a chat type,
`orbisterrarum:world_info`, whose translation key carries origin, scale,
projection and world id (`WorldInfoPayload.encode`): chat types are synced to
every player by any server, plain Minecraft never uses this one, and an Orbis
client that got no world-info message reads it on joining. Without Orbis on
the server the map's Blocks layer is off and its teleport sends `tp @s x ~ z`
(no `/tpll`); `orbis` keeps the generator and trims orbis-map to the kept
regions. Output: `<game>/orbis-exports/<world>-aternos-<time>/` with
coverage.png and HOW-TO-UPLOAD.txt. Tested offline on the full Bergen 1:2
world: 482,509 chunks in 120 s, a 113 MB base zip and 12 batches (every
chunk readable), generator, border and name as the hand-made upload of
2026-09-28; `zip` gave 832 MB with the 213,935 chunks nearest the spawn.

**Hard limit** (`worldgen/HardLimit`, `pregenHardLimit`, `/orbis hardlimit
on|off`, the override in `orbis-pregen/hard-limit.txt`). The allowed area is the
creation selection (projected with the world's mapper) plus every sweep started
since: radius circles, area sweeps (their own rows) and map selections go into
`orbis-pregen/allowed-extra.json` (ChunkSelection rows as JSON) whether the
limit is on or not. It is loaded as the overworld loads
(`ServerLevelEvents.LOAD`), before the spawn area generates. With the limit on,
a chunk outside it is left empty: `createBiomes` answers without the region
(the biome source returns its fallback there), `fillTerrain` writes only a
full-height barrier wall on the sides that touch an allowed chunk (section
writes, WG heightmaps primed), and carving, structures, decoration and mob
spawning skip it; `OsmRegionManager.prefetchAround` skips regions with no
allowed chunk. Players outside it (above the build limit, pearls, spawning
there) are teleported every tick to where they last stood inside, or to the
nearest allowed chunk's ground. Empty chunks are listed in
`void-chunks.bin`; `ChunkMapMixin` (HEAD of `ChunkMap.readChunk`) reads
one as missing once it is allowed (the area grew, or the limit is off), so it
generates properly. The world map neither draws those chunks nor
counts them as generated (`BlockMapStore.draw`, `fullMask`), so they show
the red outside-the-limit tint instead of blank land. The world map of players with the
mod also gets the limit (`net/AllowedAreaPayload`: on/off and the allowed rows
as zigzag varint deltas, about 2 KB for a city and 30 KB for a 120 × 80 km
ellipse; an area of over 150,000 runs is sent as off), at join and whenever the
area grows or the limit is switched; while it is on, `WorldMapScreen`
darkens everything outside (whole bands north and south of the area, the gaps
between runs row by row, at most one row per physical pixel) and draws its
edge in red once chunks are at least 2 pixels high, with a legend above the
scale bar.

**Pre-generation chosen at world creation** (`worldgen/AutoPregen`). The
World tab's Pre-generation group holds the generator map and
`pregenOnCreate` (`pregenArea` remains for older worlds; an empty one with no
shapes now means nothing to generate). Creating the world copies the
preview's shapes into `pregenShapes` (lat/lon outlines with add/cut flags);
all three travel in the world's settings (`WorldSettings`, so a dedicated
server has them too). Five seconds after the server starts, a world with
`pregenOnCreate` starts: the shapes as a selection sweep if there are any
(projected with the world's own mapper), else a radius as a 96-sided selection
round block 0, 0, else the place (or, empty, the municipality at the origin,
`Geocoder.areaAt`) as an area sweep. Both resume from their progress files.
`orbis-pregen/auto-state.txt` says `done` once the sweep finishes
(`PregenTask.lastFinished`), or `paused` when it was stopped by hand; a paused
world waits for `/orbis pregen resume`. On server stop the running task is put
aside with its progress saved (`PregenTask.serverStopping`, which also stops
a stale task from looking "running" in the next world of the session), and
the automatic sweep starts again when the world opens. Tested offline: 300 random
add/cut sequences agree chunk for chunk with a brute-force even-odd test.

`/orbis pregen <radiusKm>` (around you) or `/orbis pregen at <lat> <lon>
<radiusKm>` generates every chunk within the radius (real kilometres at any
scale: 5 km at 1:2 is 2 500 blocks; the limit is 15 000 blocks), nearest first, after
queueing all the map regions of the area for download. Progress is shown in
chat every 2 minutes as four short lines (the log every 30 s) (place, percent and ETA; rows generated
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
map (street map, or satellite with or without labels, all from Esri; the
topographic layer was dropped on 3 Oct 2026; OpenStreetMap's volunteer tile servers block pages opened from a
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
it leaves memory, and the game writes chunks one at a time; with
Minecraft's `syncChunkWrites:true` default in `options.txt` (on Windows)
every one of those writes is also synchronous, which on a typical SSD means
a few chunks a second. The "Fast chunk writes" setting (`fastChunkWrites`,
on by default) therefore turns synchronous writes off for Orbis worlds only:
a mixin on the `ServerLevel` constructor (`ServerLevelWritesMixin`),
where Minecraft reads the setting while it creates each level, makes it read
false when the overworld's generator is Orbis's. `options.txt`, `server.properties` and
other worlds are left alone. The cost is the usual one: after a power cut
the last few seconds of chunks may be lost and generate again.
The sweep therefore watches the writer's queue and pauses generation while
more than 2 000 chunks are waiting for the disk (the status line shows
"queued for the disk" and, when paused, why). It also pauses when the heap
is still three quarters full after a garbage collection, and progress is
only recorded for rows the disk has (a write barrier every 10 s), so a
resume after a crash never skips rows that were generated but never
written. Without this, a sweep of Bergen generated 1.1 million chunks in
100 minutes, wrote 600 of them, and ran a 16 GB heap out of memory with
the rest queued. When "Fast chunk writes" is off the start message says so.

**Finished chunks are not loaded again** (`worldgen/DiskChunks`). Minecraft
26.x marks every chunk it reads as unsaved (`SerializableChunkData.read` calls
`setLightCorrect`, which calls `markUnsaved`, and nothing marks it saved
again), so every chunk a sweep loaded was written back on unload, changed or
not. Re-running the Bergen selection to regenerate 5,072 repaired chunks
rewrote 563,000 (only `LastUpdate` differed) and took 1.5 hours. Sweeps now
skip chunks the region file already holds as `minecraft:full`: the region
file is read whole (the last six kept), the chunk's zlib stream inflated only
as far as its `Status` field; another compression, an external chunk or a
torn read count as not finished and go through the game as before. Checked
on the Bergen world: 683,008 positions in 14.5 s on one thread, 552,873
finished, the same count as a full parse. Skipped chunks count as done in the
progress ("already there").

**Parallel chunk compression** (`parallelChunkCompression`, on by default;
`worldgen/ChunkCompression`, mixins `ChunkMapMixin` (save), `CompoundTagMixin`,
`RegionFileStorageMixin`, `RegionFileAccessor`). Minecraft 26.x saves a chunk
in three steps: `SerializableChunkData.copyOf` on the server thread, its
`write()` (the NBT) on `Util.backgroundExecutor`, then the IOWorker's single
thread per storage compresses it (deflate) and writes the region file. In the
Bergen 1:2 run (2 Oct 2026, 103 chunks/s) the sweep waited on that thread in 8 %
of its progress reports and found it over 1,000 chunks behind in 40 %; during a
repair re-sweep, which reads 548,000 existing chunks through the same thread,
it sat at the 2,000 limit. So in Orbis worlds the `supplyAsync` in
`ChunkMap.save` also compresses the NBT, on the same background thread, into
exactly the buffer `RegionFile.ChunkBuffer` would produce (a 4-byte length =
count - 5 + 1, the `RegionFileVersion` id, the compressed stream with
`RegionFileVersion.getSelected()`), kept on the tag (a field added to
`CompoundTag`). `RegionFileStorage.write` then hands that buffer straight to
`RegionFile.write(pos, buffer)` (invoker) when the file's version id matches,
and skips the compressing stream and `NbtIo.write`; any other tag (entities,
POI, other worlds, a failed compression) goes the usual way. The files are
byte-for-byte Minecraft's. Same code in 26.2 and 26.3. The terrain threads
(`fastPregenThreads`) became a setting at the same time, to be tuned with a
JFR of a fresh sweep.

**Fast pre-generation** (`fastPregen`, on by default; `worldgen/FastPregen`).
Minecraft's chunk system, not the work, was the limit: an experiment on
Stord ran Orbis's terrain step outside it at 155 chunks a second (12
threads), Minecraft finished such chunks at 88 a second, and a sweep through
the chunk system managed 23 to 30. So while a sweep runs, a second walk goes
over the same chunks, a tile ahead (each tile followed by the twelve rows
below it, which the serpentine order would otherwise reach a whole tile-row
later), and on half the cores builds each chunk's terrain step the way the
game would: structure starts (Orbis's `createStructures`, kept in a cache;
Minecraft's template caches are plain hash maps, so a chunk that meets a
ConcurrentModificationException tries again, up to six times, each chunk's
starts are worked out by one thread even when several want them, and the
first 300 of a sweep one at a time while the caches fill; all of them one
at a time were most of the time and held the sweep up),
structure references (vanilla's rule, from the cached starts of the 17 x 17
chunks around), biomes (`createBiomes`), then `buildDirect` (the terrain
fill and the caves), at the terrain-done status (26.3 `terrain`, 26.2
`carvers`; the version bridge also primes the heightmaps 26.3's terrain
step primes). The built chunk waits in memory (at most 3,000) and is handed
to Minecraft when it loads that chunk (`ChunkMapMixin` on
`scheduleChunkLoad`), but only after the game's own read found nothing on
disk, so an existing chunk is never replaced; like vanilla's, the steps
after that read run on the server thread (the chunk-type table is a plain
hash map: writing it from background threads crashed the game). A first
version saved the
built chunks to disk instead: every chunk then went through the game's
single save thread twice and the sweep ran at 16 chunks a second. The sweep
asks Minecraft for a chunk only once every chunk of the sweep within eleven
chunks of it is done: Minecraft's generation pyramid loads every chunk
within 11 of one it finishes at least to structure starts, and one not
built yet became a stub that fast mode had to leave to the slow path (a
quarter of a fresh Stord sweep with a ring of 3). A
chunk the region file's header lists, or that is loaded, is not built; one
whose terrain tiles cannot be downloaded, or that fails, is left to the
game. The status lines show "terrain built ahead".

**Unloading keeps up.** Minecraft unloads chunks only in the time a tick
has left over, unless more than 2,000 are waiting, and then only the excess.
A sweep fills every tick, so about 2,000 finished chunks sat waiting to
unload; they still count as loaded, and the sweep spent most of its time
"waiting for unloads" (16 to 29 chunks a second on Stord after starting at
105). While a sweep runs, a mixin on `ChunkMap.processUnloads` makes the
level unload at least 32 chunks a tick; an unload costs the server thread
about 0.1 ms.

**The server thread goes first.** A sweep keeps every core busy, and the
server thread, which runs part of every chunk's generation and all the
unloads, then got too little of the CPU: a flight recording of a Stord sweep
on a 6-core laptop had the machine at 98% and the server thread at two
thirds of a core, spending 58% of its time on mobs (a real-time night:
monsters), 17% on random block ticks, 9% on spawning and 0.2% in Orbis code
("Can't keep up" every half minute). In Orbis worlds the server thread
therefore runs at the highest thread priority, and while a sweep runs the
world stands still ("Pause the world while pre-generating",
`pregenPauseWorld`): Minecraft's own tick freeze, the one `/tick freeze`
uses, which skips mobs, random block ticks, spawning, redstone, weather and
time but keeps loading, generating, unloading and saving chunks, and lets
players move. It is undone when the sweep ends or stops, and when the server
stops (a resumed sweep freezes it again). A freeze someone made themselves
is left alone. A frozen world also stops expiring the temporary chunk
tickets (the short holds around chunks being generated), so their chunks
never unloaded: the first overnight sweep with the pause sat at 4,482 loaded
chunks "waiting for unloads" for five and a half hours. A mixin on
`ServerChunkCache.tick` keeps those tickets expiring while a sweep runs on
the level, and as a safety net a sweep that finishes no chunk for two
minutes while the world is paused lets the world run for a minute, then
pauses it again; after three such stalls it runs for the rest of the sweep
(logged). Time spent waiting for Orbis's own data does not count (the sweep
"building terrain ahead", or map regions being built): the first Stavanger
1:2 sweep (2 Oct 2026, a fresh area, downloads for every region) finished no
chunk in its first two minutes, the old rule lifted the pause for the whole
sweep, and a flight recording then had the server thread at about 85 % of a
core: 51 % ticking mobs (villagers, zombies, animals moving and colliding),
16 % ticking chunks, 11 % drawing the world map's block layer
(`BlockMapStore.draw`), 10 % `ChunkMap.tick`; 35-70 chunks/s against Bergen's
paused 100. The same recording had the terrain builders about 10,000 chunks
ahead using under two of their seven threads, and the disk queue near zero
(parallel compression on), so neither more builder threads nor faster saving
would help there. With the pause held, the next recording had the server
thread at about a quarter of a core and everything else waiting: the map
region workers idle two thirds of the time while the terrain builders waited
for the regions still downloading. Sweeps now queue the map regions of the
two region rows ahead (not one), and the region cache holds five rows
across (at most 480 regions, about 8 MB each). A sweep never slows down for the players: while it runs,
nobody is expected to be playing.
On a dedicated server, also set `pause-when-empty-seconds=-1` in
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

## Lidar and canopy inputs (trees from the canopy map, lidar roofs)

Two optional inputs refine what the OSM data alone cannot say. Both are on
by default (`treesFromCanopy`, `roofsFromSurfaceModel`) and both fall back
silently to the older rules when their data is missing, so nothing changes
until the data is there.

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
taken from the ridge (`FeatureRasterizer.roofFromSurfaceModel`).

A lidar source turns itself off for 3 minutes only after 12 tiles in a
row have failed all three tries (it was 4 tiles and 10 minutes, which one bad
minute at Kartverket was enough for).

Kartverket's services (all on 159.162.x.x) cannot be reached from every
network; through a VPN to Norway (split tunnel for 159.162.0.0/16 only) the
DOM WCS answers, but on 30 Sep 2026 half the requests came back as HTTP 504
after 30 s. So the surface model has its own switch, `lidarSurfaceModel`
(off by default; the terrain services of `useHighResElevation` are not
needed, Mapterhorn carries that terrain), each tile gets three tries, and the
tiles under a region's buildings (only those, to spare a VPN's data
allowance) are fetched six at a time before the buildings are measured
(`ImageServiceDemSource.prefetch`). Tree heights take the canopy map first
and the surface model only where there is none, so forests do not pull the
lidar in. Measured on central Bergen (region 0,0 at 1:1): 143 of the 144
buildings without a height tag got a measured height (mean change 4 blocks;
the Bergen sentrum police station 19 -> 33), 62 a measured roof shape
(gabled 59 -> 85, hipped 21 -> 41, flat 279 -> 233); 11 tiles, 2.8 MB, 100 s
instead of 5 s while downloading. Tiles stay in `dsm-cache/`.

### Ground classes from a segmentation model — removed
An experiment read per-tile class maps made by IGN's FLAIR-INC RGB U-Net
(`tools/classify_ground.py`) in place of the colour rules. On Esri imagery its
predictions were unusable under every preprocessing tried (forests as water,
Paris blocks as farmland, raw 0–255 input all "building"), so the option, the
reader and the script were removed. ESA WorldCover gap filling ("Land cover
and vegetation") does that job worldwide; the imagery colour rules still
classify ground inside it.

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
- Graded roads are level across their width along a smoothed profile. Where
  roads meet at different levels (bridge approach ramps, tunnel cuttings,
  interchanges) the level change is a plain ground step: generated retaining
  walls and parapets were tried and looked like ruins between parallel roads.
- Tunnel cover (6.5 m), the stretch inside a portal before it (16 m) and
  bridge clearances (5 m over the ground for main roads, 5.5 m over a road,
  6.5 m over a railway) are real heights, converted to blocks at the world's
  scale, but never less than a player needs: 3.5 blocks of tunnel cover and
  4 blocks under a bridge over a road. At 1:2 and smaller scales, tunnels and
  bridges are therefore relatively taller than the real ones, and their
  cuttings and ramps longer. (As fixed block counts they were right at 1:1
  only: at 1:2 the Nygårdstunnelen portals sank 4 blocks into flat ground.)
- Relief mode is not 1:1 above 1500 m of regional elevation, and the very
  top of the Himalaya is squeezed; a 4064-block engine cannot hold Earth.
- One building per column: a `building:part` that floats above another
  building (skybridge) is dropped in favour of the lower building.
- Roof stairs and ridge slabs are chosen by colour; a roof block with no
  close-coloured stairs in the running Minecraft version stays in full-block
  steps (bright concrete colours on 26.2, gold domes, thatch).
- Interiors are empty shells (floors only).
- Climate has no precipitation data; use `climateOverride` where the
  latitude default is wrong.
