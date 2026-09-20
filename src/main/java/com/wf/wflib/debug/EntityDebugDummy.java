package com.wf.wflib.debug;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** A target that exists only to be hit. */
public class EntityDebugDummy extends Mob {

    /** Radius of the circle a mobile dummy walks, in blocks. */
    private float drift;
    private double homeX;
    private double homeZ;

    private int hits;
    private float damageTaken;

    public EntityDebugDummy(EntityType<? extends EntityDebugDummy> type, Level level) {
        super(type, level);
        setNoAi(true);
        setPersistenceRequired();
        setInvulnerable(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1024.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                // Nothing looks for a dummy, so follow range only has to be small enough not to matter.
                .add(Attributes.FOLLOW_RANGE, 1.0);
    }

    /** Send this dummy round a circle of {@code radius} blocks centred where it stands. */
    public void setDrift(float radius) {
        drift = radius;
        homeX = getX();
        homeZ = getZ();
    }

    @Override
    public void tick() {
        super.tick();
        if (drift <= 0.0F || level().isClientSide) {
            return;
        }
        double angle = tickCount * 0.02 + getId();
        double x = homeX + Math.cos(angle) * drift;
        double z = homeZ + Math.sin(angle) * drift;
        setPos(x, level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z), z);
    }

    public int hits() {
        return hits;
    }

    public float damageTaken() {
        return damageTaken;
    }

    public void resetCounters() {
        hits = 0;
        damageTaken = 0.0F;
    }

    /** Records the hit and shrugs it off. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, amount);
        }
        if (!level().isClientSide) {
            hits++;
            damageTaken += amount;
        }
        return true;
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected boolean isImmobile() {
        return true;
    }

    @Override
    public void travel(Vec3 travelVector) {
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        PathNavigation navigation = super.createNavigation(level);
        navigation.stop();
        return navigation;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putFloat("drift", drift);
        compound.putInt("hits", hits);
        compound.putFloat("damageTaken", damageTaken);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        drift = compound.getFloat("drift");
        homeX = getX();
        homeZ = getZ();
        hits = compound.getInt("hits");
        damageTaken = compound.getFloat("damageTaken");
    }
}
