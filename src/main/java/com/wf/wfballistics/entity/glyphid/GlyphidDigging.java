package com.wf.wfballistics.entity.glyphid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a glyphid can chew, priced on block hardness.
 *
 * <p>Hardness, not explosion resistance: obsidian is 50 hardness against 1,200 resistance, and a glyphid is
 * biting rather than detonating. It is also the number a player's pickaxe answers to.
 *
 * <p>Two different ways to be un-chewable. Above the caste's <em>ceiling</em> means a bigger caste could open
 * it, which is what makes the material worth building out of; <em>negative</em> hardness — bedrock, barriers,
 * portal frames — is unbreakable to everything.
 */
public final class GlyphidDigging {

    private GlyphidDigging() {
    }

    /**
     * @return ticks this caste needs to chew the block, or -1 if it never can
     */
    public static int ticksToChew(BlockState state, BlockGetter level, BlockPos pos,
                                  GlyphidStats.StatBundle stats) {
        if (state.isAir()) {
            return -1;
        }
        return stats.ticksToChew(state.getDestroySpeed(level, pos));
    }

    public static boolean chewable(BlockState state, BlockGetter level, BlockPos pos,
                                   GlyphidStats.StatBundle stats) {
        return ticksToChew(state, level, pos, stats) > 0;
    }

    /** Whether a bite stops dead here. For the blast-shaped dig, which cares only about the ceiling. */
    public static boolean stopsABite(BlockState state, BlockGetter level, BlockPos pos, double ceiling) {
        float hardness = state.getDestroySpeed(level, pos);
        return hardness < 0.0F || hardness > ceiling;
    }
}
