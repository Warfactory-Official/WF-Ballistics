package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileEntity.CruiseMode;
import com.wf.wflib.MissileEntity.DownedAction;
import com.wf.wflib.MissileEntity.FuelType;
import com.wf.wflib.MissileEntity.InterceptMode;
import com.wf.wflib.MissileEntity.Medium;
import com.wf.wflib.MissileEntity.Phase;
import com.wf.wflib.MissileModels;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.damage.MissileDamageRegistry;
import com.wf.wflib.drone.cam.CameraSpec;
import com.wf.wflib.flight.ApproachStage;
import com.wf.wflib.flight.AttackProfile;
import com.wf.wflib.flight.FlightStageRegistry;
import com.wf.wflib.flight.InterceptStage;
import com.wf.wflib.sim.MissileSimConfig;
import com.wf.wflib.warhead.RecursiveFrag;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * <pre>{@code MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
 *     .target(pos).highAltitude(250.0).explosionOffset(30f).build();}</pre>
 */
public final class MissileBuilder {

    private final EntityType<? extends Projectile> type;
    private final Level level;

    private Vec3 target;
    private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
    private Medium medium = Medium.AIR;
    private double cruiseAltitude = 200.0;
    private double terrainClearance = 24.0;
    private float explosionOffset;
    private ResourceLocation detonationId = WarheadRegistry.defaultId();
    /** null = the phase's default stage. */
    private ResourceLocation ascentStageId;
    private ResourceLocation cruiseStageId;
    private ResourceLocation attackStageId;
    private int fragmentCount = MissileEntity.DEFAULT_FRAGMENT_COUNT;
    private int impactPreloadRadius = MissileEntity.DEFAULT_IMPACT_PRELOAD_RADIUS;
    private double cruiseSpeed = MissileEntity.CRUISE_SPEED;
    private Double ascentSpeed;
    private Double attackAngle;
    private Double minDiveAngle;
    private Double maxDiveAngle;
    private Vec3 attackApproachDir;
    private AttackProfile attackProfile = AttackProfile.SPEED;
    private double approachJoinCap = MissileEntity.DEFAULT_APPROACH_JOIN_CAP;
    /** null = scaled off the model length. */
    private Double maxTurnRate;
    private ResourceLocation modelId = MissileModels.DEFAULT;
    @Nullable
    private ResourceLocation look;
    private int exhaustColor = MissileEntity.DEFAULT_EXHAUST_COLOR;
    private ResourceLocation flightSoundId;
    private double flightSoundRange = MissileEntity.DEFAULT_FLIGHT_SOUND_RANGE;
    private float flightSoundBasePitch = 1.0f;
    private double flightSoundSpeedPitch;
    private ResourceLocation damageResponseId = MissileDamageRegistry.defaultId();
    private DownedAction downedAction = DownedAction.CRASH;
    private double dudChance = MissileEntity.DEFAULT_DUD_CHANCE;
    private boolean startInCruise;
    private boolean startInAttack;
    private boolean startArmed;
    private float health = MissileEntity.DEFAULT_HEALTH;
    /** null = warhead default (recursive_frag gets a depth, others 0). */
    private Integer splitDepth;
    private long swarmId;
    private boolean commander;
    private UUID controlId;
    private UUID teamId;
    private boolean interceptor;
    private InterceptMode interceptMode = InterceptMode.NEAREST;
    private UUID lockTargetId;
    private Float interceptChance;
    private FuelType fuelType = FuelType.SOLID;
    private int fuelTicks = MissileEntity.DEFAULT_FUEL_TICKS;
    private double acceleration = MissileEntity.DEFAULT_ACCELERATION;
    private double deceleration = MissileEntity.DEFAULT_DECELERATION;
    private UUID designatedTargetId;
    private CameraSpec seeker;
    private SeekerMode seekerMode = SeekerMode.DESIGNATED;
    private float seekerFov = 30.0f;
    private double radarRange = 1024.0;
    private float proximity;
    private float impactDamage;
    private ThreatKind threatKind;
    private float caliber;
    private float penetration;
    private float blastSize;
    private boolean breaksBlocks = true;
    private double dragK;
    private boolean glides;
    private double gLimit;
    private double gRefSpeed = 1.0;
    private float rcs = 1.0f;
    private float evasion;
    private boolean evasiveManeuver;

