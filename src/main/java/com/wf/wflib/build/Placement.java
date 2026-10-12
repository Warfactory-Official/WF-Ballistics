package com.wf.wflib.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** What a block state means for the order work gets done in, and whether it needs an order at all. */
public final class Placement {

    public static final int TIERS = 2;
    public static final int TIER_STRUCTURAL = 0;
    public static final int TIER_ATTACHMENT = 1;

    private Placement() {
    }

    /** True: building something else already creates this cell; left out of the plan entirely. */
    public static boolean derived(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return true;
        }
        return state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON);
    }

    /** Heuristic: no collision shape => attachment (torches, signs, ...), else structural. */
    public static int tier(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                    ? TIER_ATTACHMENT : TIER_STRUCTURAL;
        } catch (RuntimeException ignored) {
            return TIER_STRUCTURAL;
        }
    }

    /** Layers ascend; within a layer, structure before what hangs off it. */
    public static int buildSequence(int y, BlockState state) {
        return y * TIERS + tier(state);
    }

    /** Mirror of {@link #buildSequence}: descending from {@code topY}, attachments before structure. */
    public static int salvageSequence(int y, int topY, BlockState state) {
        return (topY - y) * TIERS + (tier(state) == TIER_ATTACHMENT ? TIER_STRUCTURAL : TIER_ATTACHMENT);
    }
}
