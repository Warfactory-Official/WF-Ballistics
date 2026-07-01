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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public class MissileEntity extends Projectile {
    public MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);

        // Required for missile to move properly
        this.noPhysics = true;
        this.setNoGravity(true);
        //Technically you can try doing with gravity... if you hate yourself o algo
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final EntityDataAccessor<BlockPos> TARGET_POS =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BLOCK_POS);

    // True while the missile is still boosting straight up out of the launch site.
    private boolean ascending = true;

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            return;
        }

        Vec3 currentPos = this.position();
        Vec3 targetPos = this.getTarget();

        double dx = targetPos.x - currentPos.x;
        double dz = targetPos.z - currentPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        double maxCruiseSpeed = 1.0;
        double ascentSpeed = 1.25;        // vertical boost speed during launch
        double terrainClearance = 24.0;   // altitude to hold above surrounding terrain
        double terrainScanRadius = 24.0;  // how far around the missile we look for terrain
        double lookAhead = 32.0;          // how far ahead (toward target) to scan while cruising
        double dampeningRange = 50.0;     // smooths cruise altitude corrections
        double brakingRange = 30.0;       // horizontal distance to target before the terminal dive
        float terminalFallVelocity = -8;  // steep attack-dive speed (larger than cruise due to "gravity")


        double scanCenterX = currentPos.x;
        double scanCenterZ = currentPos.z;
        if (!this.ascending && horizontalDist > 1.0E-3) {
            scanCenterX += (dx / horizontalDist) * lookAhead;
            scanCenterZ += (dz / horizontalDist) * lookAhead;
        }
        double safeAltitude = scanTerrainTop(scanCenterX, scanCenterZ, terrainScanRadius) + terrainClearance;

        Vec3 velocity;

        if (this.ascending && this.getY() < safeAltitude) {
            //Vertical boost
            velocity = new Vec3(0.0, ascentSpeed, 0.0);
        } else {
            this.ascending = false;

            // --- Phase 2/3: cruise toward the target, then dive on it ---
            boolean terminal = horizontalDist < brakingRange;

            double horizontalSpeed = terminal
                    ? maxCruiseSpeed * (horizontalDist / brakingRange)
                    : maxCruiseSpeed;

            double vx = 0.0;
            double vz = 0.0;
            if (horizontalDist > 1.0E-3) {
                vx = (dx / horizontalDist) * horizontalSpeed;
                vz = (dz / horizontalDist) * horizontalSpeed;
            }

            double vy;
            if (terminal) {
                // Steep terminal dive onto the target.
                double currentVy = this.getDeltaMovement().y;
                vy = Mth.lerp(0.01f, (float) currentVy, terminalFallVelocity);
            } else {
                // Terrain-following cruise: hold the safe altitude, climbing over rising terrain.
                double desiredVy = (safeAltitude - this.getY()) / dampeningRange;
                vy = Mth.clamp(desiredVy, -maxCruiseSpeed, maxCruiseSpeed);
            }

            velocity = new Vec3(vx, vy, vz);
        }

        this.setDeltaMovement(velocity);

        this.hasImpulse = true;
        this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());

        HitResult hitResult = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);

        if (hitResult.getType() != HitResult.Type.MISS) {
            this.onMissileImpact(hitResult);
        }
    }

    /**
     * Scans safe height based on mc heightmap
     */
    private double scanTerrainTop(double centerX, double centerZ, double radius) {
        int r = (int) Math.ceil(radius);
        int step = Math.max(2, r / 4);
        int cx = Mth.floor(centerX);
        int cz = Mth.floor(centerZ);

        int maxTop = this.level().getMinBuildHeight();
        for (int ox = -r; ox <= r; ox += step) {
            for (int oz = -r; oz <= r; oz += step) {
                int top = this.level().getHeight(Heightmap.Types.WORLD_SURFACE, cx + ox, cz + oz);
                if (top > maxTop) {
                    maxTop = top;
                }
            }
        }
        return maxTop;
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
        tag.putBoolean("Ascending", this.ascending);
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

        if (tag.contains("Ascending")) {
            this.ascending = tag.getBoolean("Ascending");
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
