package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.sim.IMissileListener;
import com.wf.wflib.sim.MissileListenerRegistry;
import com.wf.wflib.sim.MissileSimConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;


public class MissileListenerDebugBlockEntity extends BlockEntity implements IMissileListener {
    public MissileListenerDebugBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MISSILE_LISTENER_DEBUG.get(), pos, state);
    }

    public void serverTick() {
        if (this.level instanceof ServerLevel sl) {
            MissileListenerRegistry.get(sl).register(this.worldPosition, this);
        }
    }

    @Override
    public Vec3 listenerCenter() {
        return Vec3.atCenterOf(this.worldPosition);
    }

    @Override
    public double listenerRange() {
        return MissileSimConfig.DEBUG_LISTENER_RANGE;
    }

    @Override
    public boolean listenerValid() {
        return !this.isRemoved() && this.level instanceof ServerLevel sl && sl.isLoaded(this.worldPosition);
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            MissileListenerRegistry.get(sl).deregister(this.worldPosition);
        }
        super.setRemoved();
    }
}
