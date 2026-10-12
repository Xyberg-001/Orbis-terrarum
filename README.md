# Orbis Terrarum

Play on the real Earth in Minecraft. Pick any place, from your town to a whole country, and the mod builds it from real map data: hills,
coastlines, rivers, every building, roads, bridges, railways and forests. Minecraft **26.2** and **26.3**, **Fabric**.

## Features

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

## Install

1. Fabric Loader and **Fabric API** for your Minecraft version.
2. `orbisterrarum-<version>+<minecraft>.jar` in `mods/`.
3. Recommended: **Mod Menu**, for the settings.

New areas need an internet connection while they generate; everything downloaded is cached.

## Create a world

**Create New World → World Type: Orbis Terrarum → Customize**, open the **World generator map**, search a place or right-click
where you want to spawn, and create the world. The other tabs (Features, Look & Climate, Data Sources, Advanced) have sensible defaults.

## World map (B)

| Action | How |
|---|---|
| Find a place | Type in the search box: the card has **Teleport** and **Select area** |
| What's here | Right-click → **What's here** |
| Teleport / mark / copy coordinates | Right-click |
| Map style | Street, satellite or elevation; day / night |
| See the blocks | **Blocks** shows the world from above |
| Pre-generate | **Select area**, draw it, **Generate** |
| World info and switches | The **i** button (real sky, hard limit, download map data) |

## Commands

| Command | What it does | Who |
|---|---|---|
| `/tpll <place or lat, lon>` | Teleport to a place ("Eiffel Tower") or coordinates | op |
| `/wherell` | Your latitude and longitude | everyone |
| `/orbis pregen <km>` | Pre-generate everything within `<km>` of you | op |
| `/orbis pregen at <lat> <lon> <km>` | The same around a coordinate | op |
| `/orbis pregen area [all] <place>` | Pre-generate a town or country by its outline (`all`: islands and overseas parts too) | op |
| `/orbis pregen status` / `stop` | Progress, or stop it | op |
| `/orbis export aternos [orbis] [zip]` | Make an upload of this world for an Aternos server | op |
| `/orbis import <file.osm.pbf>` | Use a downloaded OpenStreetMap file instead of the online servers | op |
| `/orbis import-places <file.geojson>` | Add Overture place data | op |
| `/orbis settings [name] [value]` | The mod's performance and network settings, for servers | op |

Everything else is on the world map (B): What's here, the World window (**i**) with the real sky and hard limit switches, Download
map data, Draw all, and Resume.

## Servers

- Install the mod on the server only; set `level-type=orbisterrarum\:earth` in `server.properties` before the first start.
- Easiest: create and pre-generate the world in singleplayer, then upload it.
- Aternos: `/orbis export aternos` makes the upload and a how-to file.

## Tested on

Intel i7-9750H, RTX 2060, 16 GB for Minecraft. Bergen at 1:2 (566 km²): pre-generated in 1.5 hours (about 103 chunks/s), 3.3 GB on
disk, 150–260 FPS.

## Known issues

- Generation needs internet and is slower than vanilla: pre-generate for multiplayer.
- On 26.2, Voxy 0.2.19's far terrain disappears with Iris 1.11.4 + Sodium 0.9.2; use Iris 1.11.2 + Sodium 0.9.1.

---

[Data sources and credits](docs/CREDITS.md) · [Internet and privacy](docs/PRIVACY.md) · [How it works and every setting](docs/technical-notes.md)
