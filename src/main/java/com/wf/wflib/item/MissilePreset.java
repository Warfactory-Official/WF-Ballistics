package com.wf.wflib.item;

import com.wf.wflib.missile.MissileBuilder;
import com.wf.wflib.missile.SeekerMode;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileModels;
import com.wf.wflib.ModEntities;
import com.wf.wflib.drone.cam.CameraSpec;
import com.wf.wflib.flight.FlightStageRegistry;
import com.wf.wflib.sim.MissileSimConfig;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * An immutable, launch-ready missile configuration: the full {@link MissileBuilder} minus the target, which
 * is supplied at fire time.
 */
public final class MissilePreset {

    private final ResourceLocation id;
    private final ResourceLocation modelId;
    private final ResourceLocation warheadId;
    private final boolean highAltitude;
    private final double altitudeParam; // cruiseAltitude (high) or terrainClearance (terrain follow)
    private final double terrainClearance; // NaN = whatever the cruise mode implies; set to split the two
    private final double cruiseSpeed;
    private final double turnRate;       // <= 0 = model-size default
    private final double approachJoinCap; // directional-strike join ceiling (blocks)
    private final float health;
    private final int fragmentCount;
    private final int impactPreloadRadius;
    private final float explosionOffset;
    private final int splitDepth;
    private final boolean interceptor;
    private final float interceptChance;
    private final MissileEntity.FuelType fuelType;
    private final int fuelTicks;
    private final double acceleration;
    private final double deceleration;
    private final ResourceLocation ascentStageId;
    private final ResourceLocation cruiseStageId;
    private final ResourceLocation attackStageId;
    private final MissileEntity.Medium medium;
    private final double attackAngle;
    private final double minDiveAngle;
    private final double maxDiveAngle;
    private final float rcs;
    private final float evasion;
    private final boolean evasiveManeuver;
    private final double accuracy;
    private final int exhaustColor;
    private final ResourceLocation flightSoundId;
    private final double flightSoundRange;
    private final float flightSoundBasePitch;
    private final double flightSoundSpeedPitch;
    private final ResourceLocation damageResponseId;
    private final MissileEntity.DownedAction downedAction;
    private final DownedActionPicker downedActionPicker;
    private final double dudChance;
    private final CameraSpec seeker;
    private final SeekerMode seekerMode;
    private final float seekerFov;
    private final double radarRange;
    private final float proximity;
    private final float impactDamage;
    private final ThreatKind threatKind;
    private final float caliber;
    private final float penetration;
    private final float blastSize;
    private final boolean breaksBlocks;
    private final double dragK;
    private final boolean glides;
    private final double gLimit;
    private final double gRefSpeed;

    private MissilePreset(Builder b) {
        this.id = b.id;
        this.modelId = b.modelId;
        this.warheadId = b.warheadId;
        this.highAltitude = b.highAltitude;
        this.altitudeParam = b.altitudeParam;
        this.terrainClearance = b.terrainClearance;
        this.cruiseSpeed = b.cruiseSpeed;
        this.turnRate = b.turnRate;
        this.approachJoinCap = b.approachJoinCap;
        this.health = b.health;
        this.fragmentCount = b.fragmentCount;
        this.impactPreloadRadius = b.impactPreloadRadius;
        this.explosionOffset = b.explosionOffset;
        this.splitDepth = b.splitDepth;
        this.interceptor = b.interceptor;
        this.interceptChance = b.interceptChance;
        this.fuelType = b.fuelType;
        this.fuelTicks = b.fuelTicks;
        this.acceleration = b.acceleration;
        this.deceleration = b.deceleration;
        this.ascentStageId = b.ascentStageId;
        this.cruiseStageId = b.cruiseStageId;
        this.attackStageId = b.attackStageId;
        this.medium = b.medium;
        this.attackAngle = b.attackAngle;
        this.minDiveAngle = b.minDiveAngle;
        this.maxDiveAngle = b.maxDiveAngle;
        this.rcs = b.rcs;
        this.evasion = b.evasion;
        this.evasiveManeuver = b.evasiveManeuver;
        this.accuracy = b.accuracy;
        this.exhaustColor = b.exhaustColor;
        this.flightSoundId = b.flightSoundId;
        this.flightSoundRange = b.flightSoundRange;
        this.flightSoundBasePitch = b.flightSoundBasePitch;
        this.flightSoundSpeedPitch = b.flightSoundSpeedPitch;
        this.damageResponseId = b.damageResponseId;
        this.downedAction = b.downedAction;
        this.downedActionPicker = b.downedActionPicker;
        this.dudChance = b.dudChance;
        this.seeker = b.seeker;
        this.seekerMode = b.seekerMode;
        this.seekerFov = b.seekerFov;
        this.radarRange = b.radarRange;
        this.proximity = b.proximity;
        this.impactDamage = b.impactDamage;
        this.threatKind = b.threatKind;
        this.caliber = b.caliber;
        this.penetration = b.penetration;
        this.blastSize = b.blastSize;
        this.breaksBlocks = b.breaksBlocks;
        this.dragK = b.dragK;
        this.glides = b.glides;
        this.gLimit = b.gLimit;
        this.gRefSpeed = b.gRefSpeed;
    }

