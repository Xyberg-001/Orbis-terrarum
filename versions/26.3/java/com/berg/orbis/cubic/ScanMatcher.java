package com.berg.orbis.cubic;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.blockscan.BlockMatcher;
import net.minecraft.world.level.blockscan.BlockStateConsumer;

/**
 * A box scan (LevelReader.findBlocksIn) block by block, for {@link DecorationLevel}: vanilla's reads chunk sections, which that level has
 * none of (sculk spreading counts the sculk around it this way).
 */
final class ScanMatcher extends BlockMatcher {
    private final BlockPos from;
    private final BlockPos to;

    ScanMatcher(LevelReader level, BlockPos from, BlockPos to) {
        super(level);
        this.from = from.immutable();
        this.to = to.immutable();
    }

    @Override
    public ScanMatcher filterState(Predicate<BlockState> predicate) {
        super.filterState(predicate);
        return this;
    }

    /** Calls the consumer for every matching block until it aborts; true if it did. */
    private boolean scan(Predicate<BlockState> predicate, BlockStateConsumer consumer) {
        for (BlockPos pos : BlockPos.betweenClosed(this.from, this.to)) {
            BlockState state = this.level.getBlockState(pos);
            if (predicate.test(state) && consumer.apply(pos, state).shouldAbort()) return true;
        }
        return false;
    }

    @Override
    public boolean atLeastMatched(int n) {
        int[] count = {0};
        return this.scan(this.statePredicate, (pos, state) -> Continuation.abortIf(++count[0] >= n));
    }

    @Override
    protected boolean anyMatched(Predicate<BlockState> predicate) {
        return this.scan(predicate, (pos, state) -> Continuation.ABORT);
    }

    @Override
    public void forEach(BiConsumer<BlockPos, BlockState> consumer) {
        this.scan(this.statePredicate, (pos, state) -> {
            consumer.accept(pos, state);
            return Continuation.CONTINUE;
        });
    }

    @Override
    public boolean forEachUntil(BlockStateConsumer consumer) {
        return this.scan(this.statePredicate, consumer);
    }
}
