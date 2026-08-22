package com.wf.wfballistics.entity.glyphid;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.entity.MistEntity;
import com.wf.wfballistics.fluid.WFFluids;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ordnance dropped by a glyphid on the wing.
 *
 * <p>Two payloads on one entity, chosen by {@link #setExplosive}: a pool of corrosive acid, and — for the
 * castes that get there — an actual blast. They share everything but the last few lines, so splitting them
 * into two entities would duplicate the falling, the impact test and the tracking to no purpose.
 *
 * <p>Being a {@link ThrowableProjectile} rather than a free-falling entity is what makes it hit things
 * reliably: it sweeps its path each tick instead of testing where it happens to land, so a bomb released at
 * altitude and speed cannot tunnel through the roof it was aimed at.
 */
public class EntityGlyphidBomb extends ThrowableProjectile {

    private static final EntityDataAccessor<Boolean> DW_EXPLOSIVE =
            SynchedEntityData.defineId(EntityGlyphidBomb.class, EntityDataSerializers.BOOLEAN);

    /**
     * How long the acid pool lasts, in ticks.
     */
    public static final int POOL_DURATION = 400;
    /**
     * Height of the pool. Low and wide: this is something spreading over a floor, not a cloud hanging over it.
     */
    public static final float POOL_HEIGHT = 2.0F;

    public EntityGlyphidBomb(EntityType<? extends EntityGlyphidBomb> type, Level level) {
        super(type, level);
    }

    public EntityGlyphidBomb(Level level, LivingEntity thrower) {
        super(ModEntities.GLYPHID_BOMB.get(), thrower, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DW_EXPLOSIVE, false);
    }

    public boolean isExplosive() {
        return entityData.get(DW_EXPLOSIVE);
    }

    public void setExplosive(boolean explosive) {
        entityData.set(DW_EXPLOSIVE, explosive);
    }

    /**
     * Heavier than a snowball so a bomb dropped from cruise altitude falls rather than drifting.
     */
    @Override
    protected double getDefaultGravity() {
        return 0.06;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        // Bombs pass straight through the swarm that dropped them.
        if (result.getEntity() instanceof EntityGlyphid) {
            return;
        }
        super.onHitEntity(result);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return !(target instanceof EntityGlyphid) && super.canHitEntity(target);
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level().isClientSide) {
            return;
        }
        // The contact point, not the bomb's position. A bomb released from cruise altitude covers several
        // blocks per tick, so where it *was* when the hit resolved can be well above what it hit -- which puts
        // the pool in the air over the target instead of on it.
        Vec3 at = result.getLocation();
        if (isExplosive()) {
            detonate(at);
        } else {
            splash(at);
        }
        discard();
    }

    /**
     * Leave a pool of acid where it landed.
     */
    private void splash(Vec3 at) {
        float radius = WFConfig.GLYPHID_ACID_RADIUS.get().floatValue();
        MistEntity.spawn(level(), WFFluids.GLYPHID_ACID.get(),
                at.x, at.y, at.z, radius, POOL_HEIGHT, POOL_DURATION);
    }

    /**
     * The heavier payload, run through the same explosion framework as everything else that goes off in this
     * mod rather than through vanilla's.
     */
    private void detonate(Vec3 at) {
        int size = WFConfig.GLYPHID_BOMB_BLAST.get();
        ExplosionAEF blast = new ExplosionAEF(level(), at.x, at.y, at.z, size, getOwner());
        blast.setBlockAllocator(new BlockAllocatorStandard());
        blast.setBlockProcessor(new BlockProcessorStandard());
        blast.explode();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putBoolean("explosive", isExplosive());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        setExplosive(compound.getBoolean("explosive"));
    }
}
