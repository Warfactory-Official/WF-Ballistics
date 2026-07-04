package com.wf.wfballistics.entity;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.aef.nuke.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class EntityNukeExplosionMK5 extends EntityExplosionChunkLoading {
    public static final EntityDataAccessor<Integer> EXPLODE_STRENGTH = SynchedEntityData.defineId(EntityNukeExplosionMK5.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Integer> EXPLODE_RADIUS = SynchedEntityData.defineId(EntityNukeExplosionMK5.class, EntityDataSerializers.INT);
    // How fast the blast front propagates outward (ray points processed per tick).
    public static final EntityDataAccessor<Integer> RADIATION_SPEED = SynchedEntityData.defineId(EntityNukeExplosionMK5.class, EntityDataSerializers.INT);
    private static final int default_explode_strength = 100;
    // The blast algorithm that does the actual destruction.
    IExplosionRay explosion;

    public EntityNukeExplosionMK5(EntityType<?> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    public EntityNukeExplosionMK5(Level pLevel) {
        super(ModEntities.NUKE_EXPLOSION.get(), pLevel);
    }

    public EntityNukeExplosionMK5(Level pLevel, Vec3 location, int strength, int radius, int speed) {
        super(ModEntities.NUKE_EXPLOSION.get(), pLevel);
        setStrength(strength);
        setRadius(radius);
        setSpeed(speed);
        this.setPos(location);
    }

    public static EntityNukeExplosionMK5 statFac(Level level, int r, Vec3 location) {
        r = r == 0 ? 25 : 2 * r;
        int strength = r;
        int speed = (int) Math.ceil((double) 10_0000 / strength);
        int radius = strength / 2;
        return new EntityNukeExplosionMK5(level, location, strength, radius, speed);
    }

    @Override
    public void tick() {
        super.tick();
        if (getStrength() == 0) {
            this.clearChunkLoader();
            this.discard();
            return;
        }
        if (!this.level().isClientSide) {
            loadChunk((int) Math.floor(position().x / 16D), (int) Math.floor(position().z / 16D));
            int damageInterval = getDamageInterval();
            if (tickCount % damageInterval == 0) {
                ExplosionNukeGeneric.dealDamage(level(), position(), getRadius());
            }

            if (explosion == null) {
                if (NukeConfig.explosionAlgorithm == 0) {
                    explosion = new ExplosionNukeRayBatched(level(), blockPosition(),
                            getStrength(), getSpeed(), getRadius());
                } else {
                    explosion = new ExplosionNukeRayParallelized((ServerLevel) level(), blockPosition(),
                            getStrength(), getRadius());
                }
            }
            if (!explosion.isComplete()) {
                int tickBudget = Math.max(1, NukeConfig.mk5BlastTime);
                int cacheBudget = Math.max(1, tickBudget / 2);
                int destructionBudget = Math.max(1, tickBudget - cacheBudget);
                explosion.cacheChunksTick(cacheBudget);
                explosion.destructionTick(destructionBudget);
            } else {
                this.clearChunkLoader();
                this.discard();
            }
        }
    }

    private int getDamageInterval() {
        int radius = getRadius();
        if (radius >= 384) return 8;
        if (radius >= 256) return 6;
        if (radius >= 160) return 4;
        if (radius >= 96) return 2;
        return 1;
    }

    @Override
    public void remove(RemovalReason pReason) {
        if (explosion != null) explosion.cancel();
        super.remove(pReason);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(EXPLODE_STRENGTH, default_explode_strength);
        this.entityData.define(EXPLODE_RADIUS, 100);
        this.entityData.define(RADIATION_SPEED, 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag pCompound) {
        this.setStrength(pCompound.getInt("strength"));
        this.setRadius(pCompound.getInt("radius"));
        this.setSpeed(pCompound.getInt("speed"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag pCompound) {
        pCompound.putInt("strength", getStrength());
        pCompound.putInt("radius", getRadius());
        pCompound.putInt("speed", getSpeed());
    }

    protected int getStrength() {
        return this.entityData.get(EXPLODE_STRENGTH);
    }

    protected void setStrength(int strength) {
        this.entityData.set(EXPLODE_STRENGTH, strength);
    }

    protected int getRadius() {
        return this.entityData.get(EXPLODE_RADIUS);
    }

    protected void setRadius(int radius) {
        this.entityData.set(EXPLODE_RADIUS, radius);
    }

    protected int getSpeed() {
        return this.entityData.get(RADIATION_SPEED);
    }

    protected void setSpeed(int speed) {
        this.entityData.set(RADIATION_SPEED, speed);
    }
}
