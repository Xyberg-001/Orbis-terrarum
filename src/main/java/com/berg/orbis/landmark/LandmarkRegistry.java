package com.berg.orbis.landmark;

import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.osm.LatLon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Hand-authored landmarks that get pasted from real schematics instead of
 * being extruded from their OSM footprint. Flat height-extrusion cannot
 * represent the Eiffel Tower's lattice or the Taj Mahal's silhouette; a
 * schematic can. Landmarks are listed in config/orbisterrarum/landmarks.json
 * and their files live in config/orbisterrarum/schematics/.
 *
 * Two layers of "not just a box" exist:
 *  1. Tag-based procedural shapes ({@link #looksDomed} + DomeGenerator,
 *     roof:shape handling in the rasteriser) -- automatic, no curation.
 *  2. This registry -- explicit schematics for specific famous buildings.
 *     OSM buildings whose centroid falls inside a landmark's radius are
 *     suppressed so the schematic isn't buried in an extruded footprint.
 */
public final class LandmarkRegistry {

    /** JSON entry. anchor: "center" (schematic centred on lat/lon) or "corner" (min x/z corner at lat/lon). */
    public static final class Landmark {
        public String name = "";
        public double lat;
        public double lon;
        public String file = "";
        public int rotation = 0;
        public int yOffset = 0;
        public double radiusMeters = 100;
        public String anchor = "center";
        /** Optional fixed base Y; when null the terrain height at the anchor is used. */
        public Integer baseY;
    }

    /** A landmark resolved into world coordinates with its schematic decoded. */
    public static final class Placement {
        public final Landmark landmark;
        public final Schematic schematic;
        public final Rotation rotation;
        public final int minX, minZ;      // world coords of the rotated schematic's min corner
        public final int sizeX, sizeZ;    // rotated footprint
        public final double centerBlockX, centerBlockZ;
        private volatile int baseY = Integer.MIN_VALUE;

        Placement(Landmark landmark, Schematic schematic, Rotation rotation, int minX, int minZ, int sizeX, int sizeZ,
                  double centerBlockX, double centerBlockZ) {
            this.landmark = landmark;
            this.schematic = schematic;
            this.rotation = rotation;
            this.minX = minX;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.centerBlockX = centerBlockX;
            this.centerBlockZ = centerBlockZ;
        }

        public int maxX() {
            return minX + sizeX - 1;
        }

        public int maxZ() {
            return minZ + sizeZ - 1;
        }

        public boolean intersectsChunk(int chunkMinX, int chunkMinZ) {
            return chunkMinX + 15 >= minX && chunkMinX <= maxX() && chunkMinZ + 15 >= minZ && chunkMinZ <= maxZ();
        }

        /** Base Y (world Y of schematic layer 0), resolved lazily from terrain at the centre. */
        public int baseY(IntBinaryOperator terrainHeight) {
            int y = baseY;
            if (y == Integer.MIN_VALUE) {
                synchronized (this) {
                    y = baseY;
                    if (y == Integer.MIN_VALUE) {
                        if (landmark.baseY != null) {
                            y = landmark.baseY;
                        } else {
                            y = terrainHeight.applyAsInt((int) Math.floor(centerBlockX), (int) Math.floor(centerBlockZ)) + 1;
                        }
                        y += landmark.yOffset - schematic.offsetY;
                        baseY = y;
                    }
                }
            }
            return y;
        }

        /** Block state at a world position, or null if the schematic leaves it untouched. */
        public BlockState stateAt(int worldX, int worldY, int worldZ, int base) {
            int ly = worldY - base;
            if (ly < 0 || ly >= schematic.height) return null;
            int rx = worldX - minX, rz = worldZ - minZ;
            if (rx < 0 || rz < 0 || rx >= sizeX || rz >= sizeZ) return null;
            int lx, lz;
            switch (rotation) {
                case CLOCKWISE_90 -> {          // (x,z) -> (L-1-z, x)
                    lx = rz;
                    lz = schematic.length - 1 - rx;
                }
                case CLOCKWISE_180 -> {         // (x,z) -> (W-1-x, L-1-z)
                    lx = schematic.width - 1 - rx;
                    lz = schematic.length - 1 - rz;
                }
                case COUNTERCLOCKWISE_90 -> {   // (x,z) -> (z, W-1-x)
                    lx = schematic.width - 1 - rz;
                    lz = rx;
                }
                default -> {
                    lx = rx;
                    lz = rz;
                }
            }
            BlockState s = schematic.get(lx, ly, lz);
            if (s == null) return null;
            return rotation == Rotation.NONE ? s : s.rotate(rotation);
        }
    }

    private final List<Landmark> landmarks = new ArrayList<>();
    private final List<Placement> placements = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static LandmarkRegistry load(Path jsonFile, Path schematicsDir, CoordinateMapper mapper) {
        LandmarkRegistry reg = new LandmarkRegistry();
        try {
            Files.createDirectories(schematicsDir);
            if (!Files.exists(jsonFile)) {
                List<Landmark> examples = new ArrayList<>();
                Landmark taj = new Landmark();
                taj.name = "Taj Mahal";
                taj.lat = 27.1751;
                taj.lon = 78.0421;
                taj.file = "taj_mahal.schem";
                taj.radiusMeters = 150;
                Landmark eiffel = new Landmark();
                eiffel.name = "Eiffel Tower";
                eiffel.lat = 48.8584;
                eiffel.lon = 2.2945;
                eiffel.file = "eiffel_tower.schem";
                eiffel.radiusMeters = 100;
                examples.add(taj);
                examples.add(eiffel);
                Files.createDirectories(jsonFile.getParent());
                Files.writeString(jsonFile, GSON.toJson(examples));
                reg.landmarks.addAll(examples);
            } else {
                List<Landmark> list = GSON.fromJson(Files.readString(jsonFile), new TypeToken<List<Landmark>>() {}.getType());
                if (list != null) reg.landmarks.addAll(list);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not read landmarks.json: " + e);
        }

        for (Landmark lm : reg.landmarks) {
            if (lm.file == null || lm.file.isBlank()) continue;
            Path file = schematicsDir.resolve(lm.file);
            if (!Files.exists(file)) {
                System.out.println("[orbis] Landmark '" + lm.name + "': schematic " + file + " not found -- the OSM footprint will be used instead.");
                continue;
            }
            try {
                Schematic s = SchematicLoader.load(file);
                Rotation rot = switch (((lm.rotation % 360) + 360) % 360) {
                    case 90 -> Rotation.CLOCKWISE_90;
                    case 180 -> Rotation.CLOCKWISE_180;
                    case 270 -> Rotation.COUNTERCLOCKWISE_90;
                    default -> Rotation.NONE;
                };
                boolean swap = rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90;
                int sizeX = swap ? s.length : s.width;
                int sizeZ = swap ? s.width : s.length;
                double[] anchor = mapper.toBlockExact(lm.lat, lm.lon);
                int minX, minZ;
                if ("corner".equalsIgnoreCase(lm.anchor)) {
                    minX = (int) Math.floor(anchor[0]);
                    minZ = (int) Math.floor(anchor[1]);
                } else {
                    minX = (int) Math.floor(anchor[0] - sizeX / 2.0);
                    minZ = (int) Math.floor(anchor[1] - sizeZ / 2.0);
                }
                reg.placements.add(new Placement(lm, s, rot, minX, minZ, sizeX, sizeZ, anchor[0], anchor[1]));
                System.out.println("[orbis] Landmark '" + lm.name + "' loaded: " + s.width + "x" + s.height + "x" + s.length
                        + " blocks at " + minX + "," + minZ);
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] Landmark '" + lm.name + "' failed to load: " + e.getMessage());
            }
        }
        return reg;
    }

    public List<Placement> placements() {
        return placements;
    }

    public List<Landmark> landmarks() {
        return landmarks;
    }

    /** True if an OSM building centred here should be replaced by a loaded schematic. */
    public boolean isCoveredBySchematic(LatLon p) {
        for (Placement pl : placements) {
            if (haversineMeters(p, new LatLon(pl.landmark.lat, pl.landmark.lon)) <= pl.landmark.radiusMeters) return true;
        }
        return false;
    }

    /** Whether generic tags suggest this building needs a dome instead of a flat box. */
    public static boolean looksDomed(Map<String, String> tags) {
        if (tags == null) return false;
        String roof = tags.get("roof:shape");
        if (roof != null) return "dome".equals(roof) || "onion".equals(roof);
        return "yes".equals(tags.get("dome"))
                || "mosque".equals(tags.get("building"))
                || ("place_of_worship".equals(tags.get("amenity")) && "muslim".equals(tags.get("religion")))
                || "planetarium".equals(tags.get("amenity"))
                || "observatory".equals(tags.get("man_made"));
    }

    private static double haversineMeters(LatLon a, LatLon b) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double la1 = Math.toRadians(a.lat()), la2 = Math.toRadians(b.lat());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
