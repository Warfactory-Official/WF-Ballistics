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

    /**
     * How many ordering steps one layer occupies. Two: structure, then the things that hang off it.
     */
    public static final int TIERS = 2;
    public static final int TIER_STRUCTURAL = 0;
    public static final int TIER_ATTACHMENT = 1;

    private Placement() {
    }

    /**
     * @return true if this cell should be left out of the plan entirely because building something else
     *      creates it.
     */
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

    /**
     * @return which pass within its layer this state belongs in.
     */
    public static int tier(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                    ? TIER_ATTACHMENT : TIER_STRUCTURAL;
        } catch (RuntimeException ignored) {
            return TIER_STRUCTURAL;
        }
    }

    /**
     * @return the ordering key for building this state at height {@code y}, relative to the bottom of the
     *      job. Layers ascend, and within a layer the structure goes up before anything hangs off it
     */
    public static int buildSequence(int y, BlockState state) {
        return y * TIERS + tier(state);
    }

    /**
     * @return the ordering key for taking this state down, in a job whose top layer is {@code topY}.
     */
    public static int salvageSequence(int y, int topY, BlockState state) {
        return (topY - y) * TIERS + (tier(state) == TIER_ATTACHMENT ? TIER_STRUCTURAL : TIER_ATTACHMENT);
    }
}
