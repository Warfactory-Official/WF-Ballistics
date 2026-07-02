package com.wf.wfballistics;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.ExplosionEffectStandard;
import com.wf.wfballistics.chunk.MissileChunkLoader;
import com.wf.wfballistics.entity.OBBEntity;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.sim.SimMissileManager;
import com.wf.wfballistics.util.OBB;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.List;

public class MissileEntity extends Projectile implements OBBEntity {
    public static final Detonation STANDARD = (missile, pos) -> {
        var expl = new ExplosionAEF(missile.level(), pos.x, pos.y, pos.z, 250);
        expl.makeStandard();
        expl.setSFX(new ExplosionEffectStandard());
        expl.explode();
    };

    public static final Detonation INERT = (missile, pos) -> {
    };

    public static final double CRUISE_SPEED = 1.0;
    private static final java.util.Map<String, Detonation> WARHEADS = new java.util.HashMap<>();
    //Why is this nophono here
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EntityDataAccessor<BlockPos> TARGET_POS =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    private static final double TURN_AGILITY = 1.0; // radians * (model units) per tick

    static {
        WARHEADS.put("standard", STANDARD);
        WARHEADS.put("inert", INERT);
    }

    // Oriented bounding box that wraps the (elongated) missile model, giving projectiles an accurate,
    // rotation-aware hitbox instead of the coarse vanilla AABB. Kept live-updated in refreshObb().
    private final OBB obb = new OBB(new Vector3d(), new Vector3d(), new Quaterniond(), OBB.Part.BODY);
    private final List<OBB> obbList = List.of(this.obb);

    // Forces the chunks the missile needs while flying (own chunk ticking + non-ticking look-ahead fan).
    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();
    private Phase phase = Phase.ASCEND;
    private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
    private double cruiseAltitude = 200.0;
    private double terrainClearance = 24.0;
    // Horizontal cruise speed (blocks/tick). The off-world simulation advances at this same rate so
    // simulated travel time matches an in-world flight.
    private double cruiseSpeed = CRUISE_SPEED;
    // Airburst fuze: while diving, detonate in the air once the missile is within this many
    // blocks (Y difference) above the target. 0 disables it, giving a contact/ground detonation.
    private float explosionOffset = 0.0f;
    private String detonationId = "standard";
    private Detonation detonation = STANDARD;
    // Max change in velocity direction per tick, in radians. Default scales from the model's length
    // (longer airframe = less nimble); overridable via the Builder.
    private double maxTurnRate = TURN_AGILITY / MissileModels.length(MissileModels.DEFAULT);
    // Consecutive ticks spent in CRUISE; gates the offload-to-simulation transition.
    private int cruiseTicks = 0;

