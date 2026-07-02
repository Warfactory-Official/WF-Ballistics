package com.wf.wfballistics;

import com.wf.wfballistics.chunk.MissileChunkLoader;
import com.wf.wfballistics.entity.OBBEntity;
import com.wf.wfballistics.flight.*;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.sim.SimMissileManager;
import com.wf.wfballistics.swarm.SwarmManager;
import com.wf.wfballistics.util.OBB;
import com.wf.wfballistics.util.SweptCollision;
import com.wf.wfballistics.warhead.RecursiveFrag;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

public class MissileEntity extends Projectile implements OBBEntity {

    public static final int DEFAULT_FRAGMENT_COUNT = 24;

    public static final double CRUISE_SPEED = 1.0;
    // Safety/arming: the warhead is inert until the missile has flown this far from its launch point, so it
    // can't fuze, impact-detonate, or be blown up by damage while still on/near the launcher.
    public static final double ARMING_DISTANCE = 6.0;
    // Interception damage pool: CIWS fire / interceptors chip this down; at <= 0 the missile is destroyed.
    public static final float DEFAULT_HEALTH = 50.0f;
    private static final EntityDataAccessor<BlockPos> TARGET_POS =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    private static final double TURN_AGILITY = 1.0; // radians * (model units) per tick
    // Predictive friendly deconfliction ("don't ram your own"): a missile looks AVOID_HORIZON ticks ahead
    // for friendly missiles (same swarm or launcher) whose closest approach would fall within AVOID_MIN_SEP
    // blocks, and if so applies a small course offset (perpendicular to the closing path, complementary
    // between the pair, bounded by AVOID_STRENGTH blocks/tick) so they slip past instead of colliding.
    // AVOID_RADIUS bounds the neighbour search. The offset only exists while a collision is predicted.
    private static final double AVOID_RADIUS = 24.0;
    private static final int AVOID_HORIZON = 20;
    private static final double AVOID_MIN_SEP = 4.0;
    private static final double AVOID_STRENGTH = 1.5;
    private static final double ARMING_DISTANCE_SQ = ARMING_DISTANCE * ARMING_DISTANCE;
    // Failsafe: arm anyway after this many ticks so a missile that somehow can't travel never stays a live dud.
    private static final int ARMING_FAILSAFE_TICKS = 100;
    // Oriented bounding box that wraps the (elongated) missile model, giving projectiles an accurate,
    // rotation-aware hitbox instead of the coarse vanilla AABB. Kept live-updated in refreshObb().
    private final OBB obb = new OBB(new Vector3d(), new Vector3d(), new Quaterniond(), OBB.Part.BODY);
    private final List<OBB> obbList = List.of(this.obb);
    // Forces the chunks the missile needs while flying (own chunk ticking + non-ticking look-ahead fan).
    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();
    // Transform (position + heading) the OBB was last built for; refreshObb() skips recomputation while
    // these are unchanged, so the repeated getOBBs() calls during a hit-check don't rebuild it each time.
    private double obbX = Double.NaN, obbY, obbZ, obbDx, obbDy, obbDz;
    private Phase phase = Phase.ASCEND;
    // How this missile flies: one swappable stage per phase (ascent curve, cruise/loiter, attack run),
    // each resolved from FlightStageRegistry by id and composed into the runtime profile. Selecting stages
    // independently is what lets a missile become e.g. a loitering drone; the choice survives save/load.
    // Declared before the profile so the field initialisers run in order.
    private String ascentStageId = FlightStageRegistry.defaultId(Phase.ASCEND);
    private String cruiseStageId = FlightStageRegistry.defaultId(Phase.CRUISE);
    private String attackStageId = FlightStageRegistry.defaultId(Phase.ATTACK);
    private FlightProfile flightProfile = FlightProfile.fromIds(this.ascentStageId, this.cruiseStageId, this.attackStageId);
    // Loitering-munition timer: ticks spent orbiting on-station (see LoiterStage). Persisted.
    private int loiterTicks = 0;
    private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
    private double cruiseAltitude = 200.0;
    private double terrainClearance = 24.0;
    // Smoothed terrain-follow altitude the missile actually flies toward (eased from the raw scan);
    // NaN until the first cruise tick, then re-seeded from the current scan.
    private double cruiseTargetY = Double.NaN;
    // Horizontal cruise speed (blocks/tick). The off-world simulation advances at this same rate so
    // simulated travel time matches an in-world flight.
    private double cruiseSpeed = CRUISE_SPEED;
    // Airburst fuze: while diving, detonate in the air once the missile is within this many
    // blocks (Y difference) above the target. 0 disables it, giving a contact/ground detonation.
    private float explosionOffset = 0.0f;
    private String detonationId = "standard";
    // Number of bomblets the FRAGMENTATION warhead scatters; per-missile, set via the Builder.
    private int fragmentCount = DEFAULT_FRAGMENT_COUNT;
    private WarheadRegistry.Detonation detonation = WarheadRegistry.STANDARD;
    // Max change in velocity direction per tick, in radians. Default scales from the model's length
    // (longer airframe = less nimble); overridable via the Builder.
    private double maxTurnRate = TURN_AGILITY / MissileModels.length(MissileModels.DEFAULT);
    // Consecutive ticks spent in CRUISE; gates the offload-to-simulation transition.
    private int cruiseTicks = 0;
    private float health = DEFAULT_HEALTH;
    // Guards against re-entrant detonation: the warhead's own blast can hurt() this still-present missile,
    // which would otherwise re-enter detonate() and recurse until the stack overflows.
    private boolean detonated = false;
    // Arming state (see ARMING_DISTANCE): the launch point, captured on the first server tick, and whether
    // the warhead has gone live. While unarmed all detonation triggers are suppressed.
    private Vec3 launchPos = null;
    private boolean armed = false;
    // Recursive-fragmentation payload: how many more times this missile splits into child missiles before
    // the leaf generation does a real blast (see RecursiveFrag). 0 = never splits (a normal missile).
    private int splitDepth = 0;
    // Groups a fragmentation family so its members never collide with one another (see canHitEntity).
    // 0 = no family; the missile collides with every other missile normally.
    private long swarmId = 0L;
    // Launcher/control identity (see MissileDispenserBlockEntity): missiles sharing a non-null control id
    // (fired from the same launcher, or one recursive family) are friendly and never collide with each other.
    private UUID controlId = null;

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
            // Keep the AABB wrapping the oriented model each frame so culling / F3+B stay correct;
            // the client doesn't run the flight logic below and never calls move().
            this.setBoundingBox(this.makeBoundingBox());
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level();

