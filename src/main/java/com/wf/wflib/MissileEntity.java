package com.wf.wflib;

import com.mojang.logging.LogUtils;
import com.wf.wflib.api.Threat;
import com.wf.wflib.api.ThreatSource;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.api.WFTelemetry;
import com.wf.wflib.api.WFTelemetryService;
import com.wf.wflib.attitude.MissileAttitude;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.chunk.MissileChunkLoader;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.debug.MissileDebug;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.entity.ModelledProjectile;
import com.wf.wflib.entity.OBBEntity;
import com.wf.wflib.flight.FlightContext;
import com.wf.wflib.fx.ExplosionCreator;
import com.wf.wflib.missile.MissileBuilder;
import com.wf.wflib.missile.MissileDamage;
import com.wf.wflib.missile.MissileFlight;
import com.wf.wflib.missile.MissileFuze;
import com.wf.wflib.missile.MissileInterceptor;
import com.wf.wflib.missile.MissileMotor;
import com.wf.wflib.missile.MissileSeeker;
import com.wf.wflib.missile.MissileSignature;
import com.wf.wflib.missile.MissileSwarm;
import com.wf.wflib.missile.MissileTicker;
import com.wf.wflib.network.MissileFlightAudioPacket;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.sim.IMissileListener;
import com.wf.wflib.sim.MissileListenerRegistry;
import com.wf.wflib.sim.MissileSimConfig;
import com.wf.wflib.swarm.SwarmManager;
import com.wf.wflib.tv.TvGuidance;
import com.wf.wflib.util.OBB;
import com.wf.wflib.util.SweptCollision;
import com.wf.wflib.warhead.RecursiveFrag;
import com.wf.wflib.warhead.WarheadCarrier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Guided missile. Entity = body (model, OBB, synced look, allegiance, telemetry, chunk tickets) + tick order;
 * behaviour lives in the components under {@link com.wf.wflib.missile}.
 */
