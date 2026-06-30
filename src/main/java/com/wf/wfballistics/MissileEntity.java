package com.wf.wfballistics;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public class MissileEntity extends Projectile {
    public MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);

        // Required for missile to move properly
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final EntityDataAccessor<BlockPos> TARGET_POS =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BLOCK_POS);

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            Vec3 currentPos = this.position();
            Vec3 targetPos = this.getTarget();

            Vec3 direction = targetPos.subtract(currentPos);
            double distance = direction.length();

            if (distance < 0.5) {
                this.setDeltaMovement(Vec3.ZERO);

                return;
            }

            Vec3 normalizedDirection = direction.normalize();

            double speed = 0.5;
            Vec3 velocity = normalizedDirection.scale(speed);

            this.setDeltaMovement(velocity);

            this.hasImpulse = true;
            this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());
        }
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(TARGET_POS, BlockPos.ZERO);
    }

    public Vec3 getTarget() {
        BlockPos pos = this.entityData.get(TARGET_POS);
        return new Vec3(pos.getX(), pos.getY(), pos.getZ());
    }

    public void setTarget(Vec3 target) {
        this.entityData.set(TARGET_POS, new BlockPos((int)target.x, (int)target.y, (int)target.z));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        Vec3 target = getTarget();
        tag.putDouble("TargetX", target.x);
        tag.putDouble("TargetY", target.y);
        tag.putDouble("TargetZ", target.z);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        if (tag.contains("TargetX")) {
            double x = tag.getDouble("TargetX");
            double y = tag.getDouble("TargetY");
            double z = tag.getDouble("TargetZ");
            this.setTarget(new Vec3(x, y, z));
        }
    }
}
