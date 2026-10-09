package com.berg.orbis.cubic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import io.github.opencubicchunks.cubicchunks.api.CubicApi;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * The level a chunk's decoration is made in for a cubic world: it reads the painted columns ({@link PaintedChunk}) with the decoration's
 * own writes over them, and keeps those writes (blocks, block entities, entities) instead of putting them anywhere, so that every cube can
 * take its share of the same decoration ({@link ChunkDecoration}). Like vanilla's region it writes up to a chunk away from its own.
 * <p>
 * It is a {@link WorldGenRegion} for the decoration code's sake (some of it asks for one), made with an empty chunk cache; everything that
 * would read the cache is answered here.
 */
final class DecorationLevel extends WorldGenRegion {
    interface Columns {
        PaintedChunk painted(int chunkX, int chunkZ);
    }

    private final ServerLevel serverLevel;
    private final Columns columns;
    private final int chunkX;
    private final int chunkZ;
    private final Placeholder placeholder;
    private final int minY;
    private final int height;
    private final Long2ObjectOpenHashMap<PaintedChunk> painted = new Long2ObjectOpenHashMap<>();
    /** The writes by column (x, z packed as a block position at Y 0), by Y. */
    private final Long2ObjectOpenHashMap<TreeMap<Integer, BlockState>> writes = new Long2ObjectOpenHashMap<>();
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Set<BlockPos> postProcessing = new HashSet<>();
    private final List<ScheduledTick<Block>> blockTicks = new ArrayList<>();
    private final List<ScheduledTick<Fluid>> fluidTicks = new ArrayList<>();
    private final LevelTickAccess<Block> blockTickAccess = recorder(this.blockTicks);
    private final LevelTickAccess<Fluid> fluidTickAccess = recorder(this.fluidTicks);

    DecorationLevel(ServerLevel level, int chunkX, int chunkZ, Columns columns) {
        this(level, chunkX, chunkZ, columns, placeholder(level, chunkX, chunkZ));
    }

