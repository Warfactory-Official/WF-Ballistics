package com.wf.wfballistics.entity.glyphid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a glyphid can chew, priced on block hardness.
 *
 * <p>Hardness rather than explosion resistance, which is what this used to read. The two disagree in exactly
 * the cases that matter: obsidian is 50 hardness against 1,200 resistance, and a reinforced door is tough to
 * mine but barely resists a blast at all. A glyphid is biting, not detonating, so what it is up against is how
 * long the material takes to cut — and hardness is also the number a player already has an intuition for,
 * because it is the one their pickaxe answers to.
 *
 * <p>Two ways to be un-chewable, and they are different. A block <em>above the caste's ceiling</em> is one this
 * caste cannot open but a bigger one could, which is what makes a wall worth building out of the right
 * material. A block of <em>negative hardness</em> — bedrock, barriers, portal frames — is unbreakable to
 * everything, and no amount of evolution changes that.
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

    /**
     * Whether a bite stops dead here. Used by the blast-shaped dig, which takes a whole sphere at once and so
     * cares only about the ceiling and not about how long the material would have taken.
     */
    public static boolean stopsABite(BlockState state, BlockGetter level, BlockPos pos, double ceiling) {
        float hardness = state.getDestroySpeed(level, pos);
        return hardness < 0.0F || hardness > ceiling;
    }
}