    public MissileBuilder(EntityType<? extends Projectile> type, Level level) {
        this.type = type;
        this.level = level;
    }

    public MissileBuilder target(Vec3 target) {
        this.target = target;
        return this;
    }

    /** Fixed altitude, terrain ignored except as a floor. */
    public MissileBuilder highAltitude(double cruiseAltitude) {
        this.cruiseMode = CruiseMode.HIGH_ALTITUDE;
        this.cruiseAltitude = cruiseAltitude;
        return this;
    }

    /** Hold {@code terrainClearance} over nearby terrain. */
    public MissileBuilder terrainFollow(double terrainClearance) {
        this.cruiseMode = CruiseMode.TERRAIN_FOLLOW;
        this.terrainClearance = terrainClearance;
        return this;
    }

    /** Clearance without changing the mode: floor for fixed-altitude flight / seabed floor for a torpedo. */
    public MissileBuilder clearance(double terrainClearance) {
        this.terrainClearance = terrainClearance;
        return this;
    }

    public MissileBuilder medium(Medium medium) {
        this.medium = medium;
        return this;
    }

    /** Airburst height above the target; 0 = contact. */
    public MissileBuilder explosionOffset(float offset) {
        this.explosionOffset = offset;
        return this;
    }

    /** {@link WarheadRegistry} id. */
    public MissileBuilder detonation(ResourceLocation detonationId) {
        this.detonationId = detonationId;
        return this;
    }

    public MissileBuilder ascentStage(ResourceLocation id) {
        this.ascentStageId = id;
        return this;
    }

    public MissileBuilder cruiseStage(ResourceLocation id) {
        this.cruiseStageId = id;
        return this;
    }

    public MissileBuilder attackStage(ResourceLocation id) {
        this.attackStageId = id;
        return this;
    }

    /** Bomblets for the fragmentation warhead. */
    public MissileBuilder fragmentCount(int fragmentCount) {
        this.fragmentCount = fragmentCount;
        return this;
    }

    /** Chunk radius force-loaded around the aim point in the terminal run. */
    public MissileBuilder impactPreloadRadius(int chunkRadius) {
        this.impactPreloadRadius = Math.max(0, chunkRadius);
        return this;
    }

    /** Max heading change per tick (radians). */
    public MissileBuilder turnRate(double radiansPerTick) {
        this.maxTurnRate = radiansPerTick;
        return this;
    }

    public MissileBuilder cruiseSpeed(double blocksPerTick) {
        this.cruiseSpeed = blocksPerTick;
        return this;
    }

    public MissileBuilder ascentSpeed(double blocksPerTick) {
        this.ascentSpeed = blocksPerTick;
        return this;
    }

    /** Preferred dive, degrees below horizontal (90 = top attack), uncapped. */
    public MissileBuilder attackAngle(double degrees) {
        this.attackAngle = degrees;
        return this;
    }

    /** Without {@link #attackAngle}: dive auto-picked in this range by raycast for the clearest approach. */
    public MissileBuilder diveAngleRange(double minDegrees, double maxDegrees) {
        this.minDiveAngle = minDegrees;
        this.maxDiveAngle = maxDegrees;
        return this;
    }

    /** Horizontal direction target -> approach origin ("from the west" = westward). */
    public MissileBuilder attackFrom(double dirX, double dirZ) {
        double h = Math.sqrt(dirX * dirX + dirZ * dirZ);
        this.attackApproachDir = h < 1.0E-4 ? null : new Vec3(dirX / h, 0.0, dirZ / h);
        return this;
    }

    public MissileBuilder attackProfile(@Nullable AttackProfile profile) {
        this.attackProfile = profile == null ? AttackProfile.SPEED : profile;
        return this;
    }

