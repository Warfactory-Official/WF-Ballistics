package com.wf.wfballistics.item;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.flight.FlightStageRegistry;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * An immutable, launch-ready missile configuration — the full {@link MissileEntity.Builder} minus the target,
 * which is supplied at fire time. Registered in {@link MissilePresetRegistry}; each preset becomes a
 * {@link MissileItem} the player can carry and launch.
 *
 * <p>Build one with {@link Builder}; only {@code id} / {@code model} / {@code warhead} are required, the rest
 * default to a plain terrain-following cruise missile.
 * TODO: Add Lombok or something
 */
public final class MissilePreset {

    private final ResourceLocation id;
    private final ResourceLocation modelId;
    private final ResourceLocation warheadId;
    private final boolean highAltitude;
    private final double altitudeParam; // cruiseAltitude (high) or terrainClearance (terrain follow)
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
    private final ResourceLocation cruiseStageId;
    private final ResourceLocation attackStageId;
    private final double attackAngle;
    private final double minDiveAngle;
    private final double maxDiveAngle;
    private final boolean stealth;
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

    private MissilePreset(Builder b) {
        this.id = b.id;
        this.modelId = b.modelId;
        this.warheadId = b.warheadId;
        this.highAltitude = b.highAltitude;
        this.altitudeParam = b.altitudeParam;
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
        this.cruiseStageId = b.cruiseStageId;
        this.attackStageId = b.attackStageId;
        this.attackAngle = b.attackAngle;
        this.minDiveAngle = b.minDiveAngle;
        this.maxDiveAngle = b.maxDiveAngle;
        this.stealth = b.stealth;
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

    public boolean isStealth() {
        return stealth;
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

    /**
     * Builds (but does not spawn) a live missile aimed at {@code target}.
     */
    public MissileEntity build(Level level, Vec3 target) {
        // CEP: scatter the aimpoint within an `accuracy`-block disk (uniform in area) at launch, so an
        // inaccurate missile (e.g. a bunker buster) lands off-target. 0 = pinpoint (aim exactly at target).
        Vec3 aim = target;
        if (accuracy > 0.0) {
            double ang = level.random.nextDouble() * Math.PI * 2.0;
            double rad = accuracy * Math.sqrt(level.random.nextDouble());
            aim = new Vec3(target.x + Math.cos(ang) * rad, target.y, target.z + Math.sin(ang) * rad);
        }
        MissileEntity.Builder b = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
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
        if (stealth) {
            b.stealth(true);
        }
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
        return b.build();
    }

    /**
     * Picks a {@link MissileEntity.DownedAction} at launch time — call e.g. to roll a random / weighted shot-down
     * behaviour per missile. Rolled once in {@link #build(Level, Vec3)} (so each launched missile decides its own
     * fate); the chosen action is then a plain enum on the entity and persists normally.
     *
     * <pre>{@code
     * // 20% power loss, 80% instant detonation, per missile:
     * .downedAction(r -> r.nextFloat() < 0.2f ? DownedAction.POWER_LOSS : DownedAction.DETONATE)
     * // or weighted by integer shares:
     * .downedAction(DownedActionPicker.weighted(Map.of(DownedAction.POWER_LOSS, 20, DownedAction.DETONATE, 80)))
     * }</pre>
     */
    @FunctionalInterface
    public interface DownedActionPicker {
        MissileEntity.DownedAction pick(RandomSource random);

        /**
         * A picker that chooses among {@code weights} (action → integer share) in proportion to their weights.
         * Non-positive weights are ignored; an all-zero/empty map falls back to {@link MissileEntity.DownedAction#CRASH}.
         */
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
        private ResourceLocation cruiseStageId = null; // null = phase default
        private ResourceLocation attackStageId = null;
        private double attackAngle = Double.NaN;
        private double minDiveAngle = MissileEntity.DEFAULT_MIN_DIVE_ANGLE;
        private double maxDiveAngle = MissileEntity.DEFAULT_MAX_DIVE_ANGLE;
        private boolean stealth = false;
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

        private Builder(ResourceLocation id, ResourceLocation modelId, ResourceLocation warheadId) {
            this.id = id;
            this.modelId = MissileModels.exists(modelId) ? modelId : MissileModels.defaultId();
            this.warheadId = WarheadRegistry.exists(warheadId) ? warheadId : WarheadRegistry.defaultId();
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
         * Ceiling (blocks) on how far out a directional strike joins its attack line (see
         * {@link com.wf.wfballistics.flight.ApproachStage}). The join scales with range up to this cap;
         * default {@link MissileEntity#DEFAULT_APPROACH_JOIN_CAP}.
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
         * loaded terrain (see {@link MissileEntity#DEFAULT_IMPACT_PRELOAD_RADIUS}). Default 4 (a 9x9 area);
         * raise it for a very large warhead, or set 0 to disable and rely on the flight fan alone.
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
         * Make this preset an interceptor with the given kill chance (see {@link MissileEntity.Builder#interceptor}).
         * Best paired with the {@code "interceptor"} warhead and a high {@code cruiseSpeed}/{@code turnRate}.
         */
        public Builder interceptor(float chance) {
            this.interceptor = true;
            this.interceptChance = chance;
            return this;
        }

        /**
         * Load the tank: {@code type} of propellant and {@code ticks} of powered flight (see
         * {@link MissileEntity.Builder#fuel}). Running dry mid-flight makes the missile fall ballistically.
         */
        public Builder fuel(MissileEntity.FuelType type, int ticks) {
            this.fuelType = type;
            this.fuelTicks = ticks;
            return this;
        }

        /**
         * Acceleration / deceleration limits (blocks/tick^2) governing how fast actual speed reaches and sheds
         * the cruise (target) speed.
         */
        public Builder accel(double acceleration, double deceleration) {
            this.acceleration = acceleration;
            this.deceleration = deceleration;
            return this;
        }

        /**
         * Pick the cruise-phase flight stage by id (e.g. {@code FlightStageRegistry.rl("loiter")} for a
         * loitering drone). An id not registered for the cruise phase falls back to the phase default.
         */
        public Builder cruiseStage(ResourceLocation id) {
            this.cruiseStageId = FlightStageRegistry.exists(MissileEntity.Phase.CRUISE, id)
                    ? id : FlightStageRegistry.defaultId(MissileEntity.Phase.CRUISE);
            return this;
        }

        /**
         * Pick the attack-phase flight stage by id (e.g. {@code FlightStageRegistry.rl("dive")} for a
         * near-vertical top-attack). An id not registered for the attack phase falls back to the phase default.
         */
        public Builder attackStage(ResourceLocation id) {
            this.attackStageId = FlightStageRegistry.exists(MissileEntity.Phase.ATTACK, id)
                    ? id : FlightStageRegistry.defaultId(MissileEntity.Phase.ATTACK);
            return this;
        }

        /**
         * Explicit preferred dive angle in degrees below horizontal (90 = straight down), uncapped. Leave unset
         * to auto-pick within {@link #diveAngleRange} (see {@link MissileEntity.Builder#attackAngle}).
         */
        public Builder attackAngle(double degrees) {
            this.attackAngle = degrees;
            return this;
        }

        /**
         * Range (degrees below horizontal) the terminal dive auto-picks from when no explicit
         * {@link #attackAngle} is set (see {@link MissileEntity.Builder#diveAngleRange}). Defaults to 80-90.
         */
        public Builder diveAngleRange(double minDegrees, double maxDegrees) {
            this.minDiveAngle = minDegrees;
            this.maxDiveAngle = maxDegrees;
            return this;
        }

        /**
         * Make this missile stealth: invisible to automatic detection (see {@link MissileEntity#isStealth}).
         */
        public Builder stealth() {
            this.stealth = true;
            return this;
        }

        /**
         * Evasion (0..1): how often this missile escapes an interception (see {@link MissileEntity#getEvasion}).
         */
        public Builder evasion(float evasion) {
            this.evasion = evasion;
            return this;
        }

        /**
         * Evasive maneuvering: makes evasion boosts jink off-course instead of sprinting straight (see
         * {@link MissileEntity.Builder#evasiveManeuver}). Pair with a non-zero {@link #evasion}.
         */
        public Builder evasiveManeuver() {
            this.evasiveManeuver = true;
            return this;
        }

        /**
         * Circular error probable (blocks): the aimpoint is randomly scattered within a disk of this radius at
         * launch, so the missile lands off-target by up to {@code blocks}. 0 (default) = pinpoint. Used for
         * deliberately inaccurate ordnance such as bunker busters.
         */
        public Builder accuracy(double blocks) {
            this.accuracy = Math.max(0.0, blocks);
            return this;
        }

        /**
         * Tint of the exhaust trail (hot RGB 0xRRGGBB) the client-side plume fades from (see
         * {@link MissileEntity.Builder#exhaustColor}). Default {@link MissileEntity#DEFAULT_EXHAUST_COLOR}.
         */
        public Builder exhaustColor(int rgb) {
            this.exhaustColor = rgb;
            return this;
        }

        /**
         * The looping flight sound this missile plays client-side, by registered {@link net.minecraft.sounds.SoundEvent}
         * id (see {@link MissileEntity.Builder#flightSound}). Unset keeps WF-B's default {@code missile_flight} loop.
         */
        public Builder flightSound(ResourceLocation soundId) {
            this.flightSoundId = soundId;
            return this;
        }

        /**
         * Distance (blocks) at which this missile's flight loop fades to silence and the server broadcasts it —
         * independent of view/render distance (see {@link MissileEntity.Builder#flightSoundRange}). Default
         * {@link MissileEntity#DEFAULT_FLIGHT_SOUND_RANGE}.
         */
        public Builder flightSoundRange(double blocks) {
            this.flightSoundRange = blocks;
            return this;
        }

        /** Idle engine pitch of the flight loop (see {@link MissileEntity.Builder#flightSoundBasePitch}). */
        public Builder flightSoundBasePitch(float pitch) {
            this.flightSoundBasePitch = pitch;
            return this;
        }

        /**
         * Engine "rev": added flight-loop pitch per block/tick of the missile's own speed (see
         * {@link MissileEntity.Builder#flightSoundSpeedPitch}). Default 0 = constant pitch (the drone exception).
         */
        public Builder flightSoundSpeedPitch(double perBlockPerTick) {
            this.flightSoundSpeedPitch = perBlockPerTick;
            return this;
        }

        /**
         * How this missile responds to incoming damage, by {@code MissileDamageRegistry} id — e.g.
         * {@code explosion_only} to resist everything but blasts (see {@link MissileEntity.Builder#damageResponse}).
         * Unset takes damage as dealt.
         */
        public Builder damageResponse(ResourceLocation responseId) {
            this.damageResponseId = responseId;
            return this;
        }

        /**
         * What this missile does when shot out of the sky (see {@link MissileEntity.DownedAction}). Default
         * {@link MissileEntity.DownedAction#CRASH}.
         */
        public Builder downedAction(MissileEntity.DownedAction action) {
            this.downedAction = (action != null) ? action : MissileEntity.DownedAction.CRASH;
            this.downedActionPicker = null; // a fixed action clears any previously-set picker
            return this;
        }

        /**
         * Pick the shot-down behaviour per launch (see {@link DownedActionPicker}) — e.g. a weighted random roll.
         * Overrides any fixed {@link #downedAction(MissileEntity.DownedAction)}; {@code null} clears it.
         */
        public Builder downedAction(DownedActionPicker picker) {
            this.downedActionPicker = picker;
            return this;
        }

        public MissilePreset build() {
            return new MissilePreset(this);
        }
    }
}