    public static Builder builder(ResourceLocation id, ResourceLocation modelId, ResourceLocation warheadId) {
        return new Builder(id, modelId, warheadId);
    }

    public ResourceLocation id() {
        return id;
    }

    public ResourceLocation modelId() {
        return modelId;
    }

    public ResourceLocation warheadId() {
        return warheadId;
    }

    public boolean isInterceptor() {
        return interceptor;
    }

    public boolean highAltitude() {
        return highAltitude;
    }

    public double altitudeParam() {
        return altitudeParam;
    }

    public double cruiseSpeed() {
        return cruiseSpeed;
    }

    public double turnRate() {
        return turnRate;
    }

    public double approachJoinCap() {
        return approachJoinCap;
    }

    public float health() {
        return health;
    }

    public int fragmentCount() {
        return fragmentCount;
    }

    public int impactPreloadRadius() {
        return impactPreloadRadius;
    }

    public float explosionOffset() {
        return explosionOffset;
    }

    public int splitDepth() {
        return splitDepth;
    }

    public float interceptChance() {
        return interceptChance;
    }

    public MissileEntity.FuelType fuelType() {
        return fuelType;
    }

    public int fuelTicks() {
        return fuelTicks;
    }

    public double acceleration() {
        return acceleration;
    }

    public double deceleration() {
        return deceleration;
    }

    public ResourceLocation cruiseStageId() {
        return cruiseStageId;
    }

    public ResourceLocation attackStageId() {
        return attackStageId;
    }

    public double attackAngle() {
        return attackAngle;
    }

    public double minDiveAngle() {
        return minDiveAngle;
    }

    public double maxDiveAngle() {
        return maxDiveAngle;
    }

    /**
     * @return this preset's radar cross-section, against a reference of 1.0.
     */
    public float rcs() {
        return rcs;
    }

    /**
     * @return true if this preset is low-observable. Display only; see {@link MissileEntity#isStealth}.
     */
    public boolean isStealth() {
        return rcs < MissileSimConfig.STEALTH_RCS_THRESHOLD;
    }

    public float evasion() {
        return evasion;
    }

    public boolean isEvasiveManeuver() {
        return evasiveManeuver;
    }

    /** Circular-error radius (blocks) the aimpoint is scattered by at launch; 0 = pinpoint. */
    public double accuracy() {
        return accuracy;
    }

    public int exhaustColor() {
        return exhaustColor;
    }

    public ResourceLocation flightSoundId() {
        return flightSoundId;
    }

    public ResourceLocation damageResponseId() {
        return damageResponseId;
    }

    public MissileEntity.DownedAction downedAction() {
        return downedAction;
    }

    public double dudChance() {
        return dudChance;
    }

    public SeekerMode seekerMode() {
        return seekerMode;
    }

    /** @return the TV seeker, or null for a round nobody can fly. */
    public CameraSpec seeker() {
        return seeker;
    }

    /**
     * Builds (but does not spawn) a live missile aimed at {@code target}.
     */
    public MissileEntity build(Level level, Vec3 target) {
        return builder(level, target).build();
    }