    /** Max distance out a directional strike joins its attack line ({@link ApproachStage}). */
    public MissileBuilder approachJoinCap(double cap) {
        this.approachJoinCap = cap;
        return this;
    }

    public MissileBuilder model(ResourceLocation modelId) {
        this.modelId = modelId;
        return this;
    }

    /** Client look through {@code RoundRenderers}; the airframe {@link #model} still sizes and steers it. */
    public MissileBuilder look(ResourceLocation id) {
        this.look = id;
        return this;
    }

    /** Hot 0xRRGGBB the plume fades from. */
    public MissileBuilder exhaustColor(int rgb) {
        this.exhaustColor = rgb;
        return this;
    }

    public MissileBuilder flightSound(ResourceLocation id) {
        this.flightSoundId = id;
        return this;
    }

    /** Fade distance = server broadcast radius (audio is pushed, not tracked). */
    public MissileBuilder flightSoundRange(double blocks) {
        this.flightSoundRange = blocks;
        return this;
    }

    public MissileBuilder flightSoundBasePitch(float pitch) {
        this.flightSoundBasePitch = pitch;
        return this;
    }

    /** Pitch added per block/tick of own speed. */
    public MissileBuilder flightSoundSpeedPitch(double perBlockPerTick) {
        this.flightSoundSpeedPitch = perBlockPerTick;
        return this;
    }

    public MissileBuilder damageResponse(@Nullable ResourceLocation id) {
        this.damageResponseId = id != null ? id : MissileDamageRegistry.defaultId();
        return this;
    }

    public MissileBuilder downedAction(@Nullable DownedAction action) {
        this.downedAction = action != null ? action : DownedAction.CRASH;
        return this;
    }

    public MissileBuilder dudChance(double chance) {
        this.dudChance = Mth.clamp(chance, 0.0D, 1.0D);
        return this;
    }

    public MissileBuilder startInCruise() {
        this.startInCruise = true;
        return this;
    }

    public MissileBuilder startInAttack() {
        this.startInAttack = true;
        return this;
    }

    /** {@link RecursiveFrag} generations; unset = warhead default. */
    public MissileBuilder splitDepth(int splitDepth) {
        this.splitDepth = splitDepth;
        return this;
    }

    /** Non-zero family id: members never collide with each other. */
    public MissileBuilder swarmId(long swarmId) {
        this.swarmId = swarmId;
        return this;
    }

    public MissileBuilder commander(boolean commander) {
        this.commander = commander;
        return this;
    }

    /** Launcher id: rounds from one launcher are friendly to each other. */
    public MissileBuilder controlId(UUID controlId) {
        this.controlId = controlId;
        return this;
    }

    /** WarForge faction: same/allied/truced factions are friendly. */
    public MissileBuilder teamId(UUID teamId) {
        this.teamId = teamId;
        return this;
    }

    public MissileBuilder startArmed() {
        this.startArmed = true;
        return this;
    }

    public MissileBuilder health(float health) {
        this.health = health;
        return this;
    }

    /** Homes on air targets and rolls a kill instead of carrying a warhead. */
    public MissileBuilder interceptor(boolean interceptor) {
        this.interceptor = interceptor;
        return this;
    }

    public MissileBuilder interceptMode(InterceptMode mode) {
        this.interceptMode = mode;
        return this;
    }

    public MissileBuilder lockTarget(UUID targetId) {
        this.interceptMode = InterceptMode.LOCK;
        this.lockTargetId = targetId;
        return this;
    }

    public MissileBuilder interceptChance(float chance) {
        this.interceptChance = chance;
        return this;
    }

    /** Propellant and ticks of powered flight; then ballistic. */
    public MissileBuilder fuel(FuelType type, int ticks) {
        this.fuelType = type;
        this.fuelTicks = ticks;
        return this;
    }

    /** blocks/tick^2 spooling up. */
    public MissileBuilder acceleration(double acceleration) {
        this.acceleration = acceleration;
        return this;
    }