    public MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);

        // Required for missile to move properly
        this.noPhysics = true;
        this.setNoGravity(true);
        //Technically you can try doing with gravity... if you hate yourself o algo
    }

    public static void registerWarhead(String id, Detonation detonation) {
        WARHEADS.put(id, detonation);
    }

    private static Detonation warhead(String id) {
        return WARHEADS.getOrDefault(id, STANDARD);
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
            // Keep the AABB wrapping the oriented model each frame so culling / F3+B stay correct;
            // the client doesn't run the flight logic below and never calls move().
            this.setBoundingBox(this.makeBoundingBox());
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level();

        Vec3 currentPos = this.position();
        Vec3 targetPos = this.getTarget();

        double dx = targetPos.x - currentPos.x;
        double dz = targetPos.z - currentPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);


        boolean loadFan = this.cruiseMode == CruiseMode.TERRAIN_FOLLOW
                || this.phase == Phase.ATTACK
                || horizontalDist < MissileSimConfig.FAN_TERMINAL_RANGE;
        this.chunkLoader.update(this, serverLevel, currentPos, this.getDeltaMovement(), loadFan);

        double maxCruiseSpeed = this.cruiseSpeed;
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

        this.cruiseTicks = (this.phase == Phase.CRUISE) ? this.cruiseTicks + 1 : 0;

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

        // Position and heading just changed: re-fit the OBB and the vanilla AABB around the model.
        this.setBoundingBox(this.makeBoundingBox());


        if (this.phase == Phase.CRUISE
                && this.cruiseTicks > MissileSimConfig.CRUISE_SIM_DELAY_TICKS
                && horizontalDist > MissileSimConfig.DESTINATION_RANGE
                && !SimMissileManager.nearAnyListener(serverLevel, this.position())) {
            SimMissileManager.startSim(this);
            return;
        }

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
    public List<OBB> getOBBs() {
        // Recompute on demand so the box is always current, whichever side/tick order queries it
        // (e.g. another projectile's hit-check reaching this missile through the collision mixins).
        this.refreshObb();
        return this.obbList;
    }

    /**
     * Rebuilds the body OBB (center, extents, rotation) from the current missile model and heading.
     * The model's local +Y axis is its nose/long axis, so we map +Y onto the velocity direction, matching
     * {@link MissileVisual}. Model units are rendered 1:1 with blocks, so mesh dimensions are used directly.
     */
    private void refreshObb() {
        String modelId = this.getModelId();
        Vec3 dims = MissileModels.dimensions(modelId);
        Vec3 localCenter = MissileModels.center(modelId);

        Vec3 move = this.getDeltaMovement();
        double lenSq = move.lengthSqr();
        Quaterniond rot = new Quaterniond();
        if (lenSq > 1.0E-8) {
            double inv = 1.0 / Math.sqrt(lenSq);
            rot.rotationTo(0.0, 1.0, 0.0, move.x * inv, move.y * inv, move.z * inv);
        }
        // else: identity rotation (nose points straight up), which matches the ASCEND launch pose.

        // Box center = entity position + the rotated model-center offset (meshes sit base-at-origin).
        Vector3d worldCenter = new Vector3d(localCenter.x, localCenter.y, localCenter.z);
        rot.transform(worldCenter);
        worldCenter.add(this.getX(), this.getY(), this.getZ());

        this.obb.setCenter(worldCenter);
        this.obb.setExtents(new Vector3d(dims.x * 0.5, dims.y * 0.5, dims.z * 0.5));
        this.obb.updateRotation(rot);
    }

    /**
     * The vanilla AABB is fit tightly around the oriented model (the enclosing box of the OBB's corners),
     * so both frustum culling and the F3+B hitbox reflect the actual missile rather than a fixed cube.
     */
    @Override
    protected AABB makeBoundingBox() {
        // Called from the Entity constructor before our fields exist; fall back until the OBB is ready.
        if (this.obb == null) {
            return super.makeBoundingBox();
        }
        this.refreshObb();
        Vector3d ext = this.obb.extents();
        if (ext.x < 1.0E-3 && ext.y < 1.0E-3 && ext.z < 1.0E-3) {
            return super.makeBoundingBox();
        }
        Vector3d[] v = this.obb.getVertices();
        double minX = v[0].x, minY = v[0].y, minZ = v[0].z;
        double maxX = v[0].x, maxY = v[0].y, maxZ = v[0].z;
        for (int i = 1; i < v.length; i++) {
            minX = Math.min(minX, v[i].x); maxX = Math.max(maxX, v[i].x);
            minY = Math.min(minY, v[i].y); maxY = Math.max(maxY, v[i].y);
            minZ = Math.min(minZ, v[i].z); maxZ = Math.max(maxZ, v[i].z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
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

    public double getCruiseAltitude() {
        return this.cruiseAltitude;
    }

    public double getTerrainClearance() {
        return this.terrainClearance;
    }

    public double getMaxTurnRate() {
        return this.maxTurnRate;
    }

    public double getCruiseSpeed() {
        return this.cruiseSpeed;
    }

    public String getDetonationId() {
        return this.detonationId;
    }

    @Override
    public void remove(RemovalReason reason) {
        // Release forced chunks when the missile actually goes away (killed/discarded), but not on a
        // plain chunk unload — those tickets persist so the missile resumes after a reload.
        if (!this.level().isClientSide && reason.shouldDestroy() && this.level() instanceof ServerLevel sl) {
            this.chunkLoader.releaseAll(this, sl);
        }
        super.remove(reason);
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
        tag.putDouble("CruiseSpeed", this.cruiseSpeed);
        tag.putString("ModelId", this.getModelId());
        tag.putInt("CruiseTicks", this.cruiseTicks);
        tag.putString("DetonationId", this.detonationId);
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

        if (tag.contains("CruiseSpeed")) {
            this.cruiseSpeed = tag.getDouble("CruiseSpeed");
        }

        if (tag.contains("ModelId")) {
            this.setModelId(tag.getString("ModelId"));
        }

        if (tag.contains("CruiseTicks")) {
            this.cruiseTicks = tag.getInt("CruiseTicks");
        }

        if (tag.contains("DetonationId")) {
            this.detonationId = tag.getString("DetonationId");
            this.detonation = warhead(this.detonationId);
        }
    }

    private void onMissileImpact(HitResult hitResult) {
        this.detonate(hitResult.getLocation());
    }

    /**
     * Fires the configured warhead at the given position and removes the missile.
     */
    private void detonate(Vec3 pos) {
        if (this.level() instanceof ServerLevel sl) {
            this.chunkLoader.releaseAll(this, sl);
        }
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
        private String detonationId = "standard";
        private double cruiseSpeed = CRUISE_SPEED;
        private Double maxTurnRate = null; // null = keep the model-size default
        private String modelId = MissileModels.DEFAULT;
        private boolean startInCruise = false;

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

        /**
         * Pick the warhead by its registered id (see {@link #registerWarhead}); defaults to
         * {@code "standard"}. Using an id (rather than a raw lambda) lets the warhead survive save/load.
         */
        public Builder detonation(String detonationId) {
            this.detonationId = detonationId;
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
         * Set the horizontal cruise speed (blocks/tick); default {@link #CRUISE_SPEED}. The off-world
         * simulation advances at this same rate, so travel time matches whether flown or simulated.
         */
        public Builder cruiseSpeed(double blocksPerTick) {
            this.cruiseSpeed = blocksPerTick;
            return this;
        }

        /**
         * Pick which missile model/skin to render and fly as (see {@link MissileModels}).
         */
        public Builder model(String modelId) {
            this.modelId = modelId;
            return this;
        }

        /**
         * Start the missile already in the CRUISE phase (used when respawning a simulated missile
         * that had long since finished its ascent).
         */
        public Builder startInCruise() {
            this.startInCruise = true;
            return this;
        }

        public MissileEntity build() {
            MissileEntity missile = new MissileEntity(this.type, this.level);
            missile.cruiseMode = this.cruiseMode;
            missile.cruiseAltitude = this.cruiseAltitude;
            missile.terrainClearance = this.terrainClearance;
            missile.explosionOffset = this.explosionOffset;
            missile.detonationId = this.detonationId;
            missile.detonation = warhead(this.detonationId);
            missile.cruiseSpeed = this.cruiseSpeed;
            missile.setModelId(this.modelId);
            // Default the turn rate off the chosen model's length unless explicitly overridden.
            missile.maxTurnRate = (this.maxTurnRate != null)
                    ? this.maxTurnRate
                    : TURN_AGILITY / MissileModels.length(missile.getModelId());
            if (this.target != null) {
                missile.setTarget(this.target);
            }
            if (this.startInCruise) {
                missile.phase = Phase.CRUISE;
            }
            return missile;
        }
    }
}