    /** {@link #build} before {@link MissileBuilder#build()}: per-launch overrides (attack profile, join cap). */
    public MissileBuilder builder(Level level, Vec3 target) {
        Vec3 aim = target;
        if (accuracy > 0.0) {
            double ang = level.random.nextDouble() * Math.PI * 2.0;
            double rad = accuracy * Math.sqrt(level.random.nextDouble());
            aim = new Vec3(target.x + Math.cos(ang) * rad, target.y, target.z + Math.sin(ang) * rad);
        }
        MissileBuilder b = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .model(modelId)
                .detonation(warheadId)
                .target(aim)
                .cruiseSpeed(cruiseSpeed)
                .health(health)
                .fragmentCount(fragmentCount)
                .impactPreloadRadius(impactPreloadRadius)
                .explosionOffset(explosionOffset);
        if (highAltitude) {
            b.highAltitude(altitudeParam);
        } else {
            b.terrainFollow(altitudeParam);
        }
        if (!Double.isNaN(terrainClearance)) {
            b.clearance(terrainClearance);
        }
        if (turnRate > 0.0) {
            b.turnRate(turnRate);
        }
        b.approachJoinCap(approachJoinCap);
        if (splitDepth > 0) {
            b.splitDepth(splitDepth);
        }
        if (interceptor) {
            b.interceptor(true).interceptChance(interceptChance);
        }
        b.fuel(fuelType, fuelTicks).acceleration(acceleration).deceleration(deceleration);
        b.medium(medium);
        if (ascentStageId != null) {
            b.ascentStage(ascentStageId);
        }
        if (cruiseStageId != null) {
            b.cruiseStage(cruiseStageId);
        }
        if (attackStageId != null) {
            b.attackStage(attackStageId);
        }
        if (!Double.isNaN(attackAngle)) {
            b.attackAngle(attackAngle);
        }
        b.diveAngleRange(minDiveAngle, maxDiveAngle);
        b.rcs(rcs);
        if (evasion > 0.0f) {
            b.evasion(evasion);
        }
        if (evasiveManeuver) {
            b.evasiveManeuver(true);
        }
        b.exhaustColor(exhaustColor);
        if (flightSoundId != null) {
            b.flightSound(flightSoundId);
        }
        b.flightSoundRange(flightSoundRange)
                .flightSoundBasePitch(flightSoundBasePitch)
                .flightSoundSpeedPitch(flightSoundSpeedPitch);
        if (damageResponseId != null) {
            b.damageResponse(damageResponseId);
        }
        // Roll the shot-down behaviour per launch when a weighted/random picker was given, else use the fixed one.
        b.downedAction(downedActionPicker != null ? downedActionPicker.pick(level.random) : downedAction);
        b.dudChance(dudChance);
        if (seeker != null) {
            b.seeker(seeker);
        }
        b.seekerMode(seekerMode, seekerFov, radarRange);
        b.proximity(proximity).impactDamage(impactDamage).threat(threatKind, caliber, penetration)
                .blast(blastSize, breaksBlocks).drag(dragK, glides);
        if (gLimit > 0.0) {
            b.gLimit(gLimit, gRefSpeed);
        }
        return b;
    }

    /** Picks a {@link MissileEntity.DownedAction} at launch time: call e.g. */
    @FunctionalInterface
    public interface DownedActionPicker {
        MissileEntity.DownedAction pick(RandomSource random);

