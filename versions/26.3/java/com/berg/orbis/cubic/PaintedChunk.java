package com.berg.orbis.cubic;

import java.util.Map;
import java.util.TreeMap;

import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.render.ColumnPainter;
import com.berg.orbis.worldgen.CaveCarver;
import com.berg.orbis.worldgen.WorldModel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A chunk's columns as the painter makes them, whole (top to bottom, not cut into cubes), kept as runs of the same block: what the
 * decoration of a cubic world reads instead of the cubes, so that it sees the same ground whichever cube it is made for.
 */
final class PaintedChunk {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final int minX;
    final int minZ;
    private final Column[] columns = new Column[256];

    private PaintedChunk(int minX, int minZ) {
        this.minX = minX;
        this.minZ = minZ;
    }

    /** Makes the chunk from minX, minZ as the cubes' terrain is made: painted, with ores and caves (see OrbisCubeGenerator#buildColumns). */
    static PaintedChunk paint(WorldModel model, long seed, int minX, int minZ, RegionRaster raster) {
        PaintedChunk chunk = new PaintedChunk(minX, minZ);
        Builder[] builders = new Builder[256];
        ColumnPainter.Sink sink = new ColumnPainter.Sink() {
            @Override
            public void set(int x, int y, int z, BlockState state) {
                fill(x, y, y, z, state);
            }

            @Override
            public void fill(int x, int y0, int y1, int z, BlockState state) {
                int lx = x - minX, lz = z - minZ;
                if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || y0 > y1) return;
                Builder b = builders[lx * 16 + lz];
                if (b == null) b = builders[lx * 16 + lz] = new Builder();
                b.put(y0, y1, state);
            }
        };
        CaveCarver.Grid grid = new CaveCarver.Grid() {
            @Override
            public BlockState get(int x, int y, int z) {
                int lx = x - minX, lz = z - minZ;
                if (lx < 0 || lx > 15 || lz < 0 || lz > 15) return AIR;
                Builder b = builders[lx * 16 + lz];
                return b == null ? AIR : b.get(y);
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                sink.set(x, y, z, state);
            }
        };
        OrbisCubeGenerator.buildColumns(model, seed, minX, minZ, raster, sink, grid, Integer.MIN_VALUE, Integer.MAX_VALUE);
        for (int i = 0; i < 256; i++) chunk.columns[i] = builders[i] == null ? Column.EMPTY : builders[i].build();
        return chunk;
    }

    Column column(int x, int z) {
        return this.columns[(x - this.minX) * 16 + (z - this.minZ)];
    }

    /** A column: runs of the same block from the bottom up, without the air between them. */
    static final class Column {
        static final Column EMPTY = new Column(new int[0], new int[0], new BlockState[0]);

        private final int[] from;
        private final int[] to;
        private final BlockState[] states;

        private Column(int[] from, int[] to, BlockState[] states) {
            this.from = from;
            this.to = to;
            this.states = states;
        }

        /** The run holding y, or -1 (air). */
        int run(int y) {
            int i = this.lastStartingAtOrBelow(y);
            return i >= 0 && this.to[i] >= y ? i : -1;
        }

        int from(int run) {
            return this.from[run];
        }

        BlockState state(int run) {
            return this.states[run];
        }

        BlockState get(int y) {
            int i = this.run(y);
            return i < 0 ? AIR : this.states[i];
        }

        /** The highest block that is not air, or Integer.MIN_VALUE. */
        int top() {
            return this.to.length == 0 ? Integer.MIN_VALUE : this.to[this.to.length - 1];
        }

        /** The top of the highest run below y (y itself being air), or Integer.MIN_VALUE. */
        int topBelow(int y) {
            int i = this.lastStartingAtOrBelow(y);
            return i < 0 ? Integer.MIN_VALUE : Math.min(this.to[i], y - 1);
        }

        private int lastStartingAtOrBelow(int y) {
            int lo = 0, hi = this.from.length - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (this.from[mid] <= y) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found;
        }
    }

    /** Collects the painter's writes in order (a later one wins), as runs that do not overlap. */
    private static final class Builder {
        private record Run(int from, int to, BlockState state) {}

        private final TreeMap<Integer, Run> runs = new TreeMap<>();

        void put(int y0, int y1, BlockState state) {
            Map.Entry<Integer, Run> below = this.runs.floorEntry(y0);
            if (below != null && below.getValue().to >= y0) {
                Run r = below.getValue();
                this.runs.remove(r.from);
                if (r.from < y0) this.runs.put(r.from, new Run(r.from, y0 - 1, r.state));
                if (r.to > y1) this.runs.put(y1 + 1, new Run(y1 + 1, r.to, r.state));
            }
            Map.Entry<Integer, Run> inside;
            while ((inside = this.runs.ceilingEntry(y0)) != null && inside.getKey() <= y1) {
                Run r = inside.getValue();
                this.runs.remove(r.from);
                if (r.to > y1) this.runs.put(y1 + 1, new Run(y1 + 1, r.to, r.state));
            }
            if (!state.isAir()) this.runs.put(y0, new Run(y0, y1, state));
        }

        BlockState get(int y) {
            Map.Entry<Integer, Run> run = this.runs.floorEntry(y);
            return run != null && run.getValue().to >= y ? run.getValue().state : AIR;
        }

        Column build() {
            int n = 0;
            int[] from = new int[this.runs.size()];
            int[] to = new int[this.runs.size()];
            BlockState[] states = new BlockState[this.runs.size()];
            for (Run r : this.runs.values()) {
                if (n > 0 && states[n - 1] == r.state && to[n - 1] == r.from - 1) {
                    to[n - 1] = r.to; // joins the run below
                    continue;
                }
                from[n] = r.from;
                to[n] = r.to;
                states[n] = r.state;
                n++;
            }
            return new Column(java.util.Arrays.copyOf(from, n), java.util.Arrays.copyOf(to, n), java.util.Arrays.copyOf(states, n));
        }
    }
}
