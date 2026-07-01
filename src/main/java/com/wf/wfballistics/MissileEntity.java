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
    public static final Detonation STANDARD = (missile, pos) -> {
        var expl = new ExplosionAEF(missile.level(), pos.x, pos.y, pos.z, 250);
        expl.makeStandard();
        expl.explode();

        EntityNukeTorex torex = ModEntities.NUKE_TOREX.get().create(missile.level());
        if (torex != null) {
            torex.moveTo(pos.x, pos.y, pos.z);
            missile.level().addFreshEntity(torex);
        }
    };
    //Why is this nophono here
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EntityDataAccessor<BlockPos> TARGET_POS =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BLOCK_POS);

    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    private static final double TURN_AGILITY = 1.0; // radians * (model units) per tick
    private Phase phase = Phase.ASCEND;
    private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
    private double cruiseAltitude = 200.0;
    private double terrainClearance = 24.0;
    // Airburst fuze: while diving, detonate in the air once the missile is within this many
    // blocks (Y difference) above the target. 0 disables it, giving a contact/ground detonation.
    private float explosionOffset = 0.0f;
    private Detonation detonation = STANDARD;
    // Max change in velocity direction per tick, in radians. Default scales from the model's length
    // (longer airframe = less nimble); overridable via the Builder.
    private double maxTurnRate = TURN_AGILITY / MissileModels.length(MissileModels.DEFAULT);

    public MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);

        // Required for missile to move properly
        this.noPhysics = true;
        this.setNoGravity(true);
        //Technically you can try doing with gravity... if you hate yourself o algo
    }

    public static Builder builder(EntityType<? extends Projectile> type, Level level) {
        return new Builder(type, level);
    }

    private static Vec3 constrainTurn(Vec3 current, Vec3 desired, double maxTurnRate) {
        double desiredSpeed = desired.length();
        if (desiredSpeed < 1.0E-6 || current.lengthSqr() < 1.0E-6 || maxTurnRate >= Math.PI) {
            return desired;
        }

        Vec3 curDir = current.normalize();
        Vec3 desDir = desired.scale(1.0 / desiredSpeed);

        double angle = Math.acos(Mth.clamp(curDir.dot(desDir), -1.0, 1.0));
        if (angle <= maxTurnRate) {
            return desDir.scale(desiredSpeed);
        }

        Vec3 axis = curDir.cross(desDir);
        if (axis.lengthSqr() < 1.0E-12) {
            Vec3 reference = Math.abs(curDir.y) < 0.99 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
            axis = curDir.cross(reference);
        }
        axis = axis.normalize();

        Vec3 newDir = curDir.scale(Math.cos(maxTurnRate))
                .add(axis.cross(curDir).scale(Math.sin(maxTurnRate)));
        return newDir.normalize().scale(desiredSpeed);
    }

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
        double terrainScanRadius = 24.0;  // how far around the missile we look for terrain
        double lookAhead = 32.0;          // how far ahead (toward target) to scan while cruising
        double dampeningRange = 50.0;     // smooths cruise altitude corrections
        double brakingRange = 30.0;       // horizontal distance to target before the terminal dive
        float terminalFallVelocity = -8;  // steep attack-dive speed (larger than cruise due to "gravity")


        double safeAltitude;
        if (this.cruiseMode == CruiseMode.HIGH_ALTITUDE) {
            // Fixed high-altitude
            safeAltitude = this.cruiseAltitude;
        } else {
            // Terrain-following
            double scanCenterX = currentPos.x;
            double scanCenterZ = currentPos.z;
            if (this.phase != Phase.ASCEND && horizontalDist > 1.0E-3) {
                scanCenterX += (dx / horizontalDist) * lookAhead;
                scanCenterZ += (dz / horizontalDist) * lookAhead;
            }
            safeAltitude = scanTerrainTop(scanCenterX, scanCenterZ, terrainScanRadius) + this.terrainClearance;
        }

        //State change rules
        switch (this.phase) {
            case ASCEND -> {
                if (this.getY() >= safeAltitude) {
                    this.phase = Phase.CRUISE;
                }
            }
            case CRUISE -> {
                if (horizontalDist < brakingRange) {
                    this.phase = Phase.ATTACK;
                }
            }
            case ATTACK -> {
                //no exit, the flight ends on impact.
            }
        }

        double nx = 0.0;
        double nz = 0.0;
        if (horizontalDist > 1.0E-3) {
            nx = dx / horizontalDist;
            nz = dz / horizontalDist;
        }

        //State behavior
        Vec3 velocity = Vec3.ZERO;
        switch (this.phase) {
            case ASCEND -> velocity = new Vec3(0.0, ascentSpeed, 0.0);
            case CRUISE -> {
                double desiredVy = (safeAltitude - this.getY()) / dampeningRange;
                double vy = Mth.clamp(desiredVy, -maxCruiseSpeed, maxCruiseSpeed);
                velocity = new Vec3(nx * maxCruiseSpeed, vy, nz * maxCruiseSpeed);
            }
            case ATTACK -> {
                double horizontalSpeed = maxCruiseSpeed * (horizontalDist / brakingRange);
                double currentVy = this.getDeltaMovement().y;
                double vy = Mth.lerp(0.01f, (float) currentVy, terminalFallVelocity);
                velocity = new Vec3(nx * horizontalSpeed, vy, nz * horizontalSpeed);
            }
        }

        // Limit how far the heading can swing this tick so the missile arcs instead of snapping direction.
        velocity = constrainTurn(this.getDeltaMovement(), velocity, this.maxTurnRate);

        this.setDeltaMovement(velocity);

        this.hasImpulse = true;
        this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());

        // Airburst fuze
        if (this.explosionOffset > 0.0f && this.phase == Phase.ATTACK
                && this.getY() - targetPos.y <= this.explosionOffset) {
            this.detonate(this.position());
            return;
        }

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
        this.entityData.define(MODEL_ID, MissileModels.DEFAULT);
    }

    public String getModelId() {
        return this.entityData.get(MODEL_ID);
    }

    public void setModelId(String id) {
        this.entityData.set(MODEL_ID, MissileModels.exists(id) ? id : MissileModels.DEFAULT);
    }

    public Vec3 getTarget() {
        BlockPos pos = this.entityData.get(TARGET_POS);
        return new Vec3(pos.getX(), pos.getY(), pos.getZ());
    }

    public void setTarget(Vec3 target) {
        this.entityData.set(TARGET_POS, new BlockPos((int) target.x, (int) target.y, (int) target.z));
    }

    public Phase getPhase() {
        return this.phase;
    }

    public CruiseMode getCruiseMode() {
        return this.cruiseMode;
    }

    public float getExplosionOffset() {
        return this.explosionOffset;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        Vec3 target = getTarget();
        tag.putDouble("TargetX", target.x);
        tag.putDouble("TargetY", target.y);
        tag.putDouble("TargetZ", target.z);
        tag.putString("Phase", this.phase.name());
        tag.putString("CruiseMode", this.cruiseMode.name());
        tag.putDouble("CruiseAltitude", this.cruiseAltitude);
        tag.putDouble("TerrainClearance", this.terrainClearance);
        tag.putFloat("ExplosionOffset", this.explosionOffset);
        tag.putDouble("MaxTurnRate", this.maxTurnRate);
        tag.putString("ModelId", this.getModelId());
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

        if (tag.contains("Phase")) {
            try {
                this.phase = Phase.valueOf(tag.getString("Phase"));
            } catch (IllegalArgumentException nignored) {
            }
        }

        if (tag.contains("CruiseMode")) {
            try {
                this.cruiseMode = CruiseMode.valueOf(tag.getString("CruiseMode"));
            } catch (IllegalArgumentException nignored) {
            }
        }

        if (tag.contains("CruiseAltitude")) {
            this.cruiseAltitude = tag.getDouble("CruiseAltitude");
        }

        if (tag.contains("TerrainClearance")) {
            this.terrainClearance = tag.getDouble("TerrainClearance");
        }

        if (tag.contains("ExplosionOffset")) {
            this.explosionOffset = tag.getFloat("ExplosionOffset");
        }

        if (tag.contains("MaxTurnRate")) {
            this.maxTurnRate = tag.getDouble("MaxTurnRate");
        }

        if (tag.contains("ModelId")) {
            this.setModelId(tag.getString("ModelId"));
        }
    }

    private void onMissileImpact(HitResult hitResult) {
        this.detonate(hitResult.getLocation());
    }

    /**
     * Fires the configured warhead at the given position and removes the missile.
     */
    private void detonate(Vec3 pos) {
        this.detonation.detonate(this, pos);
        this.discard();
    }

    public enum Phase {
        ASCEND,  // boost straight up until clear of surrounding terrain
        CRUISE,  // fly toward the target while holding a terrain-safe altitude
        ATTACK   // steep terminal dive onto the target
    }

    public enum CruiseMode {
        TERRAIN_FOLLOW,
        HIGH_ALTITUDE
    }

    @FunctionalInterface
    public interface Detonation {
        void detonate(MissileEntity missile, Vec3 pos);
    }

    /**
     * Example use:
     * <pre>{@code
     * MissileEntity m = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
     *         .target(pos)
     *         .highAltitude(250.0)   // or .terrainFollow(24.0)
     *         .explosionOffset(30f)  // airburst 30 blocks above the target
     *         .build();
     * level.addFreshEntity(m);
     * }</pre>
     */
    public static final class Builder {
        private final EntityType<? extends Projectile> type;
        private final Level level;

        private Vec3 target;
        private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
        private double cruiseAltitude = 200.0;
        private double terrainClearance = 24.0;
        private float explosionOffset = 0.0f;
        private Detonation detonation = STANDARD;
        private Double maxTurnRate = null; // null = keep the model-size default
        private String modelId = MissileModels.DEFAULT;

        private Builder(EntityType<? extends Projectile> type, Level level) {
            this.type = type;
            this.level = level;
        }

        public Builder target(Vec3 target) {
            this.target = target;
            return this;
        }

        /**
         * Fly at a fixed altitude, ignoring terrain.
         */
        public Builder highAltitude(double cruiseAltitude) {
            this.cruiseMode = CruiseMode.HIGH_ALTITUDE;
            this.cruiseAltitude = cruiseAltitude;
            return this;
        }

        /**
         * Hug the ground, holding {@code terrainClearance} blocks above nearby terrain.
         */
        public Builder terrainFollow(double terrainClearance) {
            this.cruiseMode = CruiseMode.TERRAIN_FOLLOW;
            this.terrainClearance = terrainClearance;
            return this;
        }

        /**
         * Airburst {@code offset} blocks above the target; 0 (default) is a contact detonation.
         */
        public Builder explosionOffset(float offset) {
            this.explosionOffset = offset;
            return this;
        }

        // Swap the warhead behavior, defaults to {@link #STANDARD}.
        public Builder detonation(Detonation detonation) {
            this.detonation = detonation;
            return this;
        }

        /**
         * Override the max heading change per tick (radians); default scales from the model's length.
         */
        public Builder turnRate(double radiansPerTick) {
            this.maxTurnRate = radiansPerTick;
            return this;
        }

        /**
         * Pick which missile model/skin to render and fly as (see {@link MissileModels}).
         */
        public Builder model(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public MissileEntity build() {
            MissileEntity missile = new MissileEntity(this.type, this.level);
            missile.cruiseMode = this.cruiseMode;
            missile.cruiseAltitude = this.cruiseAltitude;
            missile.terrainClearance = this.terrainClearance;
            missile.explosionOffset = this.explosionOffset;
            missile.detonation = this.detonation;
            missile.setModelId(this.modelId);
            // Default the turn rate off the chosen model's length unless explicitly overridden.
            missile.maxTurnRate = (this.maxTurnRate != null)
                    ? this.maxTurnRate
                    : TURN_AGILITY / MissileModels.length(missile.getModelId());
            if (this.target != null) {
                missile.setTarget(this.target);
            }
            return missile;
        }
    }
}
