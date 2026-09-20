package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class OrbitalJammerBlockEntity extends OrbitalNodeBlockEntity {

    public static final String LABEL = "jammer";

    public OrbitalJammerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ORBITAL_JAMMER.get(), pos, state);
    }

    public boolean powered() {
        return this.level != null && this.level.hasNeighborSignal(this.worldPosition);
    }

    @Override
    protected String nodeLabel() {
        return LABEL;
    }

    @Override
    protected boolean nodeActive() {
        return powered();
    }
}