        if (this.launchPos == null) {
            this.launchPos = this.position();
        }

        Vec3 currentPos = this.position();
        Vec3 targetPos = this.getTarget();

        double dx = targetPos.x - currentPos.x;
        double dz = targetPos.z - currentPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);


        boolean loadFan = this.cruiseMode == CruiseMode.TERRAIN_FOLLOW
                || this.phase == Phase.ATTACK
                || horizontalDist < MissileSimConfig.FAN_TERMINAL_RANGE;
        this.chunkLoader.update(this, serverLevel, currentPos, this.getDeltaMovement(), loadFan);

        // Guidance: delegate "how it flies" to the active flight stage for this phase. Swapping a stage or the
        // whole profile (a drone ascent curve, a different attack run) changes flight without touching this
        // method — the stages live in the flight package and are resolved from FlightProfileRegistry.
        double nx = 0.0;
        double nz = 0.0;
        if (horizontalDist > 1.0E-3) {
            nx = dx / horizontalDist;
            nz = dz / horizontalDist;
        }
        double safeAltitude = this.computeSafeAltitude(currentPos, dx, dz, horizontalDist);
        FlightContext ctx = new FlightContext(currentPos, targetPos, horizontalDist, nx, nz, safeAltitude);

        // Advance the phase (each stage decides when it is done), then fly the resulting phase's stage.
        Phase next = this.flightProfile.stage(this.phase).next(this, ctx);
        if (next != null) {
            this.phase = next;
        }
        this.cruiseTicks = (this.phase == Phase.CRUISE) ? this.cruiseTicks + 1 : 0;

        Vec3 velocity = this.flightProfile.stage(this.phase).guide(this, ctx);

        // Coordinate with nearby friendly missiles: if their paths would cross, veer just enough to slip
        // past instead of ramming. Folded into the desired velocity so the turn-rate limit smooths it in.
        if (this.controlId != null || this.swarmId != 0L) {
            velocity = velocity.add(this.avoidFriendlies());
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

        // Detonation triggers are inert until the missile has armed clear of the launcher.
        if (this.isArmed()) {
            // Airburst fuze
            if (this.explosionOffset > 0.0f && this.phase == Phase.ATTACK
                    && this.getY() - targetPos.y <= this.explosionOffset) {
                this.detonate(this.position());
                return;
            }

            // Swept collision over the segment actually traversed this tick (currentPos is the pre-move
            // position), extended forward by the body length. Replaces the vanilla post-move look-ahead
            // raycast, which let fast missiles tunnel through walls.
            HitResult hitResult = this.sweepForImpact(currentPos, this.getDeltaMovement());

            if (hitResult.getType() != HitResult.Type.MISS) {
                this.onMissileImpact(hitResult);
            }
        }
    }

    /**
     * Whether the warhead is live. Latches true once the missile has flown {@link #ARMING_DISTANCE} from its
     * launch point (or after {@link #ARMING_FAILSAFE_TICKS} as a stuck-missile failsafe), preventing a
     * detonation on or right next to the launcher.
     */
    private boolean isArmed() {
        if (this.armed) {
            return true;
        }
        boolean clearedLauncher = this.launchPos != null
                && this.position().distanceToSqr(this.launchPos) >= ARMING_DISTANCE_SQ;
        if (clearedLauncher || this.tickCount >= ARMING_FAILSAFE_TICKS) {
            this.armed = true;
        }
        return this.armed;
    }

    /**
     * The altitude the missile should hold this tick: the fixed cruise height in HIGH_ALTITUDE mode, or the
     * scanned terrain top (looked ahead toward the target once past ascent) plus the terrain clearance.
     * Shared by the flight stages via the {@link FlightContext}.
     */
    private double computeSafeAltitude(Vec3 pos, double dx, double dz, double horizontalDist) {
        if (this.cruiseMode == CruiseMode.HIGH_ALTITUDE) {
            return this.cruiseAltitude;
        }
        double terrainScanRadius = 24.0; // how far around the missile we look for terrain
        double lookAhead = 32.0;         // how far ahead (toward target) to scan while cruising
        double scanCenterX = pos.x;
        double scanCenterZ = pos.z;
        if (this.phase != Phase.ASCEND && horizontalDist > 1.0E-3) {
            scanCenterX += (dx / horizontalDist) * lookAhead;
            scanCenterZ += (dz / horizontalDist) * lookAhead;
        }
        return scanTerrainTop(scanCenterX, scanCenterZ, terrainScanRadius) + this.terrainClearance;
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
        if (target == this || target.isSpectator() || !target.isAlive()) {
            return false;
        }
        // Missiles aren't pickable (Entity#isPickable is false), so the vanilla check below would drop them
        // and they'd pass through one another. Allow missile-vs-missile explicitly so they collide/intercept
        // midair; everything else keeps the pickable requirement (this also keeps arrows from killing them).
        if (target instanceof MissileEntity other) {
            // Friendlies never collide: same fragmentation family (swarm) or same launcher (control id).
            // This stops a salvo self-detonating while still letting rival launchers' missiles intercept.
            return !this.isFriendly(other);
        }
        return target.isPickable();
    }

    /**
     * @return true if {@code other} is on the same side — same swarm (frag family) or launcher (control id).
     */
    private boolean isFriendly(MissileEntity other) {
        if (SwarmManager.sameSwarm(this, other)) {
            return true;
        }
        return this.controlId != null && this.controlId.equals(other.controlId);
    }

    /**
     * A non-zero pick radius so {@code MixinProjectileUtil} inflates the target OBB when another missile
     * sweeps through it — makes fast crossing intercepts land reliably instead of needing a pixel-perfect
     * centerline crossing. Also lets look-at / raytrace targeting see the missile.
     */
    @Override
    public float getPickRadius() {
        return 0.5f;
    }

    /**
     * Swept, substepped block/entity collision over the traversed segment, extended forward by the body
     * length so the nose (not the base origin) triggers the hit. Non-tunneling at any speed. Fidelity and
     * substep budget are tuned via {@link MissileSimConfig}. See {@link SweptCollision}.
     */
    private HitResult sweepForImpact(Vec3 startPos, Vec3 delta) {
        return SweptCollision.sweep(this, this.level(), startPos, delta, this.noseForward(),
                this::canHitEntity,
                MissileSimConfig.COLLISION_MAX_SUBSTEP_DIST, MissileSimConfig.COLLISION_MAX_SUBSTEPS);
    }

    /**
     * Distance from the entity origin (mesh base) to the model's front face along the heading.
     */
    private double noseForward() {
        String id = this.getModelId();
        return MissileModels.center(id).y + MissileModels.dimensions(id).y * 0.5;
    }

    @Override
    public boolean enableAABB() {
        // Missiles always carry their body OBB, so skip the getOBBs()/isEmpty() default this would otherwise run.
        return false;
    }

    @Override
    public List<OBB> getOBBs() {
        // Kept current on demand so the box is right whichever side/tick order queries it (e.g. another
        // projectile's hit-check). refreshObb() is a no-op while the transform is unchanged, so the repeated
        // calls a single hit-check makes don't each rebuild it.
        this.refreshObb();
        return this.obbList;
    }

    /**
     * Rebuilds the body OBB (center, extents, rotation) from the current missile model and heading, unless
     * the position and heading are unchanged since the last build (then it's a no-op).
     * The model's local +Y axis is its nose/long axis, so we map +Y onto the velocity direction, matching
     * {@link MissileVisual}. Model units are rendered 1:1 with blocks, so mesh dimensions are used directly.
     */
    private void refreshObb() {
        double x = this.getX(), y = this.getY(), z = this.getZ();
        Vec3 move = this.getDeltaMovement();
        if (x == obbX && y == obbY && z == obbZ && move.x == obbDx && move.y == obbDy && move.z == obbDz) {
            return;
        }
        obbX = x;
        obbY = y;
        obbZ = z;
        obbDx = move.x;
        obbDy = move.y;
        obbDz = move.z;

        String modelId = this.getModelId();
        Vec3 dims = MissileModels.dimensions(modelId);
        Vec3 localCenter = MissileModels.center(modelId);

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
        worldCenter.add(x, y, z);

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
        // Enclosing AABB of the rotated box without allocating its 8 corners: the world half-size along
        // each axis is the sum of |axis component| * extent over the box's three (rotated) local axes.
        Vector3d c = this.obb.center();
        Vector3d[] ax = this.obb.getAxes();
        double hx = ext.x * Math.abs(ax[0].x) + ext.y * Math.abs(ax[1].x) + ext.z * Math.abs(ax[2].x);
        double hy = ext.x * Math.abs(ax[0].y) + ext.y * Math.abs(ax[1].y) + ext.z * Math.abs(ax[2].y);
        double hz = ext.x * Math.abs(ax[0].z) + ext.y * Math.abs(ax[1].z) + ext.z * Math.abs(ax[2].z);
        return new AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz);
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

    /**
     * Cruise-altitude memory used by the cruise stage's altitude smoothing (NaN until the first cruise tick).
     */
    public double getCruiseTargetY() {
        return this.cruiseTargetY;
    }

    public void setCruiseTargetY(double cruiseTargetY) {
        this.cruiseTargetY = cruiseTargetY;
    }

    public String getAscentStageId() {
        return this.ascentStageId;
    }

    public String getCruiseStageId() {
        return this.cruiseStageId;
    }

    public String getAttackStageId() {
        return this.attackStageId;
    }

    public int getLoiterTicks() {
        return this.loiterTicks;
    }

    public void setLoiterTicks(int loiterTicks) {
        this.loiterTicks = loiterTicks;
    }

    /**
     * Recompose the runtime flight profile from the current stage ids (call after any id changes).
     */
    private void rebuildFlightProfile() {
        this.flightProfile = FlightProfile.fromIds(this.ascentStageId, this.cruiseStageId, this.attackStageId);
    }

    /**
     * Rough remaining time-to-impact (ticks) from the current position, target and speed, accounting for the
     * climb, transit, terminal descent and any remaining loiter time. See {@link ArrivalEstimator}.
     */
    public int estimateArrivalTicks() {
        double cruiseAltitudeY = (this.cruiseMode == CruiseMode.HIGH_ALTITUDE)
                ? this.cruiseAltitude
                : this.getY() + this.terrainClearance;
        int loiterRemaining = LoiterStage.INSTANCE.id().equals(this.cruiseStageId)
                ? Math.max(0, LoiterStage.LOITER_TICKS - this.loiterTicks)
                : 0;
        return ArrivalEstimator.estimateTicks(this.position(), this.getTarget(), this.cruiseSpeed,
                cruiseAltitudeY, loiterRemaining);
    }

    public String getDetonationId() {
        return this.detonationId;
    }

    public int getFragmentCount() {
        return this.fragmentCount;
    }

    public int getSplitDepth() {
        return this.splitDepth;
    }

    public long getSwarmId() {
        return this.swarmId;
    }

    public void setSwarmId(long swarmId) {
        this.swarmId = swarmId;
    }

    public UUID getControlId() {
        return this.controlId;
    }

    public void setControlId(UUID controlId) {
        this.controlId = controlId;
    }

    /**
     * Predictive deconfliction against nearby friendly missiles (same swarm or launcher). For each one whose
     * closest approach over the next {@link #AVOID_HORIZON} ticks would fall within {@link #AVOID_MIN_SEP}
     * blocks, adds a small offset away from where it will be — both missiles compute it, so the pair veers
     * apart (a "slight course offset": climb or a lateral shift), bounded by {@link #AVOID_STRENGTH}. Returns
     * {@link Vec3#ZERO} when nothing is actually on a collision course, so friendlies fly their normal path
     * until the moment they would ram.
     */
    private Vec3 avoidFriendlies() {
        AABB box = this.getBoundingBox().inflate(AVOID_RADIUS);
        List<MissileEntity> others = this.level().getEntitiesOfClass(MissileEntity.class, box,
                m -> m != this && m.isAlive() && this.isFriendly(m));
        if (others.isEmpty()) {
            return Vec3.ZERO;
        }
        Vec3 pos = this.position();
        Vec3 vel = this.getDeltaMovement();
        double ox = 0.0, oy = 0.0, oz = 0.0;
        for (MissileEntity other : others) {
            double rpx = other.getX() - pos.x, rpy = other.getY() - pos.y, rpz = other.getZ() - pos.z;
            Vec3 ov = other.getDeltaMovement();
            double rvx = ov.x - vel.x, rvy = ov.y - vel.y, rvz = ov.z - vel.z;
            double rv2 = rvx * rvx + rvy * rvy + rvz * rvz;
            // Time of closest approach (ticks); skip if already separating or too far in the future.
            double t = rv2 < 1.0e-6 ? 0.0 : -(rpx * rvx + rpy * rvy + rpz * rvz) / rv2;
            if (t < 0.0 || t > AVOID_HORIZON) {
                continue;
            }
            double sx = rpx + rvx * t, sy = rpy + rvy * t, sz = rpz + rvz * t;
            double miss = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (miss > AVOID_MIN_SEP) {
                continue; // they already clear each other — no course change
            }
            double urgency = (AVOID_MIN_SEP - miss) / AVOID_MIN_SEP; // 0..1, larger the tighter the miss
            if (miss > 1.0e-3) {
                // Veer away from the predicted closest-approach point (uses whichever axis separates them:
                // a climb if stacked, a sideways shift if abreast).
                double inv = urgency / miss;
                ox -= sx * inv;
                oy -= sy * inv;
                oz -= sz * inv;
            } else {
                // Dead-on (no defined "away"): split sideways, deterministically by id — a shift in x/z.
                double clen = Math.sqrt(rvx * rvx + rvz * rvz);
                double perpx = clen > 1.0e-4 ? -rvz / clen : 1.0;
                double perpz = clen > 1.0e-4 ? rvx / clen : 0.0;
                double dir = this.getId() < other.getId() ? 1.0 : -1.0;
                ox += perpx * dir * urgency;
                oz += perpz * dir * urgency;
            }
        }
        double len = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (len < 1.0e-6) {
            return Vec3.ZERO;
        }
        double scale = Math.min(AVOID_STRENGTH, len) / len;
        return new Vec3(ox * scale, oy * scale, oz * scale);
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
        tag.putString("AscentStage", this.ascentStageId);
        tag.putString("CruiseStage", this.cruiseStageId);
        tag.putString("AttackStage", this.attackStageId);
        tag.putInt("LoiterTicks", this.loiterTicks);
        tag.putInt("FragmentCount", this.fragmentCount);
        tag.putInt("SplitDepth", this.splitDepth);
        tag.putLong("SwarmId", this.swarmId);
        if (this.controlId != null) {
            tag.putUUID("ControlId", this.controlId);
        }
        tag.putFloat("Health", this.health);
        tag.putBoolean("Armed", this.armed);
        if (this.launchPos != null) {
            tag.putDouble("LaunchX", this.launchPos.x);
            tag.putDouble("LaunchY", this.launchPos.y);
            tag.putDouble("LaunchZ", this.launchPos.z);
        }
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
            this.detonation = WarheadRegistry.get(this.detonationId);
        }

        if (tag.contains("AscentStage")) {
            this.ascentStageId = tag.getString("AscentStage");
        }
        if (tag.contains("CruiseStage")) {
            this.cruiseStageId = tag.getString("CruiseStage");
        }
        if (tag.contains("AttackStage")) {
            this.attackStageId = tag.getString("AttackStage");
        }
        this.rebuildFlightProfile();
        this.loiterTicks = tag.getInt("LoiterTicks");

        if (tag.contains("FragmentCount")) {
            this.fragmentCount = tag.getInt("FragmentCount");
        }

        if (tag.contains("SplitDepth")) {
            this.splitDepth = tag.getInt("SplitDepth");
        }
        this.swarmId = tag.getLong("SwarmId");
        if (tag.hasUUID("ControlId")) {
            this.controlId = tag.getUUID("ControlId");
        }

        this.armed = tag.getBoolean("Armed");
        if (tag.contains("LaunchX")) {
            this.launchPos = new Vec3(tag.getDouble("LaunchX"), tag.getDouble("LaunchY"), tag.getDouble("LaunchZ"));
        }

        if (tag.contains("Health")) {
            this.health = tag.getFloat("Health");
        }
    }

    private void onMissileImpact(HitResult hitResult) {
        this.detonate(hitResult.getLocation());
    }

    /**
     * Fires the configured warhead at the given position and removes the missile.
     */
    private void detonate(Vec3 pos) {
        if (this.detonated) {
            return;
        }
        this.detonated = true; // set before the blast: it can hurt() this missile before discard() runs
        if (this.level() instanceof ServerLevel sl) {
            this.chunkLoader.releaseAll(this, sl);
        }
        this.detonation.detonate(this, pos);
        this.discard();
    }

    public float getHealth() {
        return this.health;
    }

    public void damageMissile(float amount) {
        if (this.level().isClientSide || this.isRemoved() || this.detonated || amount <= 0.0f) {
            return;
        }
        // While unarmed, absorb damage without cooking off — a stray hit shouldn't blow it up on the launcher.
        if (!this.isArmed()) {
            return;
        }
        this.health -= amount;
        if (this.health <= 0.0f) {
            this.detonate(this.position());
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        //TODO: Make this not get killed by arrows or something
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        this.damageMissile(amount);
        return true;
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
        private String ascentStageId = null; // null = keep the phase's default stage
        private String cruiseStageId = null;
        private String attackStageId = null;
        private int fragmentCount = DEFAULT_FRAGMENT_COUNT;
        private double cruiseSpeed = CRUISE_SPEED;
        private Double maxTurnRate = null; // null = keep the model-size default
        private String modelId = MissileModels.DEFAULT;
        private boolean startInCruise = false;
        private boolean startInAttack = false;
        private boolean startArmed = false;
        private float health = DEFAULT_HEALTH;
        private Integer splitDepth = null; // null = default by warhead (recursive_frag gets a depth, others 0)
        private long swarmId = 0L;
        private UUID controlId = null;

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
         * Pick the ascent-phase stage by its registered id (see {@link FlightStageRegistry}).
         */
        public Builder ascentStage(String id) {
            this.ascentStageId = id;
            return this;
        }

        /**
         * Pick the cruise-phase stage (e.g. {@code "cruise"} or {@code "loiter"}) by its registered id.
         */
        public Builder cruiseStage(String id) {
            this.cruiseStageId = id;
            return this;
        }

        /**
         * Pick the attack-phase stage (e.g. {@code "attack"} or {@code "dive"}) by its registered id.
         */
        public Builder attackStage(String id) {
            this.attackStageId = id;
            return this;
        }

        /**
         * Number of bomblets the {@code "fragmentation"} warhead scatters; defaults to
         * {@link #DEFAULT_FRAGMENT_COUNT}. No effect for other warheads.
         */
        public Builder fragmentCount(int fragmentCount) {
            this.fragmentCount = fragmentCount;
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

        /**
         * Spawn already in the terminal ATTACK dive. Used by {@link RecursiveFrag} so split missilelets go
         * straight into their attack run ("instant attack mode") instead of ascending/cruising first.
         */
        public Builder startInAttack() {
            this.startInAttack = true;
            return this;
        }

        /**
         * Number of recursive split generations (see {@link RecursiveFrag}); leave unset to take the
         * warhead default ({@link RecursiveFrag#DEFAULT_DEPTH} for {@code recursive_frag}, else none).
         */
        public Builder splitDepth(int splitDepth) {
            this.splitDepth = splitDepth;
            return this;
        }

        /**
         * Group this missile into a fragmentation family (a non-zero id shared by all its descendants) so
         * the family's members won't collide with each other (see {@link #canHitEntity}).
         */
        public Builder swarmId(long swarmId) {
            this.swarmId = swarmId;
            return this;
        }

        /**
         * Stamp this missile with a launcher/control id (see {@link MissileDispenserBlockEntity}) so missiles
         * fired from the same launcher treat each other as friendly and never collide.
         */
        public Builder controlId(UUID controlId) {
            this.controlId = controlId;
            return this;
        }

        /**
         * Spawn with the warhead already armed (used when respawning a simulated missile that has long since
         * flown clear of its launcher). A freshly launched missile leaves this off and arms by distance.
         */
        public Builder startArmed() {
            this.startArmed = true;
            return this;
        }

        /**
         * Set the interception damage pool (see {@link #DEFAULT_HEALTH}); higher = harder to shoot down.
         */
        public Builder health(float health) {
            this.health = health;
            return this;
        }

        public MissileEntity build() {
            MissileEntity missile = new MissileEntity(this.type, this.level);
            missile.cruiseMode = this.cruiseMode;
            missile.cruiseAltitude = this.cruiseAltitude;
            missile.terrainClearance = this.terrainClearance;
            missile.explosionOffset = this.explosionOffset;
            missile.detonationId = this.detonationId;
            missile.detonation = WarheadRegistry.get(this.detonationId);
            if (this.ascentStageId != null) {
                missile.ascentStageId = this.ascentStageId;
            }
            if (this.cruiseStageId != null) {
                missile.cruiseStageId = this.cruiseStageId;
            }
            if (this.attackStageId != null) {
                missile.attackStageId = this.attackStageId;
            }
            missile.rebuildFlightProfile();
            missile.fragmentCount = this.fragmentCount;
            // Recursive-frag payload + defaults so a "recursive_frag" missile picked from the dispenser GUI
            // just works: it gets a split depth and, unless the launcher set one, an airburst altitude to
            // split at. Non-recursive missiles are unaffected (depth 0, swarm 0).
            boolean recursive = RecursiveFrag.ID.equals(this.detonationId);
            missile.splitDepth = (this.splitDepth != null)
                    ? this.splitDepth
                    : (recursive ? RecursiveFrag.DEFAULT_DEPTH : 0);
            missile.swarmId = this.swarmId;
            missile.controlId = this.controlId;
            if (recursive && this.explosionOffset <= 0.0f) {
                missile.explosionOffset = RecursiveFrag.splitAltitude(missile.splitDepth);
            }
            missile.cruiseSpeed = this.cruiseSpeed;
            missile.health = this.health;
            missile.setModelId(this.modelId);
            // Default the turn rate off the chosen model's length unless explicitly overridden.
            missile.maxTurnRate = (this.maxTurnRate != null)
                    ? this.maxTurnRate
                    : TURN_AGILITY / MissileModels.length(missile.getModelId());
            if (this.target != null) {
                missile.setTarget(this.target);
            }
            if (this.startInAttack) {
                missile.phase = Phase.ATTACK;
            } else if (this.startInCruise) {
                missile.phase = Phase.CRUISE;
            }
            missile.armed = this.startArmed;
            return missile;
        }
    }
}