        /** A picker that chooses among {@code weights} (action → integer share) in proportion to their weights. */
        static DownedActionPicker weighted(Map<MissileEntity.DownedAction, Integer> weights) {
            int total = 0;
            for (int w : weights.values()) {
                total += Math.max(0, w);
            }
            final int sum = total;
            return random -> {
                if (sum <= 0) {
                    return MissileEntity.DownedAction.CRASH;
                }
                int roll = random.nextInt(sum);
                int acc = 0;
                for (Map.Entry<MissileEntity.DownedAction, Integer> e : weights.entrySet()) {
                    acc += Math.max(0, e.getValue());
                    if (roll < acc) {
                        return e.getKey();
                    }
                }
                return MissileEntity.DownedAction.CRASH;
            };
        }
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final ResourceLocation modelId;
        private final ResourceLocation warheadId;
        private boolean highAltitude = false;
        private double altitudeParam = 24.0;
        private double terrainClearance = Double.NaN;
        private double cruiseSpeed = MissileEntity.CRUISE_SPEED;
        private double turnRate = 0.0;
        private double approachJoinCap = MissileEntity.DEFAULT_APPROACH_JOIN_CAP;
        private float health = MissileEntity.DEFAULT_HEALTH;
        private int fragmentCount = MissileEntity.DEFAULT_FRAGMENT_COUNT;
        private int impactPreloadRadius = MissileEntity.DEFAULT_IMPACT_PRELOAD_RADIUS;
        private float explosionOffset = 0.0f;
        private int splitDepth = 0;
        private boolean interceptor = false;
        private float interceptChance = MissileSimConfig.DEFAULT_INTERCEPT_CHANCE;
        private MissileEntity.FuelType fuelType = MissileEntity.FuelType.SOLID;
        private int fuelTicks = MissileEntity.DEFAULT_FUEL_TICKS;
        private double acceleration = MissileEntity.DEFAULT_ACCELERATION;
        private double deceleration = MissileEntity.DEFAULT_DECELERATION;
        private ResourceLocation ascentStageId = null; // null = phase default
        private ResourceLocation cruiseStageId = null; // null = phase default
        private MissileEntity.Medium medium = MissileEntity.Medium.AIR;
        private ResourceLocation attackStageId = null;
        private double attackAngle = Double.NaN;
        private double minDiveAngle = MissileEntity.DEFAULT_MIN_DIVE_ANGLE;
        private double maxDiveAngle = MissileEntity.DEFAULT_MAX_DIVE_ANGLE;
        private float rcs = 1.0f;
        private float evasion = 0.0f;
        private boolean evasiveManeuver = false;
        private double accuracy = 0.0;
        private int exhaustColor = MissileEntity.DEFAULT_EXHAUST_COLOR;
        private ResourceLocation flightSoundId = null;      // null = WF-B's default missile_flight loop
        private double flightSoundRange = MissileEntity.DEFAULT_FLIGHT_SOUND_RANGE;
        private float flightSoundBasePitch = 1.0f;
        private double flightSoundSpeedPitch = 0.0;
        private ResourceLocation damageResponseId = null;   // null = standard (take damage as dealt)
        private MissileEntity.DownedAction downedAction = MissileEntity.DownedAction.CRASH;
        private DownedActionPicker downedActionPicker = null; // non-null = roll the action per launch
        private double dudChance = MissileEntity.DEFAULT_DUD_CHANCE;
        private CameraSpec seeker = null;
        private SeekerMode seekerMode = SeekerMode.DESIGNATED;
        private float seekerFov = 30.0f;
        private double radarRange = 1024.0;
        private float proximity = 0.0f;
        private float impactDamage = 0.0f;
        private ThreatKind threatKind = null;
        private float caliber = 0.0f;
        private float penetration = 0.0f;
        private float blastSize = 0.0f;
        private boolean breaksBlocks = true;
        private double dragK = 0.0;
        private boolean glides = false;
        private double gLimit = 0.0;
        private double gRefSpeed = 1.0;

        private Builder(ResourceLocation id, ResourceLocation modelId, ResourceLocation warheadId) {
            this.id = id;
            this.modelId = MissileModels.exists(modelId) ? modelId : MissileModels.defaultId();
            this.warheadId = WarheadRegistry.exists(warheadId) ? warheadId : WarheadRegistry.defaultId();
        }

        /**
         * Hold at least this much clearance over the terrain even in {@link #highAltitude} mode, where the altitude
         * parameter is the cruise height and says nothing about the floor.
         */
        public Builder clearance(double blocks) {
            this.terrainClearance = blocks;
            return this;
        }

        /**
         * Fly at a fixed altitude, ignoring terrain.
         */
        public Builder highAltitude(double cruiseAltitude) {
            this.highAltitude = true;
            this.altitudeParam = cruiseAltitude;
            return this;
        }

        /**
         * Hug the ground at the given clearance (the default).
         */
        public Builder terrainFollow(double clearance) {
            this.highAltitude = false;
            this.altitudeParam = clearance;
            return this;
        }

        public Builder cruiseSpeed(double blocksPerTick) {
            this.cruiseSpeed = blocksPerTick;
            return this;
        }

        public Builder turnRate(double radiansPerTick) {
            this.turnRate = radiansPerTick;
            return this;
        }

        /**
         * Ceiling (blocks) on how far out a directional strike joins its attack line (see {@link
         * com.wf.wflib.flight.ApproachStage}).
         */
        public Builder approachJoinCap(double blocks) {
            this.approachJoinCap = blocks;
            return this;
        }

        public Builder health(float health) {
            this.health = health;
            return this;
        }

        public Builder fragmentCount(int fragmentCount) {
            this.fragmentCount = fragmentCount;
            return this;
        }

        /**
         * Chunk radius force-loaded around the aim point during the terminal run so the warhead detonates into
         * loaded terrain (see {@link MissileEntity#DEFAULT_IMPACT_PRELOAD_RADIUS}).
         */
        public Builder impactPreloadRadius(int chunkRadius) {
            this.impactPreloadRadius = Math.max(0, chunkRadius);
            return this;
        }