public class MissileEntity extends Projectile implements OBBEntity, InterceptTarget, IMissileListener, WarheadCarrier,
        ModelledProjectile, ThreatSource {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LOG_INTERVAL = 4;

    public static final int DEFAULT_FRAGMENT_COUNT = 24;
    public static final int DEFAULT_IMPACT_PRELOAD_RADIUS = 4;
    public static final int DEFAULT_EXHAUST_COLOR = 0xFFB20D;
    public static final double CRUISE_SPEED = 1.0;
    public static final double ASCENT_SPEED_FACTOR = 1.5;
    public static final double MIN_ASCENT_SPEED = 1.5;
    public static final double DEFAULT_MIN_DIVE_ANGLE = 80.0;
    public static final double DEFAULT_MAX_DIVE_ANGLE = 90.0;
    public static final double ARMING_DISTANCE = 6.0;
    public static final float DEFAULT_HEALTH = 50.0f;
    public static final double DEFAULT_ACCELERATION = 0.15;
    public static final double DEFAULT_DECELERATION = 0.25;
    public static final double DEFAULT_FLIGHT_SOUND_RANGE = 300.0;
    public static final double DEFAULT_APPROACH_JOIN_CAP = 1500.0;
    public static final double DEFAULT_DUD_CHANCE = 0.15;
    public static int DEFAULT_FUEL_TICKS = 1200;
    private static final int SONIC_BOOM_INTERVAL = 6;

    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    /** Hot RGB the client plume fades from. */
    private static final EntityDataAccessor<Integer> EXHAUST_COLOR =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
    /** Travels submerged; synced, not {@link #isSubmerged}: the wake emitter is built on the spawn tick. */
    private static final EntityDataAccessor<Boolean> SUBMERGED_MEDIUM =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> FLIGHT_SOUND =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    /** {@code RoundRenderers} id drawn instead of the airframe; "" = the airframe. */
    private static final EntityDataAccessor<String> LOOK =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    /** Visual roll about the nose while downed, rad/tick. */
    private static final EntityDataAccessor<Float> DOWNED_ROLL =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DUD =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Vector3f> DUD_HEADING =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.VECTOR3);

    private final MissileFlight flight = new MissileFlight(this);
    private final MissileMotor motor = new MissileMotor(this);
    private final MissileFuze fuze = new MissileFuze(this);
    private final MissileDamage damage = new MissileDamage(this);
    private final MissileSwarm swarm = new MissileSwarm(this);
    private final MissileInterceptor interceptor = new MissileInterceptor(this);
    private final MissileSignature signature = new MissileSignature(this);
    private final MissileSeeker seeker = new MissileSeeker(this);

    private final Vector3f obbHeading = new Vector3f();
    private final Quaternionf obbOrientation = new Quaternionf();
    private final Quaterniond obbRotation = new Quaterniond();
    private final Vector3d obbScratch = new Vector3d();
    private final OBB obb = new OBB(new Vector3f(), new Vector3f(), new Quaternionf());
    private final List<OBB> obbList = List.of(this.obb);
    private double obbX = Double.NaN, obbY, obbZ, obbDx, obbDy, obbDz;

    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();
    private WFTelemetry telemetry;
    private boolean telemetryInit;
    private boolean fuelOutRecorded;
    private double flightSoundRange = DEFAULT_FLIGHT_SOUND_RANGE;
    private float flightSoundBasePitch = 1.0f;
    private double flightSoundSpeedPitch;
    /** Launcher: rounds sharing it are friendly. */
    @Nullable
    private UUID controlId;
    /** WarForge faction. */
    @Nullable
    private UUID teamId;
    private boolean leavingWorld;

    public MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static MissileBuilder builder(EntityType<? extends Projectile> type, Level level) {
        return new MissileBuilder(type, level);
    }

    public MissileFlight flight() {
        return this.flight;
    }

    public MissileMotor motor() {
        return this.motor;
    }

    public MissileFuze fuze() {
        return this.fuze;
    }

    public MissileDamage damage() {
        return this.damage;
    }

    public MissileSwarm swarm() {
        return this.swarm;
    }

    public MissileInterceptor interceptor() {
        return this.interceptor;
    }

    public MissileSignature signature() {
        return this.signature;
    }

    public MissileSeeker seeker() {
        return this.seeker;
    }

    public MissileChunkLoader chunkLoader() {
        return this.chunkLoader;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            // AABB wraps the oriented model each frame: culling / F3+B.
            this.refitBounds();
            return;
        }
        MissileTicker.tick(this, (ServerLevel) this.level());
    }

    /** Fuel-out bookkeeping, once. */
    public void noteFuelOut(Vec3 at) {
        if (!this.fuelOutRecorded && !this.damage.isDowned()) {
            this.fuelOutRecorded = true;
            this.chunkLoader.setTargetPreload(at, Math.max(1, this.fuze.getImpactPreloadRadius()));
            this.recordEvent(WFEventType.FUEL_OUT, "ballistic");
        }
    }

    /** Sonic boom + flight audio for a missile that just moved. */
    public void emitFlightNoise() {
        double speed = this.getDeltaMovement().length();
        if (speed >= MissileSimConfig.SUPERSONIC_SPEED && (this.tickCount + this.getId()) % SONIC_BOOM_INTERVAL == 0) {
            ExplosionCreator.sonicBoom(this.level(), this.getX(), this.getY(), this.getZ(),
                    (float) Mth.clamp(speed * 1.5, 6.0, 24.0));
        }
        if (this.tickCount % MissileFlightAudioPacket.UPDATE_INTERVAL == 0) {
            MissileFlightAudioPacket.broadcastEntity(this);
        }
    }

    /**
     * Move by {@code v}, refit the body, sweep what the move crossed.
     * @return the first non-miss hit while armed, else null
     */
    @Nullable
    public HitResult flyTo(Vec3 from, Vec3 v) {
        this.setDeltaMovement(v);
        this.hasImpulse = true;
        this.move(MoverType.SELF, v);
        this.refitBounds();
        if (!this.fuze.isArmed()) {
            return null;
        }
        HitResult hit = SweptCollision.sweep(this, this.level(), from, v, this.noseForward(), this::canHitEntity,
                MissileSimConfig.COLLISION_MAX_SUBSTEP_DIST, MissileSimConfig.COLLISION_MAX_SUBSTEPS);
        return hit.getType() == HitResult.Type.MISS ? null : hit;
    }

    public void logFlightDebug(FlightContext ctx) {
        if (this.swarm.getSwarmId() == 0L && !RecursiveFrag.ID.equals(this.fuze.getDetonationId())) {
            return;
        }
        if (this.tickCount % LOG_INTERVAL != 0) {
            return;
        }
        Vec3 pos = ctx.position();
        Vec3 target = ctx.target();
        Vec3 vel = this.getDeltaMovement();
        double vh = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        double closing = vel.x * ctx.nx() + vel.z * ctx.nz();
        double turnRate = this.flight.getMaxTurnRate();
        int groundUnderMissile = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(pos.x), Mth.floor(pos.z));
        int groundUnderTarget = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(target.x), Mth.floor(target.z));
        LOGGER.info(String.format("[MSL id=%d swarm=%d depth=%d phase=%s age=%d commit=%s] pos=(%.1f,%.1f,%.1f) "
                        + "tgt=(%.1f,%.1f,%.1f) dist2D=%.1f vy=%.1f vh=%.1f spd=%.1f closing=%.1f turnR=%.1f diveAng=%.1f "
                        + "diveRange=[%.1f,%.1f] fuze=%.1f altAGL=%.1f tgtAGL=%.1f",
                this.getId(), this.swarm.getSwarmId(), this.swarm.getSplitDepth(), this.flight.getPhase(),
                this.tickCount, this.flight.isDiveCommitted(), pos.x, pos.y, pos.z, target.x, target.y, target.z,
                ctx.horizontalDist(), vel.y, vh, vel.length(), closing, turnRate > 1.0E-4 ? vh / turnRate : 0.0,
                this.flight.resolveDiveAngle(ctx), this.flight.getMinDiveAngle(), this.flight.getMaxDiveAngle(),
                this.fuze.getExplosionOffset(), pos.y - groundUnderMissile, target.y - groundUnderTarget));
    }

    public WFTelemetry telemetry() {
        return this.telemetry;
    }

    public void attachTelemetry(WFTelemetry telemetry) {
        this.telemetry = telemetry;
        this.telemetryInit = true;
        this.recordSpawnIfFresh();
    }

    public void openTelemetry() {
        if (this.level().isClientSide) {
            return;
        }
        this.attachTelemetry(WFTelemetryService.open(this.getUUID(), this.level().getGameTime()));
    }

    public void initTelemetry() {
        if (this.telemetryInit) {
            return;
        }
        this.telemetryInit = true;
        WFTelemetry existing = WFTelemetryService.get(this.getUUID());
        if (existing != null) {
            this.telemetry = existing;
        } else if (MissileDebug.enabled() || WFTelemetryService.autoOpen()) {
            this.telemetry = WFTelemetryService.open(this.getUUID(), this.level().getGameTime());
            if (MissileDebug.enabled()) {
                MissileDebug.markLatest(this.getUUID());
            }
        }
        this.recordSpawnIfFresh();
    }

    private void recordSpawnIfFresh() {
        if (this.telemetry != null && this.telemetry.total() == 0 && this.tickCount <= 1) {
            this.recordEvent(WFEventType.SPAWN, "");
        }
    }

    public void recordEvent(WFEventType type, String detail) {
        if (this.level().isClientSide) {
            return;
        }
        WFTelemetryService.record(this.telemetry, this.getUUID(), type,
                this.level().getGameTime(), this.position(), false, detail);
    }

    /** Sweep/proximity target filter. */
    public boolean isHitCandidate(Entity target) {
        return this.canHitEntity(target);
    }

    @Override
    public float blastSize() {
        return this.fuze.getBlastSize();
    }

    @Override
    public boolean breaksBlocks() {
        return this.fuze.breaksBlocks();
    }

    /** Kill credit and self-exclusion go to the launcher. */
    @Nullable
    @Override
    public Entity exploder() {
        return this.getOwner();
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (target == this || target.isSpectator() || !target.isAlive() || this.fuze.fuseFailed()) {
            return false;
        }
        if (this.flight.getPhase() == Phase.ASCEND && target == this.getOwner()) {
            return false;
        }
        if (this.controlId != null && this.controlId.equals(target.getRootVehicle().getUUID())) {
            return false;
        }
        if (this.interceptor.isActive() && target instanceof InterceptTarget) {
            return false;
        }
        if (target instanceof MissileEntity other) {
            if (this.interceptor.isActive() || other.interceptor.isActive()) {
                return false;
            }
            return !this.isFriendly(other);
        }
        return target.isPickable();
    }

    /** Non-missile target: shares no swarm; same launcher or friendly faction. */
    public boolean isFriendly(InterceptTarget other) {
        if (other instanceof MissileEntity missile) {
            return this.isFriendly(missile);
        }
        UUID control = other.interceptControlId();
        if (this.controlId != null && this.controlId.equals(control)) {
            return true;
        }
        return WarforgeCompat.areFactionsFriendly(this.teamId, other.interceptTeamId());
    }

    /** Same swarm (frag family), same launcher, or friendly faction. */
    public boolean isFriendly(MissileEntity other) {
        if (SwarmManager.sameSwarm(this, other)) {
            return true;
        }
        if (this.controlId != null && this.controlId.equals(other.controlId)) {
            return true;
        }
        return WarforgeCompat.areFactionsFriendly(this.teamId, other.teamId);
    }

    @Nullable
    public UUID getTeamId() {
        return this.teamId;
    }

    public void setTeamId(@Nullable UUID teamId) {
        this.teamId = teamId;
    }

    @Nullable
    public UUID getControlId() {
        return this.controlId;
    }

    public void setControlId(@Nullable UUID controlId) {
        this.controlId = controlId;
    }

    /** Warhead blast credited to the missile's faction. */
    @Override
    public UUID igniterFactionId() {
        return this.teamId;
    }

    /** Non-zero: a sweeping missile's hit test inflates this OBB, so crossing intercepts need no exact centreline. */
    @Override
    public float getPickRadius() {
        return 0.5f;
    }

    /** Origin (mesh base) to front face along the heading. */
    public double noseForward() {
        ResourceLocation id = this.getModelId();
        return MissileModels.center(id).y + MissileModels.dimensions(id).y * 0.5;
    }

    @Override
    public boolean enableAABB() {
        return false;
    }

    @Override
    public List<OBB> getOBBs() {
        this.refreshObb();
        return this.obbList;
    }

    /** Rebuild the body OBB from model + heading; no-op while position and heading are unchanged. */
    private void refreshObb() {
        double x = this.getX(), y = this.getY(), z = this.getZ();
        Vec3 move = this.isDud() ? new Vec3(this.dudHeading()) : this.getDeltaMovement();
        if (x == obbX && y == obbY && z == obbZ && move.x == obbDx && move.y == obbDy && move.z == obbDz) {
            return;
        }
        obbX = x;
        obbY = y;
        obbZ = z;
        obbDx = move.x;
        obbDy = move.y;
        obbDz = move.z;
        ResourceLocation modelId = this.getModelId();
        Vec3 dims = MissileModels.dimensions(modelId);
        Vec3 localCenter = MissileModels.center(modelId);
        double lenSq = move.lengthSqr();
        // Zero heading: identity (nose up) = the ASCEND launch pose.
        Quaterniond rot = obbRotation.identity();
        if (lenSq > 1.0E-8) {
            double inv = 1.0 / Math.sqrt(lenSq);
            MissileAttitude attitude = MissileAttitudeRegistry.get(MissileModels.attitudeId(modelId));
            obbHeading.set((float) (move.x * inv), (float) (move.y * inv), (float) (move.z * inv));
            Quaternionf q = attitude.orientation(obbHeading, obbOrientation);
            rot.set(q.x, q.y, q.z, q.w);
        }
        // Meshes sit base-at-origin: centre = position + rotated model-centre offset.
        Vector3d worldCenter = obbScratch.set(localCenter.x, localCenter.y, localCenter.z);
        rot.transform(worldCenter);
        this.obb.setCenter(x + worldCenter.x, y + worldCenter.y, z + worldCenter.z);
        this.obb.setExtents((float) (dims.x * 0.5), (float) (dims.y * 0.5), (float) (dims.z * 0.5));
        this.obb.rotation().set((float) rot.x, (float) rot.y, (float) rot.z, (float) rot.w);
    }

    public void refitBounds() {
        this.setBoundingBox(this.makeBoundingBox());
    }

    /** Tight around the oriented model: culling and F3+B show the missile, not a cube. */
    @Override
    protected AABB makeBoundingBox() {
        // Entity constructor calls this before our fields exist.
        if (this.obb == null) {
            return super.makeBoundingBox();
        }
        this.refreshObb();
        Vector3f ext = this.obb.extents();
        if (ext.x < 1.0E-3 && ext.y < 1.0E-3 && ext.z < 1.0E-3) {
            return super.makeBoundingBox();
        }
        return this.obb.bounds();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean shouldRenderAtSqrDistance(double distance) {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODEL_ID, MissileModels.DEFAULT.toString());
        builder.define(EXHAUST_COLOR, DEFAULT_EXHAUST_COLOR);
        builder.define(SUBMERGED_MEDIUM, false);
        builder.define(FLIGHT_SOUND, "");
        builder.define(LOOK, "");
        builder.define(DOWNED_ROLL, 0.0f);
        builder.define(DUD, false);
        builder.define(DUD_HEADING, new Vector3f(0.0f, 1.0f, 0.0f));
    }

    private record ModelRef(String raw, ResourceLocation id) {
    }

    private volatile ModelRef modelRef;

    @Override
    public ResourceLocation getModelId() {
        String raw = this.entityData.get(MODEL_ID);
        ModelRef ref = this.modelRef;
        if (ref == null || !ref.raw().equals(raw)) {
            ref = new ModelRef(raw, MissileModels.parse(raw));
            this.modelRef = ref;
        }
        return ref.id();
    }

    public void setModelId(ResourceLocation id) {
        this.entityData.set(MODEL_ID, (MissileModels.exists(id) ? id : MissileModels.DEFAULT).toString());
    }

    public int getExhaustColor() {
        return this.entityData.get(EXHAUST_COLOR);
    }

    public void setExhaustColor(int rgb) {
        this.entityData.set(EXHAUST_COLOR, rgb);
    }

    /** Client loop; {@link WFSounds#MISSILE_FLIGHT} when unset or unresolvable. */
    public SoundEvent getFlightSound() {
        ResourceLocation rl = this.getFlightSoundId();
        SoundEvent event = rl == null ? null : BuiltInRegistries.SOUND_EVENT.get(rl);
        return event != null ? event : WFSounds.MISSILE_FLIGHT.get();
    }

    public void setLook(@Nullable ResourceLocation id) {
        this.entityData.set(LOOK, id == null ? "" : id.toString());
    }

    @Nullable
    public ResourceLocation getLook() {
        String id = this.entityData.get(LOOK);
        return id.isEmpty() ? null : ResourceLocation.tryParse(id);
    }

    /** Must be a registered {@link SoundEvent}; null = default loop. */
    public void setFlightSound(@Nullable ResourceLocation id) {
        this.entityData.set(FLIGHT_SOUND, id == null ? "" : id.toString());
    }

    /** Raw override id; null = default loop. */
    @Nullable
    public ResourceLocation getFlightSoundId() {
        String id = this.entityData.get(FLIGHT_SOUND);
        return id.isEmpty() ? null : ResourceLocation.tryParse(id);
    }

    public void setFlightSoundShape(double range, float basePitch, double speedPitch) {
        this.flightSoundRange = range;
        this.flightSoundBasePitch = basePitch;
        this.flightSoundSpeedPitch = speedPitch;
    }

    /** Fade distance = server broadcast radius. */
    public double getFlightSoundRange() {
        return this.flightSoundRange;
    }

    public float getFlightSoundBasePitch() {
        return this.flightSoundBasePitch;
    }

    /** Pitch added per block/tick of own speed; 0 = constant. */
    public double getFlightSoundSpeedPitch() {
        return this.flightSoundSpeedPitch;
    }

    public void syncSubmergedMedium(boolean submerged) {
        this.entityData.set(SUBMERGED_MEDIUM, submerged);
    }

    /**
     * Travels submerged (client-readable). != {@link #isSubmerged}: a torpedo still falling to the sea after an air
     * launch answers true here, false there.
     */
    public boolean isSubmergedMedium() {
        return this.entityData.get(SUBMERGED_MEDIUM);
    }

    /** Own block in fluid: the one "in the water" test for stages, broach and wake. */
    public boolean isSubmerged() {
        return !this.level().getFluidState(this.blockPosition()).isEmpty();
    }

    public void syncDownedRoll(float roll) {
        this.entityData.set(DOWNED_ROLL, roll);
    }

    public float downedRoll() {
        return this.entityData.get(DOWNED_ROLL);
    }

    public void syncDud(Vector3f heading) {
        this.entityData.set(DUD_HEADING, heading);
        this.entityData.set(DUD, true);
    }

    /** Downed, hit the ground without going off: live ordnance where it fell. */
    public boolean isDud() {
        return this.entityData.get(DUD);
    }

    /** Resting nose direction of a dud. */
    public Vector3f dudHeading() {
        return this.entityData.get(DUD_HEADING);
    }

    @Override
    public boolean isPickable() {
        return this.isDud();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return this.damage.interact(player);
    }

    @Override
    public int getFragmentCount() {
        return this.fuze.getFragmentCount();
    }

    @Override
    public Vec3 angle() {
        Vec3 v = this.getDeltaMovement();
        return v.lengthSqr() < 1.0e-8 ? new Vec3(0.0, -1.0, 0.0) : v.normalize();
    }

    @Override
    public Threat threat() {
        return this.fuze.threat();
    }

    /** Handed to the sim: removal without the shoot-down veto. */
    public void leaveWorld() {
        this.leavingWorld = true;
        this.discard();
    }

    /** DISCARDED while armed and live => shot down instead (vetoed): it coasts to its own crash. */
    @Override
    public void remove(RemovalReason reason) {
        if (reason == RemovalReason.DISCARDED && !this.leavingWorld && !this.level().isClientSide
                && !this.fuze.detonated() && !this.damage.isDowned() && this.fuze.isArmed() && this.isAlive()) {
            this.damage.shootDown();
            return;
        }
        if (!this.level().isClientSide && reason.shouldDestroy() && this.level() instanceof ServerLevel sl) {
            this.chunkLoader.releaseAll(this, sl);
            if (this.seeker.spec() != null) {
                TvGuidance.lost(sl, this);
            }
            if (this.interceptor.isActive()) {
                MissileListenerRegistry.get(sl).deregister(this.getUUID());
            }
            if (this.swarm.isCommander() && this.swarm.getSwarmId() != 0L) {
                SwarmManager.promoteSuccessor(sl, this.swarm.getSwarmId(), this);
            }
        }
        super.remove(reason);
    }

    @Override
    public Vec3 listenerCenter() {
        return this.position();
    }

    @Override
    public double listenerRange() {
        return MissileSimConfig.INTERCEPTOR_LISTENER_RANGE;
    }

    @Override
    public boolean listenerValid() {
        return this.interceptor.isActive() && !this.isRemoved() && !this.fuze.detonated()
                && this.level() instanceof ServerLevel sl && sl.isLoaded(this.blockPosition());
    }

    /** Interceptors are no target: shooting one spends a second interceptor on a shot already taken. */
    @Override
    public boolean interceptEngageable() {
        return this.isAlive() && !this.fuze.detonated() && !this.damage.isDowned() && !this.interceptor.isActive();
    }

    @Override
    public ContactClass interceptClass() {
        return ContactClass.MISSILE;
    }

    @Override
    public UUID interceptControlId() {
        return this.controlId;
    }

    @Override
    public UUID interceptTeamId() {
        return this.teamId;
    }

    @Override
    public void interceptDamage(float amount) {
        this.damage.damage(amount);
    }

    @Override
    public void interceptKill() {
        this.damage.shootDown(DownedAction.DETONATE);
    }

    @Override
    public boolean interceptEvade(double interceptorSpeed, Vec3 interceptorPos) {
        return this.signature.evade(interceptorSpeed, interceptorPos);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        return this.damage.hurt(source, amount);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("Flight", this.flight.save());
        tag.put("Motor", this.motor.save());
        tag.put("Fuze", this.fuze.save());
        tag.put("Damage", this.damage.save());
        tag.put("Swarm", this.swarm.save());
        tag.put("Interceptor", this.interceptor.save());
        tag.put("Signature", this.signature.save());
        tag.put("Seeker", this.seeker.save());
        tag.putString("ModelId", this.getModelId().toString());
        tag.putInt("ExhaustColor", this.getExhaustColor());
        tag.putString("FlightSound", this.entityData.get(FLIGHT_SOUND));
        tag.putString("Look", this.entityData.get(LOOK));
        tag.putDouble("FlightSoundRange", this.flightSoundRange);
        tag.putFloat("FlightSoundBasePitch", this.flightSoundBasePitch);
        tag.putDouble("FlightSoundSpeedPitch", this.flightSoundSpeedPitch);
        if (this.controlId != null) {
            tag.putUUID("ControlId", this.controlId);
        }
        if (this.teamId != null) {
            tag.putUUID("TeamId", this.teamId);
        }
        tag.putFloat("DownedRoll", this.downedRoll());
        if (this.isDud()) {
            Vector3f h = this.dudHeading();
            tag.putFloat("DudHX", h.x);
            tag.putFloat("DudHY", h.y);
            tag.putFloat("DudHZ", h.z);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setModelId(MissileModels.parse(tag.getString("ModelId")));
        this.setExhaustColor(tag.getInt("ExhaustColor"));
        this.entityData.set(FLIGHT_SOUND, tag.getString("FlightSound"));
        this.entityData.set(LOOK, tag.getString("Look"));
        this.setFlightSoundShape(tag.getDouble("FlightSoundRange"), tag.getFloat("FlightSoundBasePitch"),
                tag.getDouble("FlightSoundSpeedPitch"));
        this.controlId = tag.hasUUID("ControlId") ? tag.getUUID("ControlId") : null;
        this.teamId = tag.hasUUID("TeamId") ? tag.getUUID("TeamId") : null;
        this.flight.load(tag.getCompound("Flight"));
        this.motor.load(tag.getCompound("Motor"));
        this.fuze.load(tag.getCompound("Fuze"));
        this.damage.load(tag.getCompound("Damage"));
        this.swarm.load(tag.getCompound("Swarm"));
        this.interceptor.load(tag.getCompound("Interceptor"));
        this.signature.load(tag.getCompound("Signature"));
        this.seeker.load(tag.getCompound("Seeker"));
        this.syncDownedRoll(tag.getFloat("DownedRoll"));
        if (tag.contains("DudHX")) {
            this.syncDud(new Vector3f(tag.getFloat("DudHX"), tag.getFloat("DudHY"), tag.getFloat("DudHZ")));
        }
    }

    public enum Phase {
        /** Boost straight up clear of surrounding terrain. */
        ASCEND,
        /** Toward the target at a terrain-safe altitude. */
        CRUISE,
        /** Terminal dive. */
        ATTACK
    }

    public enum CruiseMode {
        TERRAIN_FOLLOW,
        HIGH_ALTITUDE
    }

    /** What the missile travels through: decides what "terrain" means. */
    public enum Medium {
        AIR(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 32.0, 24.0),
        WATER(Heightmap.Types.OCEAN_FLOOR, 12.0, 8.0);

        private final Heightmap.Types heightmap;
        private final double lookAhead;
        private final double scanRadius;

        Medium(Heightmap.Types heightmap, double lookAhead, double scanRadius) {
            this.heightmap = heightmap;
            this.lookAhead = lookAhead;
            this.scanRadius = scanRadius;
        }

        /** Top of this heightmap = the surface not to run into. */
        public Heightmap.Types heightmap() {
            return this.heightmap;
        }

        /** Terrain fan centre, blocks ahead along the track. */
        public double lookAhead() {
            return this.lookAhead;
        }

        /** Terrain fan half-width, blocks. */
        public double scanRadius() {
            return this.scanRadius;
        }

        /** Bounded above as well as below. */
        public boolean hasCeiling() {
            return this == WATER;
        }
    }

    /** Behaviour when shot out of the sky; per preset. */
    public enum DownedAction {
        /** Neutralised fizzle mid-air where hit. */
        FIZZLE,
        /** Full warhead mid-air where hit. */
        DETONATE,
        /** Power cut, ballistic with light drag; fizzles at the crash site. */
        CRASH,
        /** Random-walk heading under power, then full warhead on impact. */
        SPIN_OUT,
        /** Engines cut, hard deceleration; fizzles at the crash site. */
        POWER_LOSS
    }

    /** Re-acquire closest hostile each tick, or home on one UUID. */
    public enum InterceptMode {
        NEAREST,
        LOCK
    }

    /** SOLID = preloaded charge; LIQUID = kerosene ({@code WFFluids}). */
    public enum FuelType {
        SOLID,
        LIQUID
    }
}
