package com.wf.wflib;

import com.mojang.logging.LogUtils;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.api.WFTelemetry;
import com.wf.wflib.api.WFTelemetryService;
import com.wf.wflib.attitude.MissileAttitude;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.chunk.MissileChunkLoader;
import com.wf.wflib.chunk.DetonationChunkGuard;
import com.wf.wflib.debug.MissileDebug;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.entity.ModelledProjectile;
import com.wf.wflib.entity.OBBEntity;
import com.wf.wflib.flight.*;
import com.wf.wflib.recon.ContactClass;
import org.jetbrains.annotations.Nullable;
import com.wf.wflib.fx.ExplosionCreator;
import com.wf.wflib.network.MissileFlightAudioPacket;
import com.wf.wflib.sim.IMissileListener;
import com.wf.wflib.sim.MissileListenerRegistry;
import com.wf.wflib.sim.MissileSimConfig;
import net.minecraft.tags.DamageTypeTags;
import com.wf.wflib.sim.SimMissileManager;
import com.wf.wflib.swarm.SwarmManager;
import com.wf.wflib.util.OBB;
import com.wf.wflib.util.SweptCollision;
import com.wf.wflib.warhead.RecursiveFrag;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.damage.MissileDamageRegistry;
import com.wf.wflib.damage.MissileDamageResponse;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MissileEntity extends Projectile implements OBBEntity, InterceptTarget, IMissileListener, WarheadCarrier,
        ModelledProjectile {

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
    private static final double DIVE_RAYCAST_RANGE = 64.0;
    private static final int DIVE_ANGLE_SAMPLES = 6;
    public static final double ARMING_DISTANCE = 6.0;
    // Interception damage pool: CIWS fire / interceptors chip this down; at <= 0 the missile is destroyed.
    public static final float DEFAULT_HEALTH = 50.0f;
    public static final double DEFAULT_ACCELERATION = 0.15;
    public static final double DEFAULT_DECELERATION = 0.25;
    /** Default distance (blocks) at which a missile's flight loop fades to silence and the server broadcasts it. */
    public static final double DEFAULT_FLIGHT_SOUND_RANGE = 300.0;
    /** Default ceiling (blocks) on how far out a directional strike joins its attack line (see ApproachStage). */
    public static final double DEFAULT_APPROACH_JOIN_CAP = 1500.0;
    private static final double DIVE_ACCELERATION = 1.5;
    private static final double FUEL_OUT_GRAVITY = 0.05;
    private static final double TERMINAL_FALL_SPEED = -3.9;
    private static final double FALL_HORIZONTAL_DRAG = 0.99;
    private static final double POWER_LOSS_DRAG = 0.93;
    private static final double SPINOUT_GRAVITY = 0.03;
    private static final int SPINOUT_MIN_FUSE = 15;
    private static final int SPINOUT_MAX_FUSE = 45;
    private static final int DOWNED_REHIT_GRACE = 60;
    private static final double SURFACE_MARGIN = 2.0;
    private static final int MAX_SUBMERGENCE = 96;
    // Consecutive ticks out of the water before a submerged missile is written off (see tickBroach). A second.
    private static final int BROACH_GRACE_TICKS = 20;
    // Downward nudge (blocks/tick^2) applied while it is broached, on top of cutting the climb.
    private static final double BROACH_RECOVERY = 0.04;
    private static final double BOOST_SPEED_MULT = 2.0;
    private static final int BOOST_DURATION = 8;
    private static final int BOOST_FUEL_COST = 150;
    private static final double JINK_DEFLECT = 0.6;
    private static final double FORMATION_GAIN = 0.25;
    private static final double FORMATION_MAX_OVERSPEED = 1.6;
    private static final double SATURATION_SPREAD = 10.0;
    // Ticks between sonic-boom shock rings while travelling supersonic.
    private static final int SONIC_BOOM_INTERVAL = 6;
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    // Synced so the client-side exhaust trail can tint itself per missile (see InstancedTrailEffect).
    private static final EntityDataAccessor<Integer> EXHAUST_COLOR =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SUBMERGED_MEDIUM =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> FLIGHT_SOUND =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    private static final double TURN_AGILITY = 1.0; // radians * (model units) per tick
    private static final double AVOID_RADIUS = 24.0;
    private static final int AVOID_HORIZON = 20;
    private static final double AVOID_MIN_SEP = 4.0;
    private static final double AVOID_STRENGTH = 1.5;
    private static final double ARMING_DISTANCE_SQ = ARMING_DISTANCE * ARMING_DISTANCE;
    // Failsafe: arm anyway after this many ticks so a missile that somehow can't travel never stays a live dud.
    private static final int ARMING_FAILSAFE_TICKS = 100;
    public static int DEFAULT_FUEL_TICKS = 1200;
    /**
     * Scratch for {@link #refreshObb}, which runs every tick for every missile that moved, and again for every
     * hit-check that asks for the box.
     */
    private final Vector3f obbHeading = new Vector3f();
    private final Quaternionf obbOrientation = new Quaternionf();
    private final Quaterniond obbRotation = new Quaterniond();
    private final Vector3d obbScratch = new Vector3d();
    private final OBB obb = new OBB(new Vector3d(), new Vector3d(), new Quaterniond(), OBB.Part.BODY);
    private final List<OBB> obbList = List.of(this.obb);
    // Forces the chunks the missile needs while flying (own chunk ticking + non-ticking look-ahead fan).
    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();
    private WFTelemetry telemetry;
    private boolean telemetryInit;
    private boolean fuelOutRecorded;
    private double obbX = Double.NaN, obbY, obbZ, obbDx, obbDy, obbDz;
    private Vec3 target = Vec3.ZERO;
    private Phase phase = Phase.ASCEND;
    private ResourceLocation ascentStageId = FlightStageRegistry.defaultId(Phase.ASCEND);
    private ResourceLocation cruiseStageId = FlightStageRegistry.defaultId(Phase.CRUISE);
    private ResourceLocation attackStageId = FlightStageRegistry.defaultId(Phase.ATTACK);
    private FlightProfile flightProfile = FlightProfile.fromIds(this.ascentStageId, this.cruiseStageId, this.attackStageId);
    // Loitering-munition timer: ticks spent orbiting on-station (see LoiterStage). Persisted.
    private int loiterTicks = 0;
    private boolean diveCommitted = false;
    private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
    // What the missile travels through, and so which way up its terrain scan reads. Persisted.
    private Medium medium = Medium.AIR;
    private int dryTicks = 0;
    private double cruiseAltitude = 200.0;
    private double terrainClearance = 24.0;
    private double cruiseTargetY = Double.NaN;
    private double cruiseSpeed = CRUISE_SPEED;
    private double ascentSpeed = Double.NaN;
    private double attackAngle = Double.NaN;
    private double minDiveAngle = DEFAULT_MIN_DIVE_ANGLE;
    private double maxDiveAngle = DEFAULT_MAX_DIVE_ANGLE;
    private Vec3 attackApproachDir = null;
    private AttackProfile attackProfile = AttackProfile.SPEED;
    private double approachJoinCap = DEFAULT_APPROACH_JOIN_CAP;
    private float explosionOffset = 0.0f;
    private ResourceLocation detonationId = WarheadRegistry.defaultId();
    // Number of bomblets the FRAGMENTATION warhead scatters; per-missile, set via the Builder.
    private int fragmentCount = DEFAULT_FRAGMENT_COUNT;
    // Chunk radius force-loaded around the aim point during the terminal run (see DEFAULT_IMPACT_PRELOAD_RADIUS).
    private int impactPreloadRadius = DEFAULT_IMPACT_PRELOAD_RADIUS;
    private WarheadRegistry.Detonation detonation = WarheadRegistry.STANDARD;
    private ResourceLocation damageResponseId = MissileDamageRegistry.defaultId();
    private MissileDamageResponse damageResponse = MissileDamageRegistry.STANDARD;
    private double maxTurnRate = TURN_AGILITY / MissileModels.length(MissileModels.DEFAULT);
    private double flightSoundRange = DEFAULT_FLIGHT_SOUND_RANGE;
    private float flightSoundBasePitch = 1.0f;
    private double flightSoundSpeedPitch = 0.0;
    // Consecutive ticks spent in CRUISE; gates the offload-to-simulation transition.
    private int cruiseTicks = 0;
    private float health = DEFAULT_HEALTH;
    private boolean detonated = false;
    private boolean downed = false;
    private DownedAction downedAction = DownedAction.CRASH;
    private Vec3 spinOutDir = null;
    private int spinOutFuse = 0;
    private int downedGrace = 0; // ticks left before a re-hit may force-detonate this downed missile
    private Vec3 launchPos = null;
    private boolean armed = false;
    private int splitDepth = 0;
    private long swarmId = 0L;
    private boolean commander = false;
    private boolean brokeFormation = false;
    private UUID controlId = null;
    private UUID teamId = null;

    private boolean interceptor = false;
    // NEAREST re-acquires the closest hostile missile each tick; LOCK homes on one specific target UUID.
    private InterceptMode interceptMode = InterceptMode.NEAREST;
    // The specific target's UUID in LOCK mode (null in NEAREST mode).
    private UUID lockTargetId = null;
    // Per-interceptor kill probability rolled on closest approach (default 90%).
    private float interceptChance = MissileSimConfig.DEFAULT_INTERCEPT_CHANCE;
    private UUID currentTargetId = null;
    private int noTargetTicks = 0;
    private boolean crossingShot = false;

    private UUID designatedTargetId = null;

    private float rcs = 1.0f;
    private float evasion = 0.0f;
    private boolean evasiveManeuver = false;
    // Remaining ticks of an active evasive speed burst (see evadeBoost / the guidance boost step).
    private int boostTicks = 0;
    private Vec3 boostManeuver = null;

    private FuelType fuelType = FuelType.SOLID;
    private int fuelCapacity = DEFAULT_FUEL_TICKS; // initial tank size (telemetry / refuel reference)
    private int fuel = DEFAULT_FUEL_TICKS;         // remaining ticks of powered flight
    private double acceleration = DEFAULT_ACCELERATION;
    private double deceleration = DEFAULT_DECELERATION;

    public MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);

        this.noPhysics = true;
        this.setNoGravity(true);
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

    private static double approach(double cur, double target, double maxDelta) {
        double diff = target - cur;
        if (Math.abs(diff) <= maxDelta) {
            return target;
        }
        return cur + Math.copySign(maxDelta, diff);
    }

    /**
     * @return the target UUIDs already "claimed" by interceptors in {@code missiles}, from the perspective of
     *      a claimant with entity id {@code selfId} (pass {@link Integer#MAX_VALUE} for a battery, which yields to
     *      every interceptor). A LOCK interceptor always claims its lock target; a NEAREST interceptor claims its
     *      current target only against higher-id peers, so exactly one interceptor keeps a shared target and the
     *      rest divert to other threats (no dogpiling, and no two interceptors swapping off each other forever).
     */
    public static Set<UUID> claimedTargets(List<MissileEntity> missiles, int selfId) {
        Set<UUID> claimed = new HashSet<>();
        for (MissileEntity o : missiles) {
            if (!o.interceptor || o.getId() == selfId) {
                continue;
            }
            if (o.interceptMode == InterceptMode.LOCK && o.lockTargetId != null) {
                claimed.add(o.lockTargetId);
            } else if (o.currentTargetId != null && o.getId() < selfId) {
                claimed.add(o.currentTargetId);
            }
        }
        return claimed;
    }

    private static double smallestPositive(double a, double b) {
        if (a > 0.0 && b > 0.0) {
            return Math.min(a, b);
        }
        if (a > 0.0) {
            return a;
        }
        return b > 0.0 ? b : -1.0;
    }

    /**
     * A visible/audible flak burst at the intercept point so a kill and a miss are easy to tell apart at range: a
     * bright burst + explosion boom on a kill, a small puff + lighter pop on a miss.
     */
    private static void spawnInterceptBurst(ServerLevel level, Vec3 p, boolean kill) {
        level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y, p.z, kill ? 3 : 1, 1.0, 1.0, 1.0, 0.0);
        level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, kill ? 24 : 6, 1.4, 1.4, 1.4, 0.06);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, kill ? 14 : 5, 1.6, 1.6, 1.6, 0.02);
        level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
                kill ? 3.0f : 1.3f, kill ? 1.0f : 1.5f);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            // Keep the AABB wrapping the oriented model each frame so culling / F3+B stay correct.
            this.setBoundingBox(this.makeBoundingBox());
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level();

        if (this.launchPos == null) {
            this.launchPos = this.position();
        }

        Vec3 currentPos = this.position();

        this.initTelemetry();

        if (!this.interceptor && !this.downed) {
            MissileListenerRegistry.get(serverLevel).noteThreat(currentPos, serverLevel.getGameTime());
        }

        if (this.fuel <= 0) {
            if (!this.fuelOutRecorded && !this.downed) {
                this.fuelOutRecorded = true;
                this.chunkLoader.setTargetPreload(currentPos, Math.max(1, this.impactPreloadRadius));
                this.recordEvent(WFEventType.FUEL_OUT, "ballistic");
            }
            if (this.downed) {
                this.tickDowned(serverLevel, currentPos);
            } else {
                this.ballisticFall(serverLevel, currentPos);
            }
            return;
        }
        this.fuel--;

        if (this.interceptor && !this.updateInterceptTarget(serverLevel, currentPos)) {
            return;
        }

        if (!this.interceptor && this.designatedTargetId != null) {
            Entity designated = serverLevel.getEntity(this.designatedTargetId);
            if (designated != null && designated.isAlive() && designated != this) {
                this.setTarget(designated.getBoundingBox().getCenter());
            }
        }

        Vec3 targetPos = this.getTarget();

        double dx = targetPos.x - currentPos.x;
        double dz = targetPos.z - currentPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        boolean loadFan = this.cruiseMode == CruiseMode.TERRAIN_FOLLOW
                || this.phase == Phase.ATTACK
                || horizontalDist < MissileSimConfig.FAN_TERMINAL_RANGE;
        boolean preloadTarget = !this.interceptor && this.isArmed() && this.impactPreloadRadius > 0
                && (this.phase == Phase.ATTACK || horizontalDist < MissileSimConfig.FAN_TERMINAL_RANGE);
        this.chunkLoader.setTargetPreload(preloadTarget ? targetPos : null, this.impactPreloadRadius);
        this.chunkLoader.update(this, serverLevel, currentPos, this.getDeltaMovement(), loadFan);

        // Guidance: delegate "how it flies" to the active flight stage for this phase.
        double nx = 0.0;
        double nz = 0.0;
        if (horizontalDist > 1.0E-3) {
            nx = dx / horizontalDist;
            nz = dz / horizontalDist;
        }
        double safeAltitude = this.computeSafeAltitude(currentPos, dx, dz, horizontalDist);
        FlightContext ctx = new FlightContext(currentPos, targetPos, horizontalDist, nx, nz, safeAltitude,
                this.computeSafeCeiling(currentPos));

        MissileEntity commander = (this.swarmId != 0L && !this.commander && !this.brokeFormation)
                ? SwarmManager.commander(serverLevel, this.swarmId) : null;
        if (commander != null && commander.isAlive() && commander.getPhase() == Phase.ATTACK) {
            this.brokeFormation = true;
            this.setTarget(this.saturationAim(commander.getTarget()));
            this.phase = Phase.ATTACK;
            this.recordEvent(WFEventType.ATTACK, "saturation break");
            commander = null;
        }
        boolean inFormation = commander != null && commander != this && commander.isAlive();

        Vec3 velocity;
        if (inFormation) {
            velocity = this.formationGuide(commander);
        } else {
            // Advance the phase (each stage decides when it is done), then fly the resulting phase's stage.
            Phase next = this.flightProfile.stage(this.phase).next(this, ctx);
            if (next != null) {
                this.phase = next;
                this.recordEvent(phaseEvent(next), "");
            }
            this.cruiseTicks = (this.phase == Phase.CRUISE) ? this.cruiseTicks + 1 : 0;

            velocity = this.flightProfile.stage(this.phase).guide(this, ctx);

            if (this.controlId != null || this.swarmId != 0L) {
                velocity = velocity.add(this.avoidFriendlies());
            }
        }

        velocity = constrainTurn(this.getDeltaMovement(), velocity, this.maxTurnRate);
        if (this.boostTicks > 0) {
            this.boostTicks--;
            velocity = velocity.scale(BOOST_SPEED_MULT);
            if (this.boostManeuver != null) {
                double sp = velocity.length();
                if (sp > 1.0E-6) {
                    Vec3 deflected = velocity.scale(1.0 / sp).add(this.boostManeuver.scale(JINK_DEFLECT));
                    double dlen = deflected.length();
                    if (dlen > 1.0E-6) {
                        velocity = deflected.scale(sp / dlen);
                    }
                }
            }
            serverLevel.sendParticles(ParticleTypes.FLAME, this.getX(), this.getY(), this.getZ(),
                    6, 0.3, 0.3, 0.3, 0.02);
        } else {
            this.boostManeuver = null; // burst over: drop the stale break so the next dodge picks a fresh one
        }
        velocity = this.applyThrust(velocity);

        this.setDeltaMovement(velocity);
        this.logFlightDebug(ctx);

        this.hasImpulse = true;
        this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());

        // Position and heading just changed: re-fit the OBB and the vanilla AABB around the model.
        this.setBoundingBox(this.makeBoundingBox());

        // Checked after the move, not before it: what matters is whether it ended the tick in the water.
        if (this.tickBroach()) {
            return;
        }

        // Sonic boom
        double machSpeed = this.getDeltaMovement().length();
        if (machSpeed >= MissileSimConfig.SUPERSONIC_SPEED
                && (this.tickCount + this.getId()) % SONIC_BOOM_INTERVAL == 0) {
            ExplosionCreator.sonicBoom(this.level(), this.getX(), this.getY(), this.getZ(),
                    (float) Mth.clamp(machSpeed * 1.5, 6.0, 24.0));
        }

        if (this.tickCount % MissileFlightAudioPacket.UPDATE_INTERVAL == 0) {
            MissileFlightAudioPacket.broadcastEntity(this);
        }

        // Interceptor kill check
        if (this.interceptor && this.tryIntercept(currentPos)) {
            return;
        }

        if (!this.interceptor
                && !this.medium.hasCeiling()
                && this.phase == Phase.CRUISE
                && this.cruiseTicks > MissileSimConfig.CRUISE_SIM_DELAY_TICKS
                && horizontalDist > MissileSimConfig.DESTINATION_RANGE
                && !SimMissileManager.nearAnyListener(serverLevel, this.position())) {
            if (this.swarmId == 0L) {
                SimMissileManager.startSim(this);
                return;
            }
            if (this.commander) {
                List<MissileEntity> subs = this.formationSubordinates(serverLevel);
                if (subs != null) {
                    SimMissileManager.startSimSwarm(this, subs);
                    return;
                }
            }
        }

        // Detonation triggers are inert until the missile has armed clear of the launcher.
        if (this.isArmed()) {
            // Airburst fuze
            if (this.explosionOffset > 0.0f && this.phase == Phase.ATTACK
                    && this.getY() - targetPos.y <= this.explosionOffset) {
                this.detonate(this.position());
                return;
            }

            HitResult hitResult = this.sweepForImpact(currentPos, this.getDeltaMovement());

            if (hitResult.getType() != HitResult.Type.MISS) {
                this.onMissileImpact(hitResult);
            }
        }
    }

    /**
     * DEBUG: dump the flight state that explains a missilelet spinning instead of impacting (where it's aiming, how
     * far the aim point sits above the actual ground beneath it ({@code tgtAGL} > 0 means it's aiming at empty air,
     * e.g.
     */
    private void logFlightDebug(FlightContext ctx) {
        if (this.swarmId == 0L && !RecursiveFrag.ID.equals(this.detonationId)) {
            return;
        }
        if (this.tickCount % LOG_INTERVAL != 0) {
            return;
        }
        Vec3 pos = ctx.position();
        Vec3 target = ctx.target();
        Vec3 vel = this.getDeltaMovement();
        double vh = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        double closing = vel.x * ctx.nx() + vel.z * ctx.nz(); // > 0 gaining on the aim point, <= 0 overflown
        double turnRadius = this.maxTurnRate > 1.0E-4 ? vh / this.maxTurnRate : 0.0;
        double diveAngle = this.resolveDiveAngle(ctx);
        int groundUnderMissile = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(pos.x), Mth.floor(pos.z));
        int groundUnderTarget = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(target.x), Mth.floor(target.z));
        LOGGER.info("[MSL id={} swarm={} depth={} phase={} age={} commit={}] pos=({},{},{}) tgt=({},{},{}) dist2D={} "
                        + "vy={} vh={} spd={} closing={} turnR={} diveAng={} diveRange=[{},{}] fuze={} altAGL={} tgtAGL={}",
                this.getId(), this.swarmId, this.splitDepth, this.phase, this.tickCount, this.diveCommitted,
                f1(pos.x), f1(pos.y), f1(pos.z), f1(target.x), f1(target.y), f1(target.z),
                f1(ctx.horizontalDist()), f1(vel.y), f1(vh), f1(vel.length()), f1(closing), f1(turnRadius),
                f1(diveAngle), f1(this.minDiveAngle), f1(this.maxDiveAngle),
                f1(this.explosionOffset), f1(pos.y - groundUnderMissile), f1(target.y - groundUnderTarget));
    }

    private static String f1(double v) {
        return String.format("%.1f", v);
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

    private void initTelemetry() {
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

    private static WFEventType phaseEvent(Phase phase) {
        return switch (phase) {
            case ASCEND -> WFEventType.ASCEND;
            case CRUISE -> WFEventType.CRUISE;
            case ATTACK -> WFEventType.ATTACK;
        };
    }

    /** Whether the warhead is live. */
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
     * The altitude the missile should hold this tick: the scanned terrain top (looked ahead toward the target once
     * past ascent) plus the terrain clearance.
     */
    private double computeSafeAltitude(Vec3 pos, double dx, double dz, double horizontalDist) {
        Medium med = this.medium;
        double scanCenterX = pos.x;
        double scanCenterZ = pos.z;
        if (this.phase != Phase.ASCEND && horizontalDist > 1.0E-3) {
            scanCenterX += (dx / horizontalDist) * med.lookAhead();
            scanCenterZ += (dz / horizontalDist) * med.lookAhead();
        }
        double waterline = this.medium.hasCeiling() ? this.fluidSurfaceAbove(pos) : Double.NaN;
        double terrainSafe = scanTerrainTop(med.heightmap(), scanCenterX, scanCenterZ, med.scanRadius(),
                Double.isNaN(waterline) ? Double.POSITIVE_INFINITY : waterline) + this.terrainClearance;
        if (this.cruiseMode != CruiseMode.HIGH_ALTITUDE) {
            return terrainSafe;
        }
        if (med == Medium.WATER) {
            double surface = this.fluidSurfaceAbove(pos);
            return Double.isNaN(surface) ? terrainSafe : Math.max(terrainSafe, surface - this.cruiseAltitude);
        }
        return Math.max(this.cruiseAltitude, terrainSafe);
    }

    /** The highest altitude the missile may hold, or {@link Double#POSITIVE_INFINITY} when nothing caps it. */
    private double computeSafeCeiling(Vec3 pos) {
        if (!this.medium.hasCeiling()) {
            return Double.POSITIVE_INFINITY;
        }
        double surface = this.fluidSurfaceAbove(pos);
        return Double.isNaN(surface) ? Double.POSITIVE_INFINITY : surface - SURFACE_MARGIN;
    }

    /**
     * @return the y of the surface of the fluid standing at {@code pos} (the bottom face of the first
     *      non-fluid block above it), or {@link Double#NaN} when {@code pos} is not in a fluid at all, or when the
     *      column above it is fluid for {@link #MAX_SUBMERGENCE} blocks without reaching one (water under an
     *      overhang, or an aquifer sealed under stone, has no surface to broach through).
     */
    private double fluidSurfaceAbove(Vec3 pos) {
        BlockPos cursor = BlockPos.containing(pos);
        if (this.level().getFluidState(cursor).isEmpty()) {
            return Double.NaN;
        }
        for (int step = 0; step < MAX_SUBMERGENCE; step++) {
            BlockPos above = cursor.above();
            if (this.level().getFluidState(above).isEmpty()) {
                return above.getY();
            }
            cursor = above;
        }
        return Double.NaN;
    }

    /**
     * Keeps a submerged missile submerged, and writes off one that will not go back under.
     *
     * @return true if the missile was written off this tick, and the caller must stop ticking it.
     */
    private boolean tickBroach() {
        if (!this.medium.hasCeiling() || this.phase == Phase.ASCEND || this.isSubmerged()) {
            this.dryTicks = 0;
            return false;
        }
        this.dryTicks++;
        if (this.dryTicks < BROACH_GRACE_TICKS) {
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(v.x, Math.min(v.y, 0.0) - BROACH_RECOVERY, v.z);
            return false;
        }
        this.recordEvent(WFEventType.DESTROYED, "broached");
        this.shootDown(DownedAction.FIZZLE);
        return true;
    }

    /**
     * Scans safe height based on mc heightmap.
     *
     * @param cutoff tops at or above this are not terrain this vehicle could ever hold clearance over, and are
     *      dropped from the fan rather than raising its answer. {@link Double#POSITIVE_INFINITY} for
     *      anything flying in air, where every ridge counts; the waterline for anything submerged,
     *      where a column reaching it is a bank and not a seabed.
     */
    private double scanTerrainTop(Heightmap.Types heightmap, double centerX, double centerZ, double radius,
                                  double cutoff) {
        int r = (int) Math.ceil(radius);
        int step = Math.max(2, r / 4);
        int cx = Mth.floor(centerX);
        int cz = Mth.floor(centerZ);

        int maxTop = this.level().getMinBuildHeight();
        boolean sampled = false;
        ServerLevel server = this.level() instanceof ServerLevel sl ? sl : null;
        for (int ox = -r; ox <= r; ox += step) {
            for (int oz = -r; oz <= r; oz += step) {
                int wx = cx + ox;
                int wz = cz + oz;
                if (server == null) {
                    int clientTop = this.level().getHeight(heightmap, wx, wz);
                    if (clientTop < cutoff) {
                        maxTop = Math.max(maxTop, clientTop);
                        sampled = true;
                    }
                    continue;
                }
                LevelChunk chunk = server.getChunkSource().getChunkNow(
                        SectionPos.blockToSectionCoord(wx), SectionPos.blockToSectionCoord(wz));
                if (chunk == null) {
                    continue;
                }
                int top = chunk.getHeight(heightmap, wx, wz) + 1;
                if (top >= cutoff) {
                    continue; // dry land: not a floor, and not something this vehicle can get above
                }
                sampled = true;
                if (top > maxTop) {
                    maxTop = top;
                }
            }
        }
        return sampled ? maxTop : this.getY() - this.terrainClearance;
    }

    protected boolean canHitEntity(Entity target) {
        if (target == this || target.isSpectator() || !target.isAlive()) {
            return false;
        }
        if (this.interceptor && target instanceof InterceptTarget) {
            return false;
        }
        if (target instanceof MissileEntity other) {
            if (this.interceptor || other.interceptor) {
                return false;
            }
            return !this.isFriendly(other);
        }
        return target.isPickable();
    }

    /**
     * @return true if {@code other} is on the same side. A non-missile target has no swarm to share, so it
     *      comes down to the launcher that owns it and the faction it flies for.
     */
    private boolean isFriendly(InterceptTarget other) {
        if (other instanceof MissileEntity missile) {
            return this.isFriendly(missile);
        }
        UUID control = other.interceptControlId();
        if (this.controlId != null && this.controlId.equals(control)) {
            return true;
        }
        return WarforgeCompat.areFactionsFriendly(this.teamId, other.interceptTeamId());
    }

    /**
     * @return true if {@code other} is on the same side: same swarm (frag family) or launcher (control id).
     */
    private boolean isFriendly(MissileEntity other) {
        if (SwarmManager.sameSwarm(this, other)) {
            return true;
        }
        if (this.controlId != null && this.controlId.equals(other.controlId)) {
            return true;
        }
        // Same / allied / truced WarForge faction
        return WarforgeCompat.areFactionsFriendly(this.teamId, other.teamId);
    }

    public UUID getTeamId() {
        return this.teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    /** A missile's warhead blast is attributed to the missile's owning faction (see {@link #getTeamId}). */
    @Override
    public UUID igniterFactionId() {
        return this.teamId;
    }

    /**
     * A non-zero pick radius so {@code MixinProjectileUtil} inflates the target OBB when another missile sweeps
     * through it: makes fast crossing intercepts land reliably instead of needing a pixel-perfect centerline
     * crossing.
     */
    @Override
    public float getPickRadius() {
        return 0.5f;
    }

    /**
     * Swept, substepped block/entity collision over the traversed segment, extended forward by the body length so
     * the nose (not the base origin) triggers the hit.
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

    /**
     * Rebuilds the body OBB (center, extents, rotation) from the current missile model and heading, unless the
     * position and heading are unchanged since the last build (then it's a no-op).
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

        ResourceLocation modelId = this.getModelId();
        Vec3 dims = MissileModels.dimensions(modelId);
        Vec3 localCenter = MissileModels.center(modelId);

        double lenSq = move.lengthSqr();
        Quaterniond rot = obbRotation.identity();
        if (lenSq > 1.0E-8) {
            double inv = 1.0 / Math.sqrt(lenSq);
            MissileAttitude attitude = MissileAttitudeRegistry.get(MissileModels.attitudeId(modelId));
            obbHeading.set((float) (move.x * inv), (float) (move.y * inv), (float) (move.z * inv));
            Quaternionf q = attitude.orientation(obbHeading, obbOrientation);
            rot.set(q.x, q.y, q.z, q.w);
        }
        // else: identity rotation (nose points straight up), which matches the ASCEND launch pose.

        // Box center = entity position + the rotated model-center offset (meshes sit base-at-origin).
        Vector3d worldCenter = obbScratch.set(localCenter.x, localCenter.y, localCenter.z);
        rot.transform(worldCenter);
        worldCenter.add(x, y, z);
        this.obb.setCenter(worldCenter);

        this.obb.setExtents(obbScratch.set(dims.x * 0.5, dims.y * 0.5, dims.z * 0.5));
        this.obb.updateRotation(rot);
    }

    /**
     * The vanilla AABB is fit tightly around the oriented model (the enclosing box of the OBB's corners), so both
     * frustum culling and the F3+B hitbox reflect the actual missile rather than a fixed cube.
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
        Vector3d c = this.obb.center();
        Vector3d[] ax = this.obb.getAxes();
        double hx = ext.x * Math.abs(ax[0].x) + ext.y * Math.abs(ax[1].x) + ext.z * Math.abs(ax[2].x);
        double hy = ext.x * Math.abs(ax[0].y) + ext.y * Math.abs(ax[1].y) + ext.z * Math.abs(ax[2].y);
        double hz = ext.x * Math.abs(ax[0].z) + ext.y * Math.abs(ax[1].z) + ext.z * Math.abs(ax[2].z);
        return new AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz);
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
    }

    /** Memoised {@link #getModelId}. */
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

    /**
     * @return the exhaust trail tint (hot RGB, 0xRRGGBB) the client-side plume fades from.
     */
    public int getExhaustColor() {
        return this.entityData.get(EXHAUST_COLOR);
    }

    public void setExhaustColor(int rgb) {
        this.entityData.set(EXHAUST_COLOR, rgb);
    }

    /**
     * @return the looping flight sound this missile plays client-side, or {@link WFSounds#MISSILE_FLIGHT} when
     *      none was set or the stored id doesn't resolve to a registered sound.
     */
    public SoundEvent getFlightSound() {
        String id = this.entityData.get(FLIGHT_SOUND);
        if (id != null && !id.isEmpty()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(rl);
                if (event != null) {
                    return event;
                }
            }
        }
        return WFSounds.MISSILE_FLIGHT.get();
    }

    /**
     * Set the looping flight sound by id (must be a registered {@link SoundEvent}); {@code null} restores the
     * default loop.
     */
    public void setFlightSound(ResourceLocation id) {
        this.entityData.set(FLIGHT_SOUND, id == null ? "" : id.toString());
    }

    /** The raw per-missile flight-sound override id, or {@code null} when it uses the default loop. */
    public ResourceLocation getFlightSoundId() {
        String id = this.entityData.get(FLIGHT_SOUND);
        return (id == null || id.isEmpty()) ? null : ResourceLocation.tryParse(id);
    }

    /** Distance (blocks) at which this missile's flight loop fades to silence / the server broadcasts it. */
    public double getFlightSoundRange() {
        return this.flightSoundRange;
    }

    /** Idle engine pitch of this missile's flight loop. */
    public float getFlightSoundBasePitch() {
        return this.flightSoundBasePitch;
    }

    /** Added flight-loop pitch per block/tick of this missile's own speed (engine rev); 0 = constant. */
    public double getFlightSoundSpeedPitch() {
        return this.flightSoundSpeedPitch;
    }

    public Vec3 getTarget() {
        return this.target;
    }

    public void setTarget(Vec3 target) {
        this.target = target;
    }

    public Phase getPhase() {
        return this.phase;
    }

    public CruiseMode getCruiseMode() {
        return this.cruiseMode;
    }

    /**
     * @return what this missile travels through. {@link Medium#WATER} is a torpedo: its terrain is the seabed
     *      and it is bounded above by the surface.
     */
    public Medium getMedium() {
        return this.medium;
    }

    private void setMedium(Medium medium) {
        this.medium = medium;
        this.entityData.set(SUBMERGED_MEDIUM, medium.hasCeiling());
    }

    /**
     * @return true if this missile travels submerged, readable on the client. Not the same question as
     *      {@link #isSubmerged}, which asks where it is right now: a torpedo still falling toward the sea after an
     *      air launch answers true here and false there.
     */
    public boolean isSubmergedMedium() {
        return this.entityData.get(SUBMERGED_MEDIUM);
    }

    /**
     * @return true if the missile's own block is standing in a fluid. The test the submerged stages, the
     *      broach check and the client wake all share, so "in the water" means one thing everywhere.
     */
    public boolean isSubmerged() {
        return !this.level().getFluidState(this.blockPosition()).isEmpty();
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

    public static double ascentSpeedFor(double cruiseSpeed) {
        return Math.max(MIN_ASCENT_SPEED, cruiseSpeed * ASCENT_SPEED_FACTOR);
    }

    public double getAscentSpeed() {
        return Double.isNaN(this.ascentSpeed) ? ascentSpeedFor(this.cruiseSpeed) : this.ascentSpeed;
    }

    public double getLaunchY() {
        return this.launchPos != null ? this.launchPos.y : this.getY();
    }

    /**
     * @return the explicit preferred dive angle in degrees below horizontal (90 = straight down, uncapped), or
     *      {@link Double#NaN} to auto-pick within [{@link #getMinDiveAngle()}, {@link #getMaxDiveAngle()}].
     */
    public double getAttackAngle() {
        return this.attackAngle;
    }

    public double getMinDiveAngle() {
        return this.minDiveAngle;
    }

    public double getMaxDiveAngle() {
        return this.maxDiveAngle;
    }

    /**
     * @return the normalized horizontal direction from the target toward the commanded approach side, or
     *      {@code null} for an unconstrained strike. See {@link #attackApproachDir}.
     */
    @Nullable
    public Vec3 getAttackApproachDir() {
        return this.attackApproachDir;
    }

    public AttackProfile getAttackProfile() {
        return this.attackProfile;
    }

    public void setAttackProfile(AttackProfile profile) {
        this.attackProfile = profile == null ? AttackProfile.SPEED : profile;
    }

    public double getApproachJoinCap() {
        return this.approachJoinCap;
    }

    public void setApproachJoinCap(double cap) {
        this.approachJoinCap = cap;
    }

    /**
     * @return the horizontal direction the terminal run should travel (into the target), i.e. the negation of
     *      {@link #getAttackApproachDir()}, or {@code null} for an unconstrained strike.
     */
    @Nullable
    public Vec3 getAttackTravelDir() {
        return this.attackApproachDir == null ? null
                : new Vec3(-this.attackApproachDir.x, 0.0, -this.attackApproachDir.z);
    }

    /**
     * Set the side the missile approaches the target from (a horizontal direction from target to approach origin);
     * normalized, and null/near-zero clears the constraint.
     */
    public void setAttackApproachDir(@Nullable Vec3 dir) {
        if (dir == null) {
            this.attackApproachDir = null;
            return;
        }
        double h = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (h < 1.0E-4) {
            this.attackApproachDir = null;
            return;
        }
        this.attackApproachDir = new Vec3(dir.x / h, 0.0, dir.z / h);
        ResourceLocation approach = FlightStageRegistry.rl(ApproachStage.INSTANCE.id());
        if (!approach.equals(this.cruiseStageId) && FlightStageRegistry.exists(Phase.CRUISE, approach)) {
            this.cruiseStageId = approach;
            this.rebuildFlightProfile();
        }
    }

    /** The dive angle (degrees below horizontal) the terminal stages should fly this tick. */
    public double resolveDiveAngle(FlightContext ctx) {
        if (!Double.isNaN(this.attackAngle)) {
            return this.attackAngle;
        }
        double lo = Math.min(this.minDiveAngle, this.maxDiveAngle);
        double hi = Math.max(this.minDiveAngle, this.maxDiveAngle);
        double dy = this.getY() - ctx.target().y;
        double direct = Math.toDegrees(Math.atan2(dy, Math.max(ctx.horizontalDist(), 1.0e-4)));
        double ideal = Mth.clamp(direct, lo, hi);
        if (hi - lo < 1.0e-4 || ctx.horizontalDist() > DIVE_RAYCAST_RANGE) {
            return ideal;
        }
        double chosen = ideal;
        double bestDelta = Double.POSITIVE_INFINITY;
        boolean anyClear = false;
        for (int i = 0; i <= DIVE_ANGLE_SAMPLES; i++) {
            double a = lo + (hi - lo) * i / DIVE_ANGLE_SAMPLES;
            if (this.diveApproachClear(ctx, a)) {
                double delta = Math.abs(a - ideal);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    chosen = a;
                    anyClear = true;
                }
            }
        }
        return anyClear ? chosen : ideal;
    }

    private boolean diveApproachClear(FlightContext ctx, double angleDeg) {
        double theta = Math.toRadians(angleDeg);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        Vec3 target = ctx.target();
        Vec3 travel = new Vec3(ctx.nx() * cos, -sin, ctx.nz() * cos);
        double dy = this.getY() - target.y;
        double length = Mth.clamp(Math.sqrt(ctx.horizontalDist() * ctx.horizontalDist() + dy * dy), 8.0, DIVE_RAYCAST_RANGE);
        Vec3 aim = target.add(0.0, 0.5, 0.0);
        Vec3 entry = aim.subtract(travel.scale(length));
        BlockHitResult hit = this.level().clip(new ClipContext(entry, aim,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(aim) < 4.0;
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

    public ResourceLocation getAscentStageId() {
        return this.ascentStageId;
    }

    public ResourceLocation getCruiseStageId() {
        return this.cruiseStageId;
    }

    public ResourceLocation getAttackStageId() {
        return this.attackStageId;
    }

    public int getLoiterTicks() {
        return this.loiterTicks;
    }

    public void setLoiterTicks(int loiterTicks) {
        this.loiterTicks = loiterTicks;
    }

    public boolean isDiveCommitted() {
        return this.diveCommitted;
    }

    public void setDiveCommitted(boolean diveCommitted) {
        this.diveCommitted = diveCommitted;
    }

    /**
     * Recompose the runtime flight profile from the current stage ids (call after any id changes).
     */
    private void rebuildFlightProfile() {
        this.flightProfile = FlightProfile.fromIds(this.ascentStageId, this.cruiseStageId, this.attackStageId);
    }

    /**
     * @return the stage that will fly the terminal ATTACK phase (independent of the current phase).
     */
    public FlightStage attackStage() {
        return this.flightProfile.stage(Phase.ATTACK);
    }

    /**
     * Rough remaining time-to-impact (ticks) from the current position, target and speed, accounting for the climb,
     * transit, terminal descent and any remaining loiter time.
     */
    public int estimateArrivalTicks() {
        double cruiseAltitudeY = (this.cruiseMode == CruiseMode.HIGH_ALTITUDE)
                ? this.cruiseAltitude
                : this.getY() + this.terrainClearance;
        int loiterRemaining = Math.max(0, LoiterStage.loiterTicksOf(this.cruiseStageId) - this.loiterTicks);
        return ArrivalEstimator.estimateTicks(this.position(), this.getTarget(), this.cruiseSpeed,
                this.getAscentSpeed(), cruiseAltitudeY, loiterRemaining, this.getAttackApproachDir(),
                this.approachJoinCap);
    }

    public ResourceLocation getDetonationId() {
        return this.detonationId;
    }

    public int getFragmentCount() {
        return this.fragmentCount;
    }

    public int getImpactPreloadRadius() {
        return this.impactPreloadRadius;
    }

    @Override
    public Vec3 angle() {
        Vec3 v = this.getDeltaMovement();
        return v.lengthSqr() < 1.0e-8 ? new Vec3(0.0, -1.0, 0.0) : v.normalize();
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

    public boolean isCommander() {
        return this.commander;
    }

    public void setCommander(boolean commander) {
        this.commander = commander;
    }

    public UUID getControlId() {
        return this.controlId;
    }

    public void setControlId(UUID controlId) {
        this.controlId = controlId;
    }

    /** Predictive deconfliction against nearby friendly missiles (same swarm or launcher). */
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
                continue; // they already clear each other: no course change
            }
            double urgency = (AVOID_MIN_SEP - miss) / AVOID_MIN_SEP; // 0..1, larger the tighter the miss
            if (miss > 1.0e-3) {
                double inv = urgency / miss;
                ox -= sx * inv;
                oy -= sy * inv;
                oz -= sz * inv;
            } else {
                // Dead-on (no defined "away"): split sideways, deterministically by id (a shift in x/z).
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

    /**
     * Keep the turn-limited heading of {@code desired} but ramp the missile's <em>actual</em> speed toward the
     * desired speed at the {@link #acceleration} (spooling up) or {@link #deceleration} (braking) limit, so speed
     * changes are gradual instead of instantaneous.
     */
    private Vec3 applyThrust(Vec3 desired) {
        double targetSpeed = desired.length();
        Vec3 cur = this.getDeltaMovement();
        double curSpeed = cur.length();
        double rate = curSpeed <= targetSpeed ? this.effectiveAcceleration() : this.deceleration;
        double newSpeed = approach(curSpeed, targetSpeed, rate);
        Vec3 dir;
        if (targetSpeed > 1.0E-8) {
            dir = desired.scale(1.0 / targetSpeed);
        } else if (curSpeed > 1.0E-8) {
            dir = cur.scale(1.0 / curSpeed); // no commanded direction: coast on the current heading
        } else {
            dir = new Vec3(0.0, 1.0, 0.0);
        }
        return dir.scale(newSpeed);
    }

    private double effectiveAcceleration() {
        boolean fast = this.phase == Phase.ATTACK || this.boostTicks > 0;
        return fast ? Math.max(this.acceleration, DIVE_ACCELERATION) : this.acceleration;
    }

    /**
     * Formation guidance for a subordinate: steer to its wedge slot behind the commander while matching the
     * commander's velocity, so it holds station and throttles its speed to keep the slot (speeding up when it falls
     * behind, easing back once it's in place).
     */
    private Vec3 formationGuide(MissileEntity commander) {
        Vec3 slot = SwarmManager.formationSlot(commander, this);
        Vec3 cmdVel = commander.getDeltaMovement();
        Vec3 toSlot = slot.subtract(this.position());
        // Match the commander's velocity, plus a pull toward the slot (the "throttle" to open/close the gap).
        Vec3 desired = cmdVel.add(toSlot.scale(FORMATION_GAIN));
        double maxSpeed = Math.max(this.getCruiseSpeed(), cmdVel.length()) * FORMATION_MAX_OVERSPEED;
        double sp = desired.length();
        if (sp > maxSpeed && sp > 1.0E-6) {
            desired = desired.scale(maxSpeed / sp);
        }
        return desired;
    }

    /**
     * @return this commander's live formation subordinates if the whole swarm is eligible to offload as one
     *      object (every member alive, still in formation, and clear of any listener), or null if not: in which
     *      case the swarm keeps flying in-world. An empty list (commander with no surviving members) is eligible.
     */
    private List<MissileEntity> formationSubordinates(ServerLevel level) {
        List<MissileEntity> subs = new ArrayList<>();
        for (MissileEntity m : SwarmManager.members(level, this.swarmId)) {
            if (m == this) {
                continue;
            }
            if (!m.isAlive() || m.isRemoved() || m.brokeFormation || m.isCommander()) {
                return null;
            }
            if (SimMissileManager.nearAnyListener(level, m.position())) {
                return null;
            }
            subs.add(m);
        }
        return subs;
    }

    /**
     * A per-missile dispersed aim point around {@code base} (the mission target) for the saturation break: a
     * golden-angle spiral keyed on the entity id spreads the swarm evenly across the target area rather than
     * stacking every missile on one point.
     */
    private Vec3 saturationAim(Vec3 base) {
        int seed = this.getId();
        double angle = seed * 2.399963229; // golden angle (radians)
        double r = SATURATION_SPREAD * (1.0 + (seed % 3) * 0.5);
        return new Vec3(base.x + Math.cos(angle) * r, base.y, base.z + Math.sin(angle) * r);
    }

    /**
     * Unpowered flight (out of fuel), with no thrust or guidance: the missile keeps its horizontal momentum
     * (lightly dragged) while gravity pulls it down toward a terminal speed, so it arcs over and falls with its
     * inertia preserved.
     */
    private void ballisticFall(ServerLevel level, Vec3 currentPos) {
        this.coastDown(level, currentPos, FALL_HORIZONTAL_DRAG, false);
    }

    /**
     * Shared unpowered coast: gravity toward terminal speed plus horizontal {@code drag}, moving and sweeping for
     * an impact.
     */
    private void coastDown(ServerLevel level, Vec3 currentPos, double drag, boolean fizzleOnImpact) {
        // Keep the missile's own chunks loaded while it coasts down.
        this.chunkLoader.update(this, level, currentPos, this.getDeltaMovement(), true);

        Vec3 v = this.getDeltaMovement();
        double vy = Math.max(v.y - FUEL_OUT_GRAVITY, TERMINAL_FALL_SPEED);
        v = new Vec3(v.x * drag, vy, v.z * drag);
        this.setDeltaMovement(v);

        this.hasImpulse = true;
        this.move(net.minecraft.world.entity.MoverType.SELF, v);
        this.setBoundingBox(this.makeBoundingBox());

        if (this.isArmed()) {
            HitResult hitResult = this.sweepForImpact(currentPos, v);
            if (hitResult.getType() != HitResult.Type.MISS) {
                if (fizzleOnImpact) {
                    this.detonate(hitResult.getLocation(), true);
                } else {
                    this.onMissileImpact(hitResult);
                }
            }
        }
    }

    /**
     * Per-tick handling for a shot-down ({@link #downed}) missile, dispatched by its {@link DownedAction}: SPIN_OUT
     * tumbles onto a random heading and cooks off; POWER_LOSS coasts down with heavy deceleration; CRASH (the
     * default) coasts down with light drag.
     */
    private void tickDowned(ServerLevel level, Vec3 currentPos) {
        if (this.downedGrace > 0) {
            this.downedGrace--;
        }
        switch (this.downedAction) {
            case SPIN_OUT -> this.spinOut(level, currentPos);
            case POWER_LOSS -> this.coastDown(level, currentPos, POWER_LOSS_DRAG, true);
            default -> this.coastDown(level, currentPos, FALL_HORIZONTAL_DRAG, true); // CRASH
        }
    }

    /**
     * Spin-out: the missile veers onto a random heading (turn-rate limited (via {@link #constrainTurn}), so it arcs
     * out of control rather than snapping) sinking as it tumbles, and its warhead cooks off (a full detonation) on
     * impact or once a short random fuse elapses.
     */
    private void spinOut(ServerLevel level, Vec3 currentPos) {
        this.chunkLoader.update(this, level, currentPos, this.getDeltaMovement(), true);

        if (this.spinOutDir == null) {
            // Pick a random veer heading (any yaw, biased 10-40 deg below horizontal) and a short tumble fuse.
            double yaw = level.random.nextDouble() * Math.PI * 2.0;
            double pitch = Math.toRadians(-10.0 - level.random.nextDouble() * 30.0);
            double cp = Math.cos(pitch);
            this.spinOutDir = new Vec3(Math.cos(yaw) * cp, Math.sin(pitch), Math.sin(yaw) * cp).normalize();
            this.spinOutFuse = SPINOUT_MIN_FUSE + level.random.nextInt(SPINOUT_MAX_FUSE - SPINOUT_MIN_FUSE + 1);
        }

        Vec3 cur = this.getDeltaMovement();
        double speed = Math.max(cur.length(), 1.0);
        Vec3 v = constrainTurn(cur, this.spinOutDir.scale(speed), this.maxTurnRate);
        v = new Vec3(v.x, v.y - SPINOUT_GRAVITY, v.z);
        this.setDeltaMovement(v);

        this.hasImpulse = true;
        this.move(net.minecraft.world.entity.MoverType.SELF, v);
        this.setBoundingBox(this.makeBoundingBox());

        if (this.isArmed()) {
            HitResult hitResult = this.sweepForImpact(currentPos, v);
            if (hitResult.getType() != HitResult.Type.MISS) {
                this.detonate(hitResult.getLocation(), false); // full warhead cooks off on impact
                return;
            }
            if (--this.spinOutFuse <= 0) {
                this.detonate(this.position(), false); // ...or after tumbling for the fuse
            }
        }
    }

    public FuelType getFuelType() {
        return this.fuelType;
    }

    public int getFuel() {
        return this.fuel;
    }

    public int getFuelCapacity() {
        return this.fuelCapacity;
    }

    // --- Interceptor guidance & kill (server-side) ---

    public double getAcceleration() {
        return this.acceleration;
    }

    public double getDeceleration() {
        return this.deceleration;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason == RemovalReason.DISCARDED && !this.level().isClientSide
                && !this.detonated && this.isArmed() && this.isAlive()) {
            if (!this.downed) {
                this.shootDown();
            }
            return; // veto the deletion: keep the (now downed) missile alive to coast to its own crash/fizzle
        }
        if (!this.level().isClientSide && reason.shouldDestroy() && this.level() instanceof ServerLevel sl) {
            this.chunkLoader.releaseAll(this, sl);
            if (this.interceptor) {
                MissileListenerRegistry.get(sl).deregister(this.getUUID());
            }
            if (this.commander && this.swarmId != 0L) {
                SwarmManager.promoteSuccessor(sl, this.swarmId, this);
            }
        }
        super.remove(reason);
    }

    /**
     * Interceptor per-tick update: keep this interceptor registered as a listener (so nearby off-world missiles
     * rematerialize and a real target won't offload), resolve its current target, and write a lead point via {@link
     * #setTarget} so the shared flight code flies toward the predicted intercept.
     *
     * @return false if the interceptor removed itself this tick (the caller should stop ticking it).
     */
    private boolean updateInterceptTarget(ServerLevel level, Vec3 currentPos) {
        MissileListenerRegistry.get(level).register(this.getUUID(), this);

        if (this.tickCount >= MissileSimConfig.INTERCEPTOR_LIFETIME_TICKS) {
            this.detonate(currentPos, true);
            return false;
        }

        InterceptTarget target = this.resolveInterceptTarget(level, currentPos);
        if (target == null) {
            this.currentTargetId = null;
            if (++this.noTargetTicks > MissileSimConfig.INTERCEPTOR_LOST_TARGET_TICKS) {
                this.detonate(currentPos, true);
                return false;
            }
            Vec3 vel = this.getDeltaMovement();
            Vec3 dir = vel.lengthSqr() > 1.0E-6 ? vel.normalize() : new Vec3(0.0, 1.0, 0.0);
            this.setTarget(currentPos.add(dir.scale(32.0)));
            return true;
        }
        this.currentTargetId = target.interceptEntity().getUUID();
        this.noTargetTicks = 0;
        this.setTarget(this.leadPoint(currentPos, target));
        return true;
    }

    /**
     * @return the air target this interceptor should home on: in LOCK mode the exact {@link #lockTargetId}
     *      (any side: an explicit override), in NEAREST mode the closest live, non-friendly, non-interceptor
     *      target within {@link MissileSimConfig#INTERCEPTOR_ACQUIRE_RANGE}. Null when none is resolvable.
     */
    private InterceptTarget resolveInterceptTarget(ServerLevel level, Vec3 currentPos) {
        if (this.interceptMode == InterceptMode.LOCK) {
            if (this.lockTargetId != null && level.getEntity(this.lockTargetId) instanceof InterceptTarget t
                    && t.interceptEngageable()) {
                return t;
            }
            return null;
        }
        double r = MissileSimConfig.INTERCEPTOR_ACQUIRE_RANGE;
        AABB box = this.getBoundingBox().inflate(r);
        List<MissileEntity> missiles = level.getEntitiesOfClass(MissileEntity.class, box, MissileEntity::isAlive);
        List<InterceptTarget> nearby = new ArrayList<>(missiles);
        nearby.addAll(level.getEntitiesOfClass(DroneEntity.class, box, DroneEntity::isAlive));
        Set<UUID> claimed = claimedTargets(missiles, this.getId());
        InterceptTarget best = null;
        InterceptTarget fallback = null;
        double bestSq = r * r;
        double fallbackSq = r * r;
        for (InterceptTarget t : nearby) {
            if (t == this || !t.interceptEngageable() || this.isFriendly(t)) {
                continue;
            }
            Entity entity = t.interceptEntity();
            double dsq = entity.position().distanceToSqr(currentPos);
            if (t instanceof MissileEntity m && !m.detectableAt(dsq, r)) {
                continue;
            }
            if (dsq <= fallbackSq) {
                fallbackSq = dsq;
                fallback = t;
            }
            if (!claimed.contains(entity.getUUID()) && dsq <= bestSq) {
                bestSq = dsq;
                best = t;
            }
        }
        return best != null ? best : fallback;
    }

    /** Predicted intercept point: where to aim so a run at cruise speed meets the target's straight-line motion. */
    private Vec3 leadPoint(Vec3 currentPos, InterceptTarget target) {
        Vec3 tPos = target.interceptEntity().getBoundingBox().getCenter();
        Vec3 vt = target.interceptEntity().getDeltaMovement();
        double s = this.getCruiseSpeed();
        Vec3 d = tPos.subtract(currentPos);
        double a = vt.lengthSqr() - s * s;
        double b = 2.0 * d.dot(vt);
        double c = d.lengthSqr();
        double t;
        if (Math.abs(a) < 1.0E-6) {
            t = Math.abs(b) < 1.0E-6 ? -1.0 : -c / b; // linear b·t + c = 0
        } else {
            double disc = b * b - 4.0 * a * c;
            if (disc < 0.0) {
                t = -1.0;
            } else {
                double sq = Math.sqrt(disc);
                t = smallestPositive((-b - sq) / (2.0 * a), (-b + sq) / (2.0 * a));
            }
        }
        if (t > 0.0 && !Double.isNaN(t) && !Double.isInfinite(t)) {
            // A timed intercept exists: we can reach this lead point just as the target arrives.
            this.crossingShot = false;
            return tPos.add(vt.scale(t));
        }
        this.crossingShot = true;
        double vlen2 = vt.lengthSqr();
        if (vlen2 < 1.0E-8) {
            return tPos; // (near-)stationary target: go straight for it
        }
        double u = Math.max(0.0, -d.dot(vt) / vlen2); // foot of the perpendicular onto the forward path
        return tPos.add(vt.scale(u));
    }

    /**
     * Closest-approach kill test against the current target over this tick's motion.
     *
     * @return true if the interceptor resolved (killed a target or was spent) and should stop ticking.
     */
    private boolean tryIntercept(Vec3 currentPos) {
        if (this.currentTargetId == null || !(this.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!(level.getEntity(this.currentTargetId) instanceof InterceptTarget tgt)
                || !tgt.interceptEngageable()) {
            return false;
        }
        Entity tgtEntity = tgt.interceptEntity();
        Vec3 iV = this.getDeltaMovement();
        Vec3 tEnd = tgtEntity.getBoundingBox().getCenter();
        Vec3 tV = tgtEntity.getDeltaMovement();
        Vec3 tStart = tEnd.subtract(tV);
        // Relative motion of the target vs. the interceptor over this tick's two segments.
        Vec3 rp = tStart.subtract(currentPos);
        Vec3 rv = tV.subtract(iV);
        double rv2 = rv.lengthSqr();
        double tStar = rv2 < 1.0E-9 ? 0.0 : Mth.clamp(-rp.dot(rv) / rv2, 0.0, 1.0);
        double minSep = rp.add(rv.scale(tStar)).length();
        if (minSep > MissileSimConfig.INTERCEPTOR_KILL_RADIUS) {
            return false;
        }
        Vec3 point = currentPos.add(iV.scale(tStar));
        // A crossing shot (we couldn't run the target down, only cut across its path) is much less reliable.
        float chance = this.crossingShot
                ? this.interceptChance * MissileSimConfig.INTERCEPTOR_CROSSING_HIT_FACTOR
                : this.interceptChance;
        boolean kill = level.random.nextFloat() < chance;
        if (kill && tgt.interceptEvade(this.getCruiseSpeed(), this.position())) {
            kill = false;
        }
        spawnInterceptBurst(level, point, kill); // readable flak burst: big + boom on a kill, small on a miss
        if (MissileSimConfig.INTERCEPTOR_CHIP_MODE) {
            tgt.interceptDamage(kill ? MissileSimConfig.INTERCEPTOR_HIT_DAMAGE : MissileSimConfig.INTERCEPTOR_GRAZE_DAMAGE);
        } else if (kill) {
            tgt.interceptKill();
        }
        this.detonate(point, true); // spent whether it hits or misses (one-shot)
        return true;
    }

    // --- IMissileListener (interceptors only; a normal missile is never registered) ---

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
        return this.interceptor && !this.isRemoved() && !this.detonated
                && this.level() instanceof ServerLevel sl && sl.isLoaded(this.blockPosition());
    }

    public boolean isInterceptor() {
        return this.interceptor;
    }

    /**
     * @return this missile's radar cross-section, against a reference of 1.0.
     */
    public float getRcs() {
        return this.rcs;
    }

    /**
     * @return true if this missile is low-observable. Derived from {@link #getRcs}, for display and commands
     *      only: nothing about detection reads this, because detection is a curve and not a flag.
     */
    public boolean isStealth() {
        return this.rcs < MissileSimConfig.STEALTH_RCS_THRESHOLD;
    }

    public float getEvasion() {
        return this.evasion;
    }

    /**
     * @return true if evasion boosts also jink the missile off its course (see {@link #evasiveManeuver}).
     */
    public boolean isEvasiveManeuver() {
        return this.evasiveManeuver;
    }

    /**
     * @return this missile's evasion right now (0..1): its base {@link #evasion}, amplified by
     *      {@link MissileSimConfig#DIVE_EVASION_MULTIPLIER} while in the terminal ATTACK dive (a maneuvering
     *      warhead is hardest to intercept on its way down). The interceptor's kill chance is scaled by
     *      {@code (1 - this)}.
     */
    public float effectiveEvasion() {
        if (this.evasion <= 0.0f) {
            return 0.0f;
        }
        double e = this.phase == Phase.ATTACK ? this.evasion * MissileSimConfig.DIVE_EVASION_MULTIPLIER : this.evasion;
        return (float) Math.min(1.0, e);
    }

    /**
     * @return true if the missile boosted clear (the incoming hit becomes a miss).
     */
    private boolean evadeBoost(double interceptorSpeed, Vec3 interceptorPos) {
        if (this.evasion <= 0.0f || this.fuel < BOOST_FUEL_COST || !(this.level() instanceof ServerLevel sl)) {
            return false; // no evasion capability
        }
        this.fuel -= BOOST_FUEL_COST;
        this.boostTicks = BOOST_DURATION;
        this.boostManeuver = this.evasiveManeuver ? this.computeJink(sl.random, interceptorPos) : null;
        double boostedSpeed = Math.max(this.getDeltaMovement().length(), this.getCruiseSpeed() * BOOST_SPEED_MULT);
        double speedFactor = Math.min(1.0, boostedSpeed / Math.max(1.0E-3, interceptorSpeed));
        float escapeChance = (float) (this.effectiveEvasion() * speedFactor);
        return sl.random.nextFloat() < escapeChance;
    }

    /**
     * A break direction for a maneuvering dodge
     */
    private Vec3 computeJink(net.minecraft.util.RandomSource rand, Vec3 interceptorPos) {
        Vec3 v = this.getDeltaMovement();
        double vlen = v.length();
        Vec3 vhat = vlen > 1.0E-6 ? v.scale(1.0 / vlen) : new Vec3(0.0, 1.0, 0.0);
        // Orthonormal basis spanning the plane perpendicular to the heading.
        Vec3 ref = Math.abs(vhat.y) < 0.95 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        Vec3 right = vhat.cross(ref).normalize();
        Vec3 up = right.cross(vhat).normalize();
        // Away-from-interceptor direction, projected into that plane, gives the base break angle.
        Vec3 away = this.position().subtract(interceptorPos);
        double ar = away.dot(right);
        double au = away.dot(up);
        double angle = (ar * ar + au * au) > 1.0E-8 ? Math.atan2(au, ar) : rand.nextDouble() * Math.PI * 2.0;
        angle += rand.nextGaussian() * 0.6; // ~+/-35 degrees of jitter so the jink isn't a fixed plane
        return right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle))).normalize();
    }

    /**
     * @return whether a detector of this range may see this missile, at squared distance {@code distSq}.
     */
    public boolean detectableAt(double distSq, double baseRange) {
        if (this.rcs <= 0.0f) {
            return false;
        }
        double detection = baseRange * Math.sqrt(Math.sqrt(this.rcs));
        return distSq <= detection * detection;
    }

    public float getInterceptChance() {
        return this.interceptChance;
    }

    /** Switch this (already-built) interceptor to LOCK mode on a specific target missile's UUID. */
    public void setInterceptLock(UUID targetId) {
        this.interceptMode = InterceptMode.LOCK;
        this.lockTargetId = targetId;
    }

    /** Assign a specific entity for this (non-interceptor) missile/drone to strike. */
    public void setDesignatedTarget(UUID entityId) {
        this.designatedTargetId = entityId;
    }

    public UUID getDesignatedTargetId() {
        return this.designatedTargetId;
    }

    /**
     * @return true if a designated strike target has been assigned (whether or not it is currently present).
     */
    public boolean hasDesignatedTarget() {
        return this.designatedTargetId != null;
    }

    /**
     * @return true if the designated target is currently resolvable and alive (server-side).
     */
    public boolean hasLiveDesignatedTarget() {
        if (this.designatedTargetId == null || !(this.level() instanceof ServerLevel sl)) {
            return false;
        }
        Entity e = sl.getEntity(this.designatedTargetId);
        return e != null && e.isAlive();
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
        tag.putString("Medium", this.medium.name());
        tag.putDouble("CruiseAltitude", this.cruiseAltitude);
        tag.putDouble("TerrainClearance", this.terrainClearance);
        tag.putFloat("ExplosionOffset", this.explosionOffset);
        tag.putDouble("MaxTurnRate", this.maxTurnRate);
        tag.putDouble("CruiseSpeed", this.cruiseSpeed);
        if (!Double.isNaN(this.ascentSpeed)) {
            tag.putDouble("AscentSpeed", this.ascentSpeed);
        }
        if (!Double.isNaN(this.attackAngle)) {
            tag.putDouble("AttackAngle", this.attackAngle);
        }
        tag.putDouble("MinDiveAngle", this.minDiveAngle);
        tag.putDouble("MaxDiveAngle", this.maxDiveAngle);
        if (this.attackApproachDir != null) {
            tag.putDouble("AttackApproachX", this.attackApproachDir.x);
            tag.putDouble("AttackApproachZ", this.attackApproachDir.z);
        }
        tag.putString("AttackProfile", this.attackProfile.name());
        tag.putDouble("ApproachJoinCap", this.approachJoinCap);
        tag.putString("ModelId", this.getModelId().toString());
        tag.putInt("ExhaustColor", this.getExhaustColor());
        tag.putString("FlightSound", this.entityData.get(FLIGHT_SOUND));
        tag.putDouble("FlightSoundRange", this.flightSoundRange);
        tag.putFloat("FlightSoundBasePitch", this.flightSoundBasePitch);
        tag.putDouble("FlightSoundSpeedPitch", this.flightSoundSpeedPitch);
        tag.putInt("CruiseTicks", this.cruiseTicks);
        tag.putString("DetonationId", this.detonationId.toString());
        tag.putString("DamageResponse", this.damageResponseId.toString());
        tag.putString("AscentStage", this.ascentStageId.toString());
        tag.putString("CruiseStage", this.cruiseStageId.toString());
        tag.putString("AttackStage", this.attackStageId.toString());
        tag.putInt("LoiterTicks", this.loiterTicks);
        tag.putInt("FragmentCount", this.fragmentCount);
        tag.putInt("ImpactPreloadRadius", this.impactPreloadRadius);
        tag.putInt("SplitDepth", this.splitDepth);
        tag.putLong("SwarmId", this.swarmId);
        tag.putBoolean("Commander", this.commander);
        tag.putBoolean("BrokeFormation", this.brokeFormation);
        if (this.controlId != null) {
            tag.putUUID("ControlId", this.controlId);
        }
        if (this.teamId != null) {
            tag.putUUID("TeamId", this.teamId);
        }
        tag.putBoolean("Interceptor", this.interceptor);
        tag.putString("InterceptMode", this.interceptMode.name());
        if (this.lockTargetId != null) {
            tag.putUUID("LockTargetId", this.lockTargetId);
        }
        tag.putFloat("InterceptChance", this.interceptChance);
        tag.putInt("NoTargetTicks", this.noTargetTicks);
        if (this.designatedTargetId != null) {
            tag.putUUID("DesignatedTarget", this.designatedTargetId);
        }
        tag.putFloat("Rcs", this.rcs);
        tag.putFloat("Evasion", this.evasion);
        tag.putBoolean("EvasiveManeuver", this.evasiveManeuver);
        tag.putString("FuelType", this.fuelType.name());
        tag.putInt("Fuel", this.fuel);
        tag.putInt("FuelCapacity", this.fuelCapacity);
        tag.putDouble("Acceleration", this.acceleration);
        tag.putDouble("Deceleration", this.deceleration);
        tag.putFloat("Health", this.health);
        tag.putBoolean("Armed", this.armed);
        tag.putBoolean("Downed", this.downed);
        tag.putInt("DownedGrace", this.downedGrace);
        tag.putString("DownedAction", this.downedAction.name());
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

        if (tag.contains("Medium")) {
            try {
                this.setMedium(Medium.valueOf(tag.getString("Medium")));
            } catch (IllegalArgumentException ignored) {
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

        if (tag.contains("AscentSpeed")) {
            this.ascentSpeed = tag.getDouble("AscentSpeed");
        }

        if (tag.contains("AttackAngle")) {
            this.attackAngle = tag.getDouble("AttackAngle");
        }

        if (tag.contains("MinDiveAngle")) {
            this.minDiveAngle = tag.getDouble("MinDiveAngle");
        }

        if (tag.contains("MaxDiveAngle")) {
            this.maxDiveAngle = tag.getDouble("MaxDiveAngle");
        }

        if (tag.contains("AttackApproachX")) {
            this.setAttackApproachDir(new Vec3(tag.getDouble("AttackApproachX"), 0.0,
                    tag.getDouble("AttackApproachZ")));
        }

        if (tag.contains("AttackProfile")) {
            this.attackProfile = AttackProfile.byName(tag.getString("AttackProfile"));
        }

        if (tag.contains("ApproachJoinCap")) {
            this.approachJoinCap = tag.getDouble("ApproachJoinCap");
        }

        if (tag.contains("ModelId")) {
            this.setModelId(MissileModels.parse(tag.getString("ModelId")));
        }

        if (tag.contains("ExhaustColor")) {
            this.setExhaustColor(tag.getInt("ExhaustColor"));
        }

        if (tag.contains("FlightSound")) {
            this.entityData.set(FLIGHT_SOUND, tag.getString("FlightSound"));
        }
        if (tag.contains("FlightSoundRange")) {
            this.flightSoundRange = tag.getDouble("FlightSoundRange");
        }
        if (tag.contains("FlightSoundBasePitch")) {
            this.flightSoundBasePitch = tag.getFloat("FlightSoundBasePitch");
        }
        if (tag.contains("FlightSoundSpeedPitch")) {
            this.flightSoundSpeedPitch = tag.getDouble("FlightSoundSpeedPitch");
        }

        if (tag.contains("CruiseTicks")) {
            this.cruiseTicks = tag.getInt("CruiseTicks");
        }

        if (tag.contains("DetonationId")) {
            this.detonationId = WarheadRegistry.parse(tag.getString("DetonationId"));
            this.detonation = WarheadRegistry.get(this.detonationId);
        }

        if (tag.contains("DamageResponse")) {
            this.damageResponseId = MissileDamageRegistry.parse(tag.getString("DamageResponse"));
            this.damageResponse = MissileDamageRegistry.get(this.damageResponseId);
        }

        if (tag.contains("AscentStage")) {
            this.ascentStageId = FlightStageRegistry.parse(Phase.ASCEND, tag.getString("AscentStage"));
        }
        if (tag.contains("CruiseStage")) {
            this.cruiseStageId = FlightStageRegistry.parse(Phase.CRUISE, tag.getString("CruiseStage"));
        }
        if (tag.contains("AttackStage")) {
            this.attackStageId = FlightStageRegistry.parse(Phase.ATTACK, tag.getString("AttackStage"));
        }
        this.rebuildFlightProfile();
        this.loiterTicks = tag.getInt("LoiterTicks");

        if (tag.contains("FragmentCount")) {
            this.fragmentCount = tag.getInt("FragmentCount");
        }

        if (tag.contains("ImpactPreloadRadius")) {
            this.impactPreloadRadius = tag.getInt("ImpactPreloadRadius");
        }

        if (tag.contains("SplitDepth")) {
            this.splitDepth = tag.getInt("SplitDepth");
        }
        this.swarmId = tag.getLong("SwarmId");
        this.commander = tag.getBoolean("Commander");
        this.brokeFormation = tag.getBoolean("BrokeFormation");
        if (tag.hasUUID("ControlId")) {
            this.controlId = tag.getUUID("ControlId");
        }
        if (tag.hasUUID("TeamId")) {
            this.teamId = tag.getUUID("TeamId");
        }

        this.interceptor = tag.getBoolean("Interceptor");
        if (tag.contains("InterceptMode")) {
            try {
                this.interceptMode = InterceptMode.valueOf(tag.getString("InterceptMode"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (tag.hasUUID("LockTargetId")) {
            this.lockTargetId = tag.getUUID("LockTargetId");
        }
        if (tag.contains("InterceptChance")) {
            this.interceptChance = tag.getFloat("InterceptChance");
        }
        this.noTargetTicks = tag.getInt("NoTargetTicks");
        if (tag.hasUUID("DesignatedTarget")) {
            this.designatedTargetId = tag.getUUID("DesignatedTarget");
        }
        this.rcs = tag.contains("Rcs") ? tag.getFloat("Rcs")
                : (tag.getBoolean("Stealth") ? MissileSimConfig.STEALTH_RCS : 1.0f);
        this.evasiveManeuver = tag.getBoolean("EvasiveManeuver");
        if (tag.contains("Evasion")) {
            this.evasion = tag.getFloat("Evasion");
        }

        if (tag.contains("FuelType")) {
            try {
                this.fuelType = FuelType.valueOf(tag.getString("FuelType"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (tag.contains("Fuel")) {
            this.fuel = tag.getInt("Fuel");
        }
        if (tag.contains("FuelCapacity")) {
            this.fuelCapacity = tag.getInt("FuelCapacity");
        }
        if (tag.contains("Acceleration")) {
            this.acceleration = tag.getDouble("Acceleration");
        }
        if (tag.contains("Deceleration")) {
            this.deceleration = tag.getDouble("Deceleration");
        }

        this.armed = tag.getBoolean("Armed");
        this.downed = tag.getBoolean("Downed");
        this.downedGrace = tag.getInt("DownedGrace");
        if (tag.contains("DownedAction")) {
            try {
                this.downedAction = DownedAction.valueOf(tag.getString("DownedAction"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (tag.contains("LaunchX")) {
            this.launchPos = new Vec3(tag.getDouble("LaunchX"), tag.getDouble("LaunchY"), tag.getDouble("LaunchZ"));
        }

        if (tag.contains("Health")) {
            this.health = tag.getFloat("Health");
        }
    }

    private void onMissileImpact(HitResult hitResult) {
        this.recordEvent(WFEventType.IMPACTED, hitResult.getType().toString());
        if (hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof MissileEntity other) {
            Vec3 pos = hitResult.getLocation();
            this.detonate(pos, true);
            other.detonate(pos, true); // guarded by other.detonated, so a mutual hit only fires each once
            return;
        }
        this.detonate(hitResult.getLocation(), false);
    }

    /**
     * Fires the configured warhead at the given position and removes the missile (a full target detonation).
     */
    private void detonate(Vec3 pos) {
        this.detonate(pos, false);
    }

    /**
     * Removes the missile, running either the full warhead {@link WarheadRegistry.Detonation} or, when {@code
     * intercepted}, the (typically neutralised) {@link WarheadRegistry#getIntercept intercept effect} used when the
     * missile is shot down / rammed mid-air instead of reaching its target.
     */
    private void detonate(Vec3 pos, boolean intercepted) {
        if (this.detonated) {
            return;
        }
        this.detonated = true; // set before the blast: it can hurt() this missile before discard() runs
        this.recordEvent(intercepted ? WFEventType.INTERCEPTED : WFEventType.DETONATED, "");
        if (intercepted) {
            WarheadRegistry.getIntercept(this.detonationId).detonate(this, pos);
        } else {
            this.detonation.detonate(this, pos);
        }
        if (this.level() instanceof ServerLevel sl) {
            if (!intercepted) {
                DetonationChunkGuard.hold(sl, pos, Math.max(1, this.impactPreloadRadius));
            }
            this.chunkLoader.releaseAll(this, sl);
        }
        this.discard();
    }

    public float getHealth() {
        return this.health;
    }

    public void damageMissile(float amount) {
        if (this.level().isClientSide || this.isRemoved() || this.detonated || amount <= 0.0f) {
            return;
        }
        // While unarmed, absorb damage without cooking off: a stray hit shouldn't blow it up on the launcher.
        if (!this.isArmed()) {
            return;
        }
        this.health -= amount;
        this.recordEvent(WFEventType.DAMAGED, "dmg=" + f1(amount) + " hp=" + f1(this.health));
        if (this.health <= 0.0f) {
            this.shootDown();
        }
    }

    /**
     * Neutralise this missile by shooting it down: it cuts thrust and guidance and falls ballistically (see {@link
     * #ballisticFall}), then runs the neutralised intercept effect where it hits the ground instead of a full
     * warhead blast.
     */
    public void shootDown() {
        this.shootDown(this.downedAction);
    }

    /** Shoot this missile down with an explicit {@link DownedAction}, overriding its per-launch rolled action. */
    public void shootDown(DownedAction action) {
        if (this.detonated) {
            return;
        }
        if (this.downed) {
            if (this.downedGrace <= 0) {
                this.detonate(this.position(), false);
            }
            return;
        }
        switch (action) {
            case FIZZLE -> this.detonate(this.position(), true);   // neutralised fizzle on the spot, mid-air
            case DETONATE -> this.detonate(this.position(), false); // full warhead blast on the spot, mid-air
            default -> {
                this.downedAction = action;
                this.downed = true;
                this.fuel = 0;
                this.downedGrace = DOWNED_REHIT_GRACE;
                this.recordEvent(WFEventType.DESTROYED, "shot down: " + action);
            }
        }
    }

    /**
     * @return true once this missile has been shot down and is falling ballistically (see {@link #shootDown}).
     */
    public boolean isDowned() {
        return this.downed;
    }

    // --- InterceptTarget: what air defence is allowed to know about this missile ---

    /**
     * An interceptor is not a target: shooting one only spends a second interceptor on a shot that was already
     * taken.
     */
    @Override
    public boolean interceptEngageable() {
        return this.isAlive() && !this.detonated && !this.downed && !this.interceptor;
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
        this.damageMissile(amount);
    }

    @Override
    public void interceptKill() {
        this.shootDown(DownedAction.DETONATE);
    }

    @Override
    public boolean interceptEvade(double interceptorSpeed, Vec3 interceptorPos) {
        return this.evadeBoost(interceptorSpeed, interceptorPos);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        if (source.is(DamageTypeTags.IS_PROJECTILE) && amount < MissileSimConfig.MIN_PROJECTILE_DAMAGE) {
            return false;
        }
        float effective = this.damageResponse.apply(this, source, amount);
        if (effective <= 0.0f) {
            return false;
        }
        this.damageMissile(effective);
        return true;
    }

    /**
     * @return this missile's damage-response id (see {@link MissileDamageRegistry}).
     */
    public ResourceLocation getDamageResponseId() {
        return this.damageResponseId;
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

    /** The fluid the missile travels in, which is what decides what "terrain" means to it. */
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

        /** The heightmap whose top is the surface this medium must not run into. */
        public Heightmap.Types heightmap() {
            return this.heightmap;
        }

        /** How far ahead along the track the terrain fan is centred, in blocks. */
        public double lookAhead() {
            return this.lookAhead;
        }

        /** Half-width of the terrain fan, in blocks. */
        public double scanRadius() {
            return this.scanRadius;
        }

        /** @return true if a vehicle in this medium is bounded above as well as below. */
        public boolean hasCeiling() {
            return this == WATER;
        }
    }

    /**
     * What a missile does when it is shot out of the sky (see {@link #shootDown}). Picked per-preset.
     */
    public enum DownedAction {
        /** Neutralised fizzle on the spot, in mid-air where it was hit (the original shot-down behaviour). */
        FIZZLE,
        /** Instant FULL warhead blast in mid-air where it was hit: a real "detonated on the spot". */
        DETONATE,
        /** Cut power and fall ballistically (light drag, momentum preserved); fizzles at the crash site. */
        CRASH,
        /** Veer onto a random, turn-rate-limited heading (spins out), then the warhead cooks off (full blast). */
        SPIN_OUT,
        /** Engines cut: falls preserving momentum but decelerating hard; fizzles at the crash site. */
        POWER_LOSS
    }

    /**
     * How an interceptor picks its target: re-acquire the closest hostile each tick, or home on one UUID.
     */
    public enum InterceptMode {
        NEAREST,
        LOCK
    }

    /** Propellant kind: SOLID is a preloaded charge; LIQUID is kerosene (see {@code WFFluids}). */
    public enum FuelType {
        SOLID,
        LIQUID
    }

    /**
     * Example use: <pre>{@code MissileEntity m = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
     * .target(pos) .highAltitude(250.0) // or .terrainFollow(24.0) .explosionOffset(30f) // airburst 30 blocks
     * above the target .build(); level.addFreshEntity(m); }</pre>
     */
    public static final class Builder {
        private final EntityType<? extends Projectile> type;
        private final Level level;

        private Vec3 target;
        private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
        private Medium medium = Medium.AIR;
        private double cruiseAltitude = 200.0;
        private double terrainClearance = 24.0;
        private float explosionOffset = 0.0f;
        private ResourceLocation detonationId = WarheadRegistry.defaultId();
        private ResourceLocation ascentStageId = null; // null = keep the phase's default stage
        private ResourceLocation cruiseStageId = null;
        private ResourceLocation attackStageId = null;
        private int fragmentCount = DEFAULT_FRAGMENT_COUNT;
        private int impactPreloadRadius = DEFAULT_IMPACT_PRELOAD_RADIUS;
        private double cruiseSpeed = CRUISE_SPEED;
        private Double ascentSpeed = null;
        private Double attackAngle = null;
        private Double minDiveAngle = null;
        private Double maxDiveAngle = null;
        private Vec3 attackApproachDir = null; // null = no directional-strike constraint
        private AttackProfile attackProfile = AttackProfile.SPEED;
        private double approachJoinCap = DEFAULT_APPROACH_JOIN_CAP;
        private Double maxTurnRate = null; // null = keep the model-size default
        private ResourceLocation modelId = MissileModels.DEFAULT;
        private int exhaustColor = DEFAULT_EXHAUST_COLOR;
        private ResourceLocation flightSoundId = null; // null = keep WF-B's default missile_flight loop
        private double flightSoundRange = DEFAULT_FLIGHT_SOUND_RANGE;
        private float flightSoundBasePitch = 1.0f;
        private double flightSoundSpeedPitch = 0.0;
        private ResourceLocation damageResponseId = MissileDamageRegistry.defaultId();
        private DownedAction downedAction = DownedAction.CRASH;
        private boolean startInCruise = false;
        private boolean startInAttack = false;
        private boolean startArmed = false;
        private float health = DEFAULT_HEALTH;
        private Integer splitDepth = null; // null = default by warhead (recursive_frag gets a depth, others 0)
        private long swarmId = 0L;
        private boolean commander = false;
        private UUID controlId = null;
        private UUID teamId = null;
        private boolean interceptor = false;
        private InterceptMode interceptMode = InterceptMode.NEAREST;
        private UUID lockTargetId = null;
        private Float interceptChance = null; // null = MissileSimConfig.DEFAULT_INTERCEPT_CHANCE
        private FuelType fuelType = FuelType.SOLID;
        private int fuelTicks = DEFAULT_FUEL_TICKS;
        private double acceleration = DEFAULT_ACCELERATION;
        private double deceleration = DEFAULT_DECELERATION;
        private UUID designatedTargetId = null;
        private float rcs = 1.0f;
        private float evasion = 0.0f;
        private boolean evasiveManeuver = false;

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
         * Set the terrain clearance without touching the cruise mode, so a fixed-altitude missile still has a floor
         * it holds above the ground, or, for a torpedo running at a set depth, above the seabed.
         */
        public Builder clearance(double terrainClearance) {
            this.terrainClearance = terrainClearance;
            return this;
        }

        /** Travel through the given medium. */
        public Builder medium(Medium medium) {
            this.medium = medium;
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
         * Pick the warhead by its registered id (see {@link WarheadRegistry#register}); defaults to {@code
         * "standard"}.
         */
        public Builder detonation(ResourceLocation detonationId) {
            this.detonationId = detonationId;
            return this;
        }

        /**
         * Pick the ascent-phase stage by its registered id (see {@link FlightStageRegistry}).
         */
        public Builder ascentStage(ResourceLocation id) {
            this.ascentStageId = id;
            return this;
        }

        /**
         * Pick the cruise-phase stage (e.g. {@code "cruise"} or {@code "loiter"}) by its registered id.
         */
        public Builder cruiseStage(ResourceLocation id) {
            this.cruiseStageId = id;
            return this;
        }

        /**
         * Pick the attack-phase stage (e.g. {@code "attack"} or {@code "dive"}) by its registered id.
         */
        public Builder attackStage(ResourceLocation id) {
            this.attackStageId = id;
            return this;
        }

        /**
         * Number of bomblets the {@code "fragmentation"} warhead scatters; defaults to {@link
         * #DEFAULT_FRAGMENT_COUNT}.
         */
        public Builder fragmentCount(int fragmentCount) {
            this.fragmentCount = fragmentCount;
            return this;
        }

        /**
         * Chunk radius force-loaded around the aim point during the terminal run so the warhead detonates into
         * loaded terrain (see {@link #DEFAULT_IMPACT_PRELOAD_RADIUS}).
         */
        public Builder impactPreloadRadius(int chunkRadius) {
            this.impactPreloadRadius = Math.max(0, chunkRadius);
            return this;
        }

        /**
         * Override the max heading change per tick (radians); default scales from the model's length.
         */
        public Builder turnRate(double radiansPerTick) {
            this.maxTurnRate = radiansPerTick;
            return this;
        }

        /** Set the horizontal cruise speed (blocks/tick); default {@link #CRUISE_SPEED}. */
        public Builder cruiseSpeed(double blocksPerTick) {
            this.cruiseSpeed = blocksPerTick;
            return this;
        }

        public Builder ascentSpeed(double blocksPerTick) {
            this.ascentSpeed = blocksPerTick;
            return this;
        }

        /**
         * Explicit preferred dive angle in degrees below horizontal (90 = straight down / top-attack), uncapped.
         */
        public Builder attackAngle(double degrees) {
            this.attackAngle = degrees;
            return this;
        }

        /**
         * When no explicit {@link #attackAngle} is set, the terminal dive auto-picks an angle in this range
         * (degrees below horizontal) by raycasting for the fastest unobstructed approach.
         */
        public Builder diveAngleRange(double minDegrees, double maxDegrees) {
            this.minDiveAngle = minDegrees;
            this.maxDiveAngle = maxDegrees;
            return this;
        }

        /**
         * Strike the target from a commanded side: {@code (dirX, dirZ)} is the horizontal direction from the target
         * toward where the missile should come in from ("attack from the west" = a westward vector).
         */
        public Builder attackFrom(double dirX, double dirZ) {
            double h = Math.sqrt(dirX * dirX + dirZ * dirZ);
            this.attackApproachDir = h < 1.0E-4 ? null : new Vec3(dirX / h, 0.0, dirZ / h);
            return this;
        }

        /**
         * Pick how the terminal attack trades speed for a steep dive when the preferred angle won't fit the turn
         * radius (see {@link AttackProfile}).
         */
        public Builder attackProfile(AttackProfile profile) {
            this.attackProfile = profile == null ? AttackProfile.SPEED : profile;
            return this;
        }

        /**
         * Ceiling (blocks) on how far out a directional strike joins its attack line (see {@link ApproachStage}).
         */
        public Builder approachJoinCap(double cap) {
            this.approachJoinCap = cap;
            return this;
        }

        /**
         * Pick which missile model/skin to render and fly as (see {@link MissileModels}).
         */
        public Builder model(ResourceLocation modelId) {
            this.modelId = modelId;
            return this;
        }

        /**
         * Tint of the exhaust trail: the hot RGB (0xRRGGBB) the client-side plume fades from as it cools and
         * dissipates.
         */
        public Builder exhaustColor(int rgb) {
            this.exhaustColor = rgb;
            return this;
        }

        /** The looping flight sound this missile plays client-side. */
        public Builder flightSound(ResourceLocation id) {
            this.flightSoundId = id;
            return this;
        }

        /**
         * Distance in blocks at which this missile's flight loop fades to silence, and, since the audio is
         * server-pushed independently of entity tracking, the radius the server broadcasts it to.
         */
        public Builder flightSoundRange(double blocks) {
            this.flightSoundRange = blocks;
            return this;
        }

        /** Idle engine pitch of the flight loop (default 1.0); e.g. a heavy missile can idle lower. */
        public Builder flightSoundBasePitch(float pitch) {
            this.flightSoundBasePitch = pitch;
            return this;
        }

        /**
         * Added flight-loop pitch per block/tick of the missile's OWN speed: the engine "revs" as it flies faster.
         */
        public Builder flightSoundSpeedPitch(double perBlockPerTick) {
            this.flightSoundSpeedPitch = perBlockPerTick;
            return this;
        }

        /** How this missile responds to incoming damage, by {@link MissileDamageRegistry} id (e.g. */
        public Builder damageResponse(ResourceLocation id) {
            this.damageResponseId = (id != null) ? id : MissileDamageRegistry.defaultId();
            return this;
        }

        /** What this missile does when shot out of the sky (see {@link DownedAction}). */
        public Builder downedAction(DownedAction action) {
            this.downedAction = (action != null) ? action : DownedAction.CRASH;
            return this;
        }

        /**
         * Start the missile already in the CRUISE phase (used when respawning a simulated missile that had long
         * since finished its ascent).
         */
        public Builder startInCruise() {
            this.startInCruise = true;
            return this;
        }

        /** Spawn already in the terminal ATTACK dive. */
        public Builder startInAttack() {
            this.startInAttack = true;
            return this;
        }

        /**
         * Number of recursive split generations (see {@link RecursiveFrag}); leave unset to take the warhead
         * default ({@link RecursiveFrag#DEFAULT_DEPTH} for {@code recursive_frag}, else none).
         */
        public Builder splitDepth(int splitDepth) {
            this.splitDepth = splitDepth;
            return this;
        }

        /**
         * Group this missile into a fragmentation family (a non-zero id shared by all its descendants) so the
         * family's members won't collide with each other (see {@link #canHitEntity}).
         */
        public Builder swarmId(long swarmId) {
            this.swarmId = swarmId;
            return this;
        }

        /**
         * Mark this missile as the swarm commander: it flies the mission and the rest of its {@link #swarmId} hold
         * formation on it (see {@link SwarmManager}).
         */
        public Builder commander(boolean commander) {
            this.commander = commander;
            return this;
        }

        /**
         * Stamp this missile with a launcher/control id (see {@link MissileDispenserBlockEntity}) so missiles fired
         * from the same launcher treat each other as friendly and never collide.
         */
        public Builder controlId(UUID controlId) {
            this.controlId = controlId;
            return this;
        }

        /**
         * Stamp this missile with a WarForge faction/team id (see {@link WarforgeCompat}); missiles of the same /
         * allied / truced faction are friendly.
         */
        public Builder teamId(UUID teamId) {
            this.teamId = teamId;
            return this;
        }

        /**
         * Spawn with the warhead already armed (used when respawning a simulated missile that has long since flown
         * clear of its launcher).
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

        /**
         * Make this an interceptor: it homes on another missile and resolves a kill by a random roll (see {@link
         * #tryIntercept}) instead of a warhead.
         */
        public Builder interceptor(boolean interceptor) {
            this.interceptor = interceptor;
            return this;
        }

        /**
         * Pick the interceptor targeting mode: {@code NEAREST} (closest hostile, re-acquired each tick) or {@code
         * LOCK} (a specific UUID, set via {@link #lockTarget}).
         */
        public Builder interceptMode(InterceptMode mode) {
            this.interceptMode = mode;
            return this;
        }

        /**
         * Home on one specific target missile's UUID (also selects {@code LOCK} mode).
         */
        public Builder lockTarget(UUID targetId) {
            this.interceptMode = InterceptMode.LOCK;
            this.lockTargetId = targetId;
            return this;
        }

        /**
         * Per-interceptor kill probability rolled on closest approach; default {@link
         * MissileSimConfig#DEFAULT_INTERCEPT_CHANCE}.
         */
        public Builder interceptChance(float chance) {
            this.interceptChance = chance;
            return this;
        }

        /**
         * Load the tank: {@code type} of propellant and {@code ticks} of powered flight before it runs dry (after
         * which the missile falls ballistically).
         */
        public Builder fuel(FuelType type, int ticks) {
            this.fuelType = type;
            this.fuelTicks = ticks;
            return this;
        }

        /**
         * Max change in actual speed per tick while spooling up toward the guidance speed (blocks/tick^2).
         */
        public Builder acceleration(double acceleration) {
            this.acceleration = acceleration;
            return this;
        }

        /**
         * Max change in actual speed per tick while braking down toward the guidance speed (blocks/tick^2).
         */
        public Builder deceleration(double deceleration) {
            this.deceleration = deceleration;
            return this;
        }

        /**
         * Assign a designated strike target entity (see {@link MissileEntity#setDesignatedTarget}).
         */
        public Builder designatedTarget(UUID entityId) {
            this.designatedTargetId = entityId;
            return this;
        }

        /** Radar cross-section against a reference of 1.0. */
        public Builder rcs(float rcs) {
            this.rcs = Math.max(0.0f, rcs);
            return this;
        }

        /** Low-observable, as a switch. */
        public Builder stealth(boolean stealth) {
            return rcs(stealth ? MissileSimConfig.STEALTH_RCS : 1.0f);
        }

        /**
         * Evasion (0..1): how often this missile shrugs off an interception attempt, the tier that lets a better
         * missile escape interceptors more often (amplified in its terminal dive).
         */
        public Builder evasion(float evasion) {
            this.evasion = evasion;
            return this;
        }

        /**
         * Evasive maneuvering: when on, an evasion boost also jinks the missile off its course (a hard lateral
         * break away from the interceptor) instead of only sprinting straight, making the dodge unpredictable and
         * genuinely displacing it from the interceptor's lead.
         */
        public Builder evasiveManeuver(boolean evasiveManeuver) {
            this.evasiveManeuver = evasiveManeuver;
            return this;
        }

        public MissileEntity build() {
            MissileEntity missile = new MissileEntity(this.type, this.level);
            missile.cruiseMode = this.cruiseMode;
            missile.setMedium(this.medium);
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
            if (this.attackApproachDir != null) {
                missile.setAttackApproachDir(this.attackApproachDir);
            }
            missile.attackProfile = this.attackProfile;
            missile.approachJoinCap = this.approachJoinCap;
            missile.fragmentCount = this.fragmentCount;
            missile.impactPreloadRadius = this.impactPreloadRadius;
            boolean recursive = RecursiveFrag.ID.equals(this.detonationId);
            missile.splitDepth = (this.splitDepth != null)
                    ? this.splitDepth
                    : (recursive ? RecursiveFrag.DEFAULT_DEPTH : 0);
            missile.swarmId = this.swarmId;
            missile.commander = this.commander;
            missile.controlId = this.controlId;
            missile.teamId = this.teamId;
            if (recursive && this.explosionOffset <= 0.0f) {
                missile.explosionOffset = RecursiveFrag.splitAltitude(missile.splitDepth);
            }
            missile.cruiseSpeed = this.cruiseSpeed;
            if (this.ascentSpeed != null) {
                missile.ascentSpeed = this.ascentSpeed;
            }
            if (this.attackAngle != null) {
                missile.attackAngle = this.attackAngle;
            }
            if (this.minDiveAngle != null) {
                missile.minDiveAngle = this.minDiveAngle;
            }
            if (this.maxDiveAngle != null) {
                missile.maxDiveAngle = this.maxDiveAngle;
            }
            missile.health = this.health;
            missile.setModelId(this.modelId);
            missile.setExhaustColor(this.exhaustColor);
            if (this.flightSoundId != null) {
                missile.setFlightSound(this.flightSoundId);
            }
            missile.flightSoundRange = this.flightSoundRange;
            missile.flightSoundBasePitch = this.flightSoundBasePitch;
            missile.flightSoundSpeedPitch = this.flightSoundSpeedPitch;
            missile.damageResponseId = this.damageResponseId;
            missile.damageResponse = MissileDamageRegistry.get(this.damageResponseId);
            missile.downedAction = this.downedAction;
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

            missile.fuelType = this.fuelType;
            missile.fuelCapacity = this.fuelTicks;
            missile.fuel = this.fuelTicks;
            missile.acceleration = this.acceleration;
            missile.deceleration = this.deceleration;
            missile.designatedTargetId = this.designatedTargetId;
            missile.rcs = this.rcs;
            missile.evasion = this.evasion;
            missile.evasiveManeuver = this.evasiveManeuver;

            missile.interceptor = this.interceptor;
            missile.interceptMode = this.interceptMode;
            missile.lockTargetId = this.lockTargetId;
            if (this.interceptChance != null) {
                missile.interceptChance = this.interceptChance;
            }
            if (this.interceptor) {
                missile.ascentStageId = FlightStageRegistry.keyOf(InterceptStage.INSTANCE);
                missile.cruiseStageId = FlightStageRegistry.keyOf(InterceptStage.INSTANCE);
                missile.attackStageId = FlightStageRegistry.keyOf(InterceptStage.INSTANCE);
                missile.rebuildFlightProfile();
                missile.phase = this.startInCruise ? Phase.CRUISE : Phase.ASCEND;
                missile.armed = true;
            }
            return missile;
        }
    }
}
