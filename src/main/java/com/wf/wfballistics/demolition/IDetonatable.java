package com.wf.wfballistics.demolition;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** A block that can be set off by a {@link DetonatorItem}. */
public interface IDetonatable {

    /**
     * Set this block off.
     *
     * @param detonator the player holding the detonator, or {@code null} if fired without one.
     */
    void detonate(ServerLevel level, BlockPos pos, BlockState state, @Nullable Player detonator);

    /**
     * A recognised explosive: tagged {@code wfballistics:explosive} (data-driven membership) and actually able to
     * be detonated in code (implements this interface).
     */
    static boolean isExplosive(BlockState state) {
        return state.is(DemolitionTags.EXPLOSIVE) && state.getBlock() instanceof IDetonatable;
    }

    /**
     * Detonate the block at {@code pos} if it's a recognised explosive (see {@link #isExplosive}).
     *
     * @return true if a charge was fired.
     */
    static boolean tryDetonate(ServerLevel level, BlockPos pos, @Nullable Player cause) {
        BlockState state = level.getBlockState(pos);
        if (state.is(DemolitionTags.EXPLOSIVE) && state.getBlock() instanceof IDetonatable detonatable) {
            detonatable.detonate(level, pos, state, cause);
            return true;
        }
        return false;
    }
}