    private DecorationLevel(ServerLevel level, int chunkX, int chunkZ, Columns columns, Placeholder placeholder) {
        // the empty step has no dependencies, so the region never looks into its cache
        super(level, StaticCache2D.create(chunkX, chunkZ, 0, (x, z) -> null), ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.EMPTY),
                placeholder);
        this.serverLevel = level;
        this.columns = columns;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.placeholder = placeholder;
        placeholder.level = this;
        this.minY = CubicApi.minY(level);
        this.height = CubicApi.maxY(level) - this.minY + 1;
    }

    private static Placeholder placeholder(ServerLevel level, int chunkX, int chunkZ) {
        return new Placeholder(new ChunkPos(chunkX, chunkZ), level);
    }

    /**
     * A one-section chunk standing in for the region's centre, and for any chunk a feature asks the region for (vanilla's vines and sculk
     * mark blocks for post-processing through their chunk): its blocks and marks are the region's.
     */
    private static final class Placeholder extends ProtoChunk {
        DecorationLevel level;

        Placeholder(ChunkPos pos, ServerLevel serverLevel) {
            super(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(0, 16), serverLevel.palettedContainerFactory(), null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.level == null ? super.getBlockState(pos) : this.level.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.level == null ? super.getFluidState(pos) : this.level.getFluidState(pos);
        }

        @Override
        public BlockState setBlockState(BlockPos pos, BlockState state, @Block.UpdateFlags int flags) {
            BlockState old = this.getBlockState(pos);
            if (this.level != null) this.level.setBlock(pos, state, flags);
            return old;
        }

        @Override
        public void markPosForPostProcessing(BlockPos pos) {
            if (this.level != null) this.level.postProcessing.add(pos.immutable());
        }
    }

    int chunkX() {
        return this.chunkX;
    }

    int chunkZ() {
        return this.chunkZ;
    }

    Map<Long, TreeMap<Integer, BlockState>> writes() {
        return this.writes;
    }

    Map<BlockPos, BlockEntity> blockEntities() {
        return this.blockEntities;
    }

    List<Entity> entities() {
        return this.entities;
    }

    Set<BlockPos> postProcessing() {
        return this.postProcessing;
    }

    List<ScheduledTick<Block>> blockTicks() {
        return this.blockTicks;
    }

    List<ScheduledTick<Fluid>> fluidTicks() {
        return this.fluidTicks;
    }

    /** Keeps the ticks scheduled (springs, falling blocks) for the cubes they fall in. */
    private static <T> LevelTickAccess<T> recorder(List<ScheduledTick<T>> ticks) {
        return new LevelTickAccess<>() {
            @Override
            public boolean willTickThisTick(BlockPos pos, T type) {
                return false;
            }

            @Override
            public void schedule(ScheduledTick<T> tick) {
                ticks.add(tick);
            }

            @Override
            public boolean hasScheduledTick(BlockPos pos, T type) {
                for (ScheduledTick<T> tick : ticks) {
                    if (tick.type() == type && tick.pos().equals(pos)) return true;
                }
                return false;
            }

            @Override
            public int count() {
                return ticks.size();
            }
        };
    }

    PaintedChunk.Column paintedColumn(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        long key = ChunkPos.pack(cx, cz);
        PaintedChunk chunk = this.painted.get(key);
        if (chunk == null) {
            chunk = this.columns.painted(cx, cz);
            this.painted.put(key, chunk);
        }
        return chunk.column(x, z);
    }

    private static long column(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private boolean nearby(int x, int z) {
        return Math.abs((x >> 4) - this.chunkX) <= 1 && Math.abs((z >> 4) - this.chunkZ) <= 1;
    }

    /** The cubic world's heights, not its dimension's (heightmaps reach down to the deepest sea bed). */
    @Override
    public int getMinY() {
        return this.minY;
    }

    @Override
    public int getHeight() {
        return this.height;
    }

    // ---- reads --------------------------------------------------------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        TreeMap<Integer, BlockState> written = this.writes.get(column(pos.getX(), pos.getZ()));
        if (written != null) {
            BlockState state = written.get(pos.getY());
            if (state != null) return state;
        }
        return this.paintedColumn(pos.getX(), pos.getZ()).get(pos.getY());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.blockEntities.get(pos);
    }

    /** The heightmap of the painted columns and the writes: one above the highest block the heightmap counts. */
    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        Predicate<BlockState> counts = type.isOpaque();
        PaintedChunk.Column painted = this.paintedColumn(x, z);
        TreeMap<Integer, BlockState> written = this.writes.get(column(x, z));
        int y = painted.top();
        if (written != null && !written.isEmpty()) y = Math.max(y, written.lastKey());
        int floor = this.getMinY();
        while (y >= floor) {
            BlockState own = written == null ? null : written.get(y);
            if (own != null) {
                if (counts.test(own)) return y + 1;
                y--;
                continue;
            }
            // through the painted column to the next run or write below (air never counts for a heightmap)
            Integer lowerWrite = written == null ? null : written.lowerKey(y);
            int lower = lowerWrite == null ? Integer.MIN_VALUE : lowerWrite;
            int run = painted.run(y);
            if (run >= 0) {
                if (counts.test(painted.state(run))) return y + 1;
                y = Math.max(painted.from(run) - 1, lower);
            } else {
                y = Math.max(painted.topBelow(y), lower);
            }
        }
        return floor;
    }

    @Override
    public net.minecraft.world.level.blockscan.BlockMatcher findBlocksIn(BlockPos from, BlockPos to) {
        return new ScanMatcher(this, from, to);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
        return this.getUncachedNoiseBiome(quartX, quartY, quartZ);
    }

    @Override
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean loadOrGenerate) {
        if (status == ChunkStatus.STRUCTURE_STARTS || status == ChunkStatus.STRUCTURE_REFERENCES) {
            // structure starts and references: the level's columns hold them (only read here)
            ChunkAccess column = CubicApi.column(this.serverLevel, chunkX, chunkZ, status);
            if (column != null) return column;
            if (!missingColumnWarned) {
                missingColumnWarned = true;
                System.err.println("[orbis] Column " + chunkX + "," + chunkZ + " has no structure data yet while decorating chunk " + this.chunkX
                        + "," + this.chunkZ + " (cubic); its structures are left out there");
            }
        }
        // there are no chunks; a caller that insists gets the stand-in, which passes blocks on to this region
        return loadOrGenerate ? this.placeholder : null;
    }

    private static volatile boolean missingColumnWarned;

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return Math.abs(chunkX - this.chunkX) <= 1 && Math.abs(chunkZ - this.chunkZ) <= 1;
    }

    // ---- writes -------------------------------------------------------------------

    @Override
    public boolean isWithinWriteZone(BlockPos pos) {
        return this.nearby(pos.getX(), pos.getZ());
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        return this.nearby(pos.getX(), pos.getZ());
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, @Block.UpdateFlags int updateFlags, int updateLimit) {
        if (!this.ensureCanWrite(pos)) return false;
        BlockPos at = pos.immutable();
        this.writes.computeIfAbsent(column(at.getX(), at.getZ()), k -> new TreeMap<>()).put(at.getY(), state);
        if (state.hasBlockEntity()) {
            BlockEntity blockEntity = ((EntityBlock) state.getBlock()).newBlockEntity(at, state);
            if (blockEntity != null) {
                this.blockEntities.put(at, blockEntity);
            } else {
                this.blockEntities.remove(at);
            }
        } else {
            this.blockEntities.remove(at);
        }
        if ((updateFlags & Block.UPDATE_KNOWN_SHAPE) == 0) {
            BlockPos postProcess = state.getPostProcessPos(this, at);
            if (postProcess != null) this.postProcessing.add(postProcess.immutable());
        }
        return true;
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        this.entities.add(entity);
        return true;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return this.blockTickAccess;
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return this.fluidTickAccess;
    }

    ServerLevel serverLevel() {
        return this.serverLevel;
    }
}
