package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.orbital.payload.LandingPads;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class LandingPadBlockEntity extends OrbitalNodeBlockEntity {

    public LandingPadBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LANDING_PAD.get(), pos, state);
    }

    @Override
    protected String nodeLabel() {
        return LandingPads.LABEL;
    }
}