    /** blocks/tick^2 braking. */
    public MissileBuilder deceleration(double deceleration) {
        this.deceleration = deceleration;
        return this;
    }

    /** TV round: operator flies it through this camera. */
    public MissileBuilder seeker(CameraSpec seeker) {
        this.seeker = seeker;
        return this;
    }

    /** Lock keeping after launch; {@code fov} half-angle degrees; {@code radarRange} active radar on + scan. */
    public MissileBuilder seekerMode(SeekerMode mode, float fov, double radarRange) {
        this.seekerMode = mode;
        this.seekerFov = fov;
        this.radarRange = radarRange;
        return this;
    }

    /** Proximity fuze radius; 0 = contact only. */
    public MissileBuilder proximity(float radius) {
        this.proximity = radius;
        return this;
    }

    /** Direct-hit damage to the struck entity before the warhead. */
    public MissileBuilder impactDamage(float damage) {
        this.impactDamage = damage;
        return this;
    }

    /** Armour-model threat; {@code caliber} 0 = airframe diameter. */
    public MissileBuilder threat(@Nullable ThreatKind kind, float caliber, float penetration) {
        this.threatKind = kind;
        this.caliber = caliber;
        this.penetration = penetration;
        return this;
    }

    /** Blast size over the warhead's own; {@code breaksBlocks} false = entities only. */
    public MissileBuilder blast(float size, boolean breaksBlocks) {
        this.blastSize = size;
        this.breaksBlocks = breaksBlocks;
        return this;
    }

    /** Quadratic drag k (dv = -k v^2 per tick); {@code glides} = guided on momentum after burn-out. */
    public MissileBuilder drag(double k, boolean glides) {
        this.dragK = k;
        this.glides = glides;
        return this;
    }

    /** Lateral acceleration limit (blocks/tick^2) with full authority from {@code refSpeed}; replaces turnRate. */
    public MissileBuilder gLimit(double accel, double refSpeed) {
        this.gLimit = accel;
        this.gRefSpeed = refSpeed;
        return this;
    }

    public MissileBuilder designatedTarget(UUID entityId) {
        this.designatedTargetId = entityId;
        return this;
    }

    public MissileBuilder rcs(float rcs) {
        this.rcs = Math.max(0.0f, rcs);
        return this;
    }

    public MissileBuilder stealth(boolean stealth) {
        return rcs(stealth ? MissileSimConfig.STEALTH_RCS : 1.0f);
    }

    /** 0..1 chance to shrug off an interception attempt (amplified in the dive). */
    public MissileBuilder evasion(float evasion) {
        this.evasion = evasion;
        return this;
    }

    /** Evasion sprints also jink laterally away from the interceptor. */
    public MissileBuilder evasiveManeuver(boolean evasiveManeuver) {
        this.evasiveManeuver = evasiveManeuver;
        return this;
    }

