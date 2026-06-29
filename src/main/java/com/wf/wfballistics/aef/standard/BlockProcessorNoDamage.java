package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IBlockMutator;
import com.wf.wfballistics.aef.interfaces.IBlockProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * A non-destructive processor: it leaves every block intact but still runs an optional {@link IBlockMutator}
 * over the allocated area. Use it for shockwaves that should scorch/ignite the surface without cratering it.
 *
 * <p>It clears {@code affectedBlocks} on the way out, which doubles as the signal that tells
 * {@link ExplosionEffectStandard} to skip its per-block debris particles (no blocks were broken, so no
 * debris should fly).
 */
public class BlockProcessorNoDamage implements IBlockProcessor {

    protected IBlockMutator convert;

    public BlockProcessorNoDamage() { }

    public BlockProcessorNoDamage withBlockEffect(IBlockMutator convert) {
        this.convert = convert;
        return this;
    }

    @Override
    public void process(ExplosionAEF explosion, Level level, double x, double y, double z, Set<BlockPos> affectedBlocks) {
        if (convert != null) {
            for (BlockPos pos : affectedBlocks) {
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    convert.mutatePre(explosion, state, pos);
                }
            }
            for (BlockPos pos : affectedBlocks) {
                if (level.getBlockState(pos).isAir()) {
                    convert.mutatePost(explosion, pos);
                }
            }
        }

        affectedBlocks.clear(); // suppresses the standard SFX's block-debris particles
    }
}