        /**
         * Airburst this many blocks above the target (0 = contact).
         */
        public Builder explosionOffset(float offset) {
            this.explosionOffset = offset;
            return this;
        }

        /**
         * Recursive-fragmentation generations (see the {@code recursive_frag} warhead).
         */
        public Builder splitDepth(int splitDepth) {
            this.splitDepth = splitDepth;
            return this;
        }

        /**
         * Make this preset an interceptor with the given kill chance (see {@link
         * MissileBuilder#interceptor}).
         */
        public Builder interceptor(float chance) {
            this.interceptor = true;
            this.interceptChance = chance;
            return this;
        }

        /**
         * Load the tank: {@code type} of propellant and {@code ticks} of powered flight (see {@link
         * MissileBuilder#fuel}).
         */
        public Builder fuel(MissileEntity.FuelType type, int ticks) {
            this.fuelType = type;
            this.fuelTicks = ticks;
            return this;
        }

        /**
         * Acceleration / deceleration limits (blocks/tick^2) governing how fast actual speed reaches and sheds the
         * cruise (target) speed.
         */
        public Builder accel(double acceleration, double deceleration) {
            this.acceleration = acceleration;
            this.deceleration = deceleration;
            return this;
        }

        /** Pick the ascent-phase flight stage by id. */
        public Builder ascentStage(ResourceLocation id) {
            this.ascentStageId = FlightStageRegistry.exists(MissileEntity.Phase.ASCEND, id)
                    ? id : FlightStageRegistry.defaultId(MissileEntity.Phase.ASCEND);
            return this;
        }

        /** Travel through the given medium (see {@link MissileBuilder#medium}). */
        public Builder medium(MissileEntity.Medium medium) {
            this.medium = medium;
            return this;
        }

        /**
         * Make this a torpedo: {@link MissileEntity.Medium#WATER} plus the whole submerged stage set, which is the
         * only combination of the two that flies.
         */
        public Builder torpedo() {
            return this.medium(MissileEntity.Medium.WATER)
                    .ascentStage(FlightStageRegistry.rl("torpedo_entry"))
                    .cruiseStage(FlightStageRegistry.rl("torpedo_run"))
                    .attackStage(FlightStageRegistry.rl("torpedo_terminal"));
        }

        /** Pick the cruise-phase flight stage by id (e.g. */
        public Builder cruiseStage(ResourceLocation id) {
            this.cruiseStageId = FlightStageRegistry.exists(MissileEntity.Phase.CRUISE, id)
                    ? id : FlightStageRegistry.defaultId(MissileEntity.Phase.CRUISE);
            return this;
        }

        /** Pick the attack-phase flight stage by id (e.g. */
        public Builder attackStage(ResourceLocation id) {
            this.attackStageId = FlightStageRegistry.exists(MissileEntity.Phase.ATTACK, id)
                    ? id : FlightStageRegistry.defaultId(MissileEntity.Phase.ATTACK);
            return this;
        }

        /** Explicit preferred dive angle in degrees below horizontal (90 = straight down), uncapped. */
        public Builder attackAngle(double degrees) {
            this.attackAngle = degrees;
            return this;
        }

        /**
         * Range (degrees below horizontal) the terminal dive auto-picks from when no explicit {@link #attackAngle}
         * is set (see {@link MissileBuilder#diveAngleRange}).
         */
        public Builder diveAngleRange(double minDegrees, double maxDegrees) {
            this.minDiveAngle = minDegrees;
            this.maxDiveAngle = maxDegrees;
            return this;
        }

        /**
         * Radar cross-section against a reference of 1.0. Detection range goes as the fourth root of it.
         */
        public Builder rcs(float rcs) {
            this.rcs = Math.max(0.0f, rcs);
            return this;
        }

        /**
         * Low-observable, as a switch: see {@link MissileSimConfig#STEALTH_RCS} for what it is worth in range.
         */
        public Builder stealth() {
            return rcs(MissileSimConfig.STEALTH_RCS);
        }

        /**
         * Evasion (0..1): how often this missile escapes an interception (see {@link MissileEntity#getEvasion}).
         */
        public Builder evasion(float evasion) {
            this.evasion = evasion;
            return this;
        }

        /**
         * Evasive maneuvering: makes evasion boosts jink off-course instead of sprinting straight (see {@link
         * MissileBuilder#evasiveManeuver}).
         */
        public Builder evasiveManeuver() {
            this.evasiveManeuver = true;
            return this;
        }