    public MissileEntity build() {
        MissileEntity missile = new MissileEntity(this.type, this.level);
        MissileFlight flight = missile.flight();
        flight.cruiseMode = this.cruiseMode;
        flight.setMedium(this.medium);
        flight.cruiseAltitude = this.cruiseAltitude;
        flight.terrainClearance = this.terrainClearance;
        if (this.ascentStageId != null) {
            flight.ascentStageId = this.ascentStageId;
        }
        if (this.cruiseStageId != null) {
            flight.cruiseStageId = this.cruiseStageId;
        }
        if (this.attackStageId != null) {
            flight.attackStageId = this.attackStageId;
        }
        flight.rebuildProfile();
        if (this.attackApproachDir != null) {
            flight.setAttackApproachDir(this.attackApproachDir);
        }
        flight.attackProfile = this.attackProfile;
        flight.approachJoinCap = this.approachJoinCap;
        flight.cruiseSpeed = this.cruiseSpeed;
        if (this.ascentSpeed != null) {
            flight.ascentSpeed = this.ascentSpeed;
        }
        if (this.attackAngle != null) {
            flight.attackAngle = this.attackAngle;
        }
        if (this.minDiveAngle != null) {
            flight.minDiveAngle = this.minDiveAngle;
        }
        if (this.maxDiveAngle != null) {
            flight.maxDiveAngle = this.maxDiveAngle;
        }

        MissileFuze fuze = missile.fuze();
        fuze.explosionOffset = this.explosionOffset;
        fuze.setDetonation(this.detonationId);
        fuze.fragmentCount = this.fragmentCount;
        fuze.impactPreloadRadius = this.impactPreloadRadius;
        fuze.armed = this.startArmed;

        fuze.proximityRadius = this.proximity;
        fuze.impactDamage = this.impactDamage;
        fuze.threatKind = this.threatKind;
        fuze.caliber = this.caliber;
        fuze.penetration = this.penetration;
        fuze.blastSize = this.blastSize;
        fuze.breaksBlocks = this.breaksBlocks;
        flight.gLimit = this.gLimit;
        flight.gRefSpeed = this.gRefSpeed;

        MissileSwarm swarm = missile.swarm();
        boolean recursive = RecursiveFrag.ID.equals(this.detonationId);
        swarm.splitDepth = this.splitDepth != null ? this.splitDepth : (recursive ? RecursiveFrag.DEFAULT_DEPTH : 0);
        swarm.swarmId = this.swarmId;
        swarm.commander = this.commander;
        if (recursive && this.explosionOffset <= 0.0f) {
            fuze.explosionOffset = RecursiveFrag.splitAltitude(swarm.splitDepth);
        }

        missile.setControlId(this.controlId);
        missile.setTeamId(this.teamId);
        missile.setModelId(this.modelId);
        missile.setLook(this.look);
        missile.setExhaustColor(this.exhaustColor);
        if (this.flightSoundId != null) {
            missile.setFlightSound(this.flightSoundId);
        }
        missile.setFlightSoundShape(this.flightSoundRange, this.flightSoundBasePitch, this.flightSoundSpeedPitch);

        MissileDamage damage = missile.damage();
        damage.health = this.health;
        damage.setResponse(this.damageResponseId);
        damage.downedAction = this.downedAction;
        damage.dudChance = this.dudChance;

        flight.maxTurnRate = this.maxTurnRate != null
                ? this.maxTurnRate : MissileFlight.TURN_AGILITY / MissileModels.length(missile.getModelId());
        if (this.target != null) {
            flight.setTarget(this.target);
        }
        if (this.startInAttack) {
            flight.phase = Phase.ATTACK;
        } else if (this.startInCruise) {
            flight.phase = Phase.CRUISE;
        }

        MissileMotor motor = missile.motor();
        motor.fuelType = this.fuelType;
        motor.fuelCapacity = this.fuelTicks;
        motor.fuel = this.fuelTicks;
        motor.acceleration = this.acceleration;
        motor.deceleration = this.deceleration;
        motor.dragK = this.dragK;
        motor.glides = this.glides;

        missile.seeker().designatedTargetId = this.designatedTargetId;
        missile.seeker().spec = this.seeker;
        missile.seeker().mode = this.seekerMode;
        missile.seeker().fov = this.seekerFov;
        missile.seeker().radarRange = this.radarRange;

        MissileSignature signature = missile.signature();
        signature.rcs = this.rcs;
        signature.evasion = this.evasion;
        signature.evasiveManeuver = this.evasiveManeuver;

        MissileInterceptor interceptor = missile.interceptor();
        interceptor.active = this.interceptor;
        interceptor.mode = this.interceptMode;
        interceptor.lockTargetId = this.lockTargetId;
        if (this.interceptChance != null) {
            interceptor.chance = this.interceptChance;
        }
        if (this.interceptor) {
            ResourceLocation intercept = FlightStageRegistry.keyOf(InterceptStage.INSTANCE);
            flight.ascentStageId = intercept;
            flight.cruiseStageId = intercept;
            flight.attackStageId = intercept;
            flight.rebuildProfile();
            flight.phase = this.startInCruise ? Phase.CRUISE : Phase.ASCEND;
            fuze.armed = true;
        }
        return missile;
    }
}
