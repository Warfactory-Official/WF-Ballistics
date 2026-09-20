package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;


public class TurretInterceptorSupersonicBlockEntity extends TurretInterceptorBlockEntity {
    public TurretInterceptorSupersonicBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TURRET_INTERCEPTOR_SUPERSONIC.get(), pos, state, "interceptor_supersonic");
    }
}
