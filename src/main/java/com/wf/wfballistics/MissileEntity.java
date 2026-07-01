package com.wf.wfballistics;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.entity.EntityNukeTorex;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
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
            double dx = direction.x;
            double dz = direction.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            if (horizontalDist < 0.5) {
                this.setDeltaMovement(Vec3.ZERO);

                return;
            }

            double maxCruiseSpeed = 1;

            double cruiseAltitude = 64;
            double dampeningRange = 50.0;

            double distanceToAltitude = cruiseAltitude - this.getY();
            double desiredVy = distanceToAltitude / dampeningRange;

            // The maximum x,z-distance before the missile begins descending and slowing down its horizontal speed
            double brakingRange = 30.0;
            double horizontalSpeed = maxCruiseSpeed;

            if (horizontalDist < brakingRange) {
                horizontalSpeed = maxCruiseSpeed * (horizontalDist / brakingRange);
            }

            double vx = (dx / horizontalDist) * horizontalSpeed;
            double vz = (dz / horizontalDist) * horizontalSpeed;

            double vy;

            // Should be larger than cruise velocity due to the influence of gravity
            float terminalFallVelocity = -8;

            if (horizontalDist < brakingRange) {
                // Steep decline for attack
                double currentVy = this.getDeltaMovement().y;
                vy = Mth.lerp(0.01f, (float) currentVy, terminalFallVelocity);
            } else {
                vy = Mth.clamp(desiredVy, -maxCruiseSpeed, maxCruiseSpeed);
            }

            Vec3 velocity = new Vec3(vx, vy, vz);

            this.setDeltaMovement(velocity);

            this.hasImpulse = true;
            this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());

            HitResult hitResult = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);

            if (hitResult.getType() != HitResult.Type.MISS) {
                this.onMissileImpact(hitResult);
            }
        }
    }

    protected boolean canHitEntity(Entity target) {
        return !target.isSpectator() && target.isAlive() && target.isPickable();
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

    private void onMissileImpact(HitResult hitResult) {
        Vec3 hitPos = hitResult.getLocation();

        var expl = new ExplosionAEF(this.level(), hitPos.x, hitPos.y, hitPos.z, 250);
        expl.makeStandard();
        expl.explode();

        EntityNukeTorex torex = ModEntities.NUKE_TOREX.get().create(this.level());
        if (torex != null) {
            torex.moveTo(hitPos.x, hitPos.y, hitPos.z);
            this.level().addFreshEntity(torex);
        }

        this.discard();
    }
}
