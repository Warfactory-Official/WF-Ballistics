package com.wf.wfballistics;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

public class MissileEntity extends Projectile {
    public MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    protected void defineSynchedData() {}
}
