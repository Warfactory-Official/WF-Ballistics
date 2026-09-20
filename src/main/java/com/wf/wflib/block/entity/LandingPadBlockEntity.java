package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.orbital.payload.LandingPads;
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
