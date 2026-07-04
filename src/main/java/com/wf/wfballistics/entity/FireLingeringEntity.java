package com.wf.wfballistics.entity;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.client.fx.FireClientFX;
import com.wf.wfballistics.fire.FireType;
import com.wf.wfballistics.fire.WFFire;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class FireLingeringEntity extends Entity {

    public static final int TYPE_DIESEL = 0;
    public static final int TYPE_PHOSPHORUS = 1;

    private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(FireLingeringEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(FireLingeringEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> TYPE = SynchedEntityData.defineId(FireLingeringEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> BOX_X = SynchedEntityData.defineId(FireLingeringEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOX_Y = SynchedEntityData.defineId(FireLingeringEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOX_Z = SynchedEntityData.defineId(FireLingeringEntity.class, EntityDataSerializers.FLOAT);

    private int maxAge = 150;

    public FireLingeringEntity(EntityType<? extends FireLingeringEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static FireLingeringEntity spawn(Level level, double x, double y, double z,
                                            float width, float height, int duration, int type) {
        FireLingeringEntity fire = new FireLingeringEntity(ModEntities.FIRE_LINGERING.get(), level);
        fire.setPos(x, y, z);
        fire.setArea(width, height);
        fire.setDuration(duration);
        fire.setType(type);
        level.addFreshEntity(fire);
        return fire;
    }

    public static FireLingeringEntity spawnBox(Level level, double minX, double minY, double minZ,
                                               float sizeX, float sizeY, float sizeZ, int duration, int type) {
        FireLingeringEntity fire = new FireLingeringEntity(ModEntities.FIRE_LINGERING.get(), level);
        fire.setDuration(duration);
        fire.setType(type);
        fire.setPos(minX, minY, minZ);
        fire.setBox(sizeX, sizeY, sizeZ);
        level.addFreshEntity(fire);
        return fire;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(TYPE, 0);
        this.entityData.define(WIDTH, 1F);
        this.entityData.define(HEIGHT, 1F);
        this.entityData.define(BOX_X, 0F);
        this.entityData.define(BOX_Y, 0F);
        this.entityData.define(BOX_Z, 0F);
    }

    public boolean isBox() {
        return this.entityData.get(BOX_X) > 0F;
    }

    public FireLingeringEntity setArea(float width, float height) {
        this.entityData.set(WIDTH, width);
        this.entityData.set(HEIGHT, height);
        refreshDimensions();
        return this;
    }

    public FireLingeringEntity setBox(float sizeX, float sizeY, float sizeZ) {
        this.entityData.set(BOX_X, sizeX);
        this.entityData.set(BOX_Y, sizeY);
        this.entityData.set(BOX_Z, sizeZ);
        this.setBoundingBox(this.makeBoundingBox());
        return this;
    }

    public FireLingeringEntity setDuration(int duration) {
        this.maxAge = duration;
        return this;
    }

    public FireLingeringEntity setType(int type) {
        this.entityData.set(TYPE, type);
        return this;
    }

    public int getVariant() {
        return this.entityData.get(TYPE);
    }

    @Override
    protected @NotNull AABB makeBoundingBox() {
        float bx = this.entityData.get(BOX_X);
        if (bx > 0F) {
            return new AABB(getX(), getY(), getZ(),
                    getX() + bx, getY() + this.entityData.get(BOX_Y), getZ() + this.entityData.get(BOX_Z));
        }
        return super.makeBoundingBox();
    }

    @Override
    public void tick() {
        super.tick();

        if (!level().isClientSide) {
            if (getVariant() == TYPE_DIESEL && this.isInWater()) {
                this.kill(); //Only phosphorus lingers in water
            }
            if (this.tickCount >= this.maxAge) {
                discard();
                return;
            }
            List<Entity> affected = level().getEntities(this, getBoundingBox(),
                    e -> !(e instanceof FireLingeringEntity));
            for (Entity e : affected) {
                if (e instanceof LivingEntity living) {
                    applyFire(living);
                } else {
                    e.setSecondsOnFire(4);
                }
            }
        } else {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FireClientFX.tick(this));
        }
    }

    private void applyFire(LivingEntity living) {
        if (getVariant() == TYPE_PHOSPHORUS) {
            WFFire.ignite(living, FireType.PHOSPHORUS, 300);
        } else {
            WFFire.ignite(living, FireType.NORMAL, 60);
        }
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        try {
            float bx = this.entityData.get(BOX_X);
            if (bx > 0F) {
                float by = this.entityData.get(BOX_Y);
                float bz = this.entityData.get(BOX_Z);
                return EntityDimensions.scalable(Math.max(Math.max(bx, bz), 0.2F), Math.max(by, 0.2F));
            }
            float width = this.entityData.get(WIDTH);
            float height = this.entityData.get(HEIGHT);
            return EntityDimensions.scalable(Math.max(width, 0.2F), Math.max(height, 0.2F));
        } catch (Exception ignored) {
            return EntityDimensions.scalable(1F, 1F);
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (BOX_X.equals(key) || BOX_Y.equals(key) || BOX_Z.equals(key)) {
            this.setBoundingBox(this.makeBoundingBox());
        } else if (WIDTH.equals(key) || HEIGHT.equals(key)) {
            refreshDimensions();
        }
        super.onSyncedDataUpdated(key);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
