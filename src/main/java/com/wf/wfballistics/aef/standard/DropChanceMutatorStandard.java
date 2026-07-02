package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IDropChanceMutator;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Forces a fixed drop chance for every block, ignoring the processor's distance-based default.
 */
public class DropChanceMutatorStandard implements IDropChanceMutator {

    private final float chance;

    public DropChanceMutatorStandard(float chance) {
        this.chance = chance;
    }

    @Override
    public float mutateDropChance(ExplosionAEF explosion, BlockState state, int x, int y, int z, float chance) {
        return this.chance;
    }
}
