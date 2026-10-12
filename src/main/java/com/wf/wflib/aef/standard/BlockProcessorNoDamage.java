package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.interfaces.IBlockMutator;
import com.wf.wflib.aef.interfaces.IBlockProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/** Leaves every block intact; still runs the optional {@link IBlockMutator}. */
public class BlockProcessorNoDamage implements IBlockProcessor {

    protected IBlockMutator convert;

    public BlockProcessorNoDamage() {
    }

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

        affectedBlocks.clear(); // clears => standard SFX skips per-block debris particles
    }
}