        /**
         * Circular error probable (blocks): the aimpoint is randomly scattered within a disk of this radius at
         * launch, so the missile lands off-target by up to {@code blocks}.
         */
        public Builder accuracy(double blocks) {
            this.accuracy = Math.max(0.0, blocks);
            return this;
        }

        /**
         * Tint of the exhaust trail (hot RGB 0xRRGGBB) the client-side plume fades from (see {@link
         * MissileBuilder#exhaustColor}).
         */
        public Builder exhaustColor(int rgb) {
            this.exhaustColor = rgb;
            return this;
        }

        /**
         * The looping flight sound this missile plays client-side, by registered {@link
         * net.minecraft.sounds.SoundEvent} id (see {@link MissileBuilder#flightSound}).
         */
        public Builder flightSound(ResourceLocation soundId) {
            this.flightSoundId = soundId;
            return this;
        }

        /**
         * Distance (blocks) at which this missile's flight loop fades to silence and the server broadcasts it:
         * independent of view/render distance (see {@link MissileBuilder#flightSoundRange}).
         */
        public Builder flightSoundRange(double blocks) {
            this.flightSoundRange = blocks;
            return this;
        }

        /** Idle engine pitch of the flight loop (see {@link MissileBuilder#flightSoundBasePitch}). */
        public Builder flightSoundBasePitch(float pitch) {
            this.flightSoundBasePitch = pitch;
            return this;
        }

        /**
         * Engine "rev": added flight-loop pitch per block/tick of the missile's own speed (see {@link
         * MissileBuilder#flightSoundSpeedPitch}).
         */
        public Builder flightSoundSpeedPitch(double perBlockPerTick) {
            this.flightSoundSpeedPitch = perBlockPerTick;
            return this;
        }

        /** How this missile responds to incoming damage, by {@code MissileDamageRegistry} id: e.g. */
        public Builder damageResponse(ResourceLocation responseId) {
            this.damageResponseId = responseId;
            return this;
        }

        /** What this missile does when shot out of the sky (see {@link MissileEntity.DownedAction}). */
        public Builder downedAction(MissileEntity.DownedAction action) {
            this.downedAction = (action != null) ? action : MissileEntity.DownedAction.CRASH;
            this.downedActionPicker = null; // a fixed action clears any previously-set picker
            return this;
        }

        /** Chance a downed missile lands as a dud instead of going off; 0 disables. */
        public Builder dudChance(double chance) {
            this.dudChance = chance;
            return this;
        }

        /** Pick the shot-down behaviour per launch (see {@link DownedActionPicker}): e.g. */
        public Builder downedAction(DownedActionPicker picker) {
            this.downedActionPicker = picker;
            return this;
        }

        /** See {@link MissileBuilder#seekerMode}. */
        public Builder seekerMode(SeekerMode mode, float fov, double radarRange) {
            this.seekerMode = mode;
            this.seekerFov = fov;
            this.radarRange = radarRange;
            return this;
        }

        /** See {@link MissileBuilder#proximity}. */
        public Builder proximity(float radius) {
            this.proximity = radius;
            return this;
        }

        /** See {@link MissileBuilder#impactDamage}. */
        public Builder impactDamage(float damage) {
            this.impactDamage = damage;
            return this;
        }

        /** See {@link MissileBuilder#threat}. */
        public Builder threat(ThreatKind kind, float caliber, float penetration) {
            this.threatKind = kind;
            this.caliber = caliber;
            this.penetration = penetration;
            return this;
        }

        /** See {@link MissileBuilder#blast}. */
        public Builder blast(float size, boolean breaksBlocks) {
            this.blastSize = size;
            this.breaksBlocks = breaksBlocks;
            return this;
        }

        /** See {@link MissileBuilder#drag}. */
        public Builder drag(double k, boolean glides) {
            this.dragK = k;
            this.glides = glides;
            return this;
        }

        /** See {@link MissileBuilder#gLimit}. */
        public Builder gLimit(double accel, double refSpeed) {
            this.gLimit = accel;
            this.gRefSpeed = refSpeed;
            return this;
        }

        /** TV round (see {@link MissileBuilder#seeker}). */
        public Builder seeker(CameraSpec seeker) {
            this.seeker = seeker;
            return this;
        }

        public MissilePreset build() {
            return new MissilePreset(this);
        }
    }
}
