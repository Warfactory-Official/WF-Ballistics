package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Normal interceptor battery: fires the {@code "interceptor"} preset at any hostile missile (good against
 * ordinary missiles; usually misses supersonic ones).
 */
public class TurretInterceptorNormalBlockEntity extends TurretInterceptorBlockEntity {
    public TurretInterceptorNormalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TURRET_INTERCEPTOR.get(), pos, state, "interceptor");
    }
}
