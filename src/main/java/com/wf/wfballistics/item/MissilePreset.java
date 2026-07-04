package com.wf.wfballistics.item;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.flight.FlightStageRegistry;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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

    private final String id;
    private final String modelId;
    private final ResourceLocation warheadId;
    private final boolean highAltitude;
    private final double altitudeParam; // cruiseAltitude (high) or terrainClearance (terrain follow)
    private final double cruiseSpeed;
    private final double turnRate;       // <= 0 = model-size default
    private final float health;
    private final int fragmentCount;
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
    private final boolean stealth;
    private final float evasion;
    private final boolean evasiveManeuver;
    private final int exhaustColor;

    private MissilePreset(Builder b) {
        this.id = b.id;
        this.modelId = b.modelId;
        this.warheadId = b.warheadId;
        this.highAltitude = b.highAltitude;
        this.altitudeParam = b.altitudeParam;
        this.cruiseSpeed = b.cruiseSpeed;
        this.turnRate = b.turnRate;
        this.health = b.health;
        this.fragmentCount = b.fragmentCount;
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
        this.stealth = b.stealth;
        this.evasion = b.evasion;
        this.evasiveManeuver = b.evasiveManeuver;
        this.exhaustColor = b.exhaustColor;
    }

    public static Builder builder(String id, String modelId, String warheadId) {
        return new Builder(id, modelId, warheadId);
    }

    public String id() {
        return id;
    }

    public String modelId() {
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

    public float health() {
        return health;
    }

    public int fragmentCount() {
        return fragmentCount;
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

    public boolean isStealth() {
        return stealth;
    }

    public float evasion() {
        return evasion;
    }

    public boolean isEvasiveManeuver() {
        return evasiveManeuver;
    }

    public int exhaustColor() {
        return exhaustColor;
    }

    /**
     * Builds (but does not spawn) a live missile aimed at {@code target}.
     */
    public MissileEntity build(Level level, Vec3 target) {
        MissileEntity.Builder b = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .model(modelId)
                .detonation(warheadId)
                .target(target)
                .cruiseSpeed(cruiseSpeed)
                .health(health)
                .fragmentCount(fragmentCount)
                .explosionOffset(explosionOffset);
        if (highAltitude) {
            b.highAltitude(altitudeParam);
        } else {
            b.terrainFollow(altitudeParam);
        }
        if (turnRate > 0.0) {
            b.turnRate(turnRate);
        }
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
        return b.build();
    }

    public static final class Builder {
        private final String id;
        private final String modelId;
        private final ResourceLocation warheadId;
        private boolean highAltitude = false;
        private double altitudeParam = 24.0;
        private double cruiseSpeed = MissileEntity.CRUISE_SPEED;
        private double turnRate = 0.0;
        private float health = MissileEntity.DEFAULT_HEALTH;
        private int fragmentCount = MissileEntity.DEFAULT_FRAGMENT_COUNT;
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
        private boolean stealth = false;
        private float evasion = 0.0f;
        private boolean evasiveManeuver = false;
        private int exhaustColor = MissileEntity.DEFAULT_EXHAUST_COLOR;

        private Builder(String id, String modelId, String warheadId) {
            this.id = id;
            this.modelId = MissileModels.exists(modelId) ? modelId : MissileModels.DEFAULT;
            ResourceLocation warhead = WarheadRegistry.parse(warheadId);
            this.warheadId = WarheadRegistry.exists(warhead) ? warhead : WarheadRegistry.defaultId();
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

        public Builder health(float health) {
            this.health = health;
            return this;
        }

        public Builder fragmentCount(int fragmentCount) {
            this.fragmentCount = fragmentCount;
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
         * Pick the cruise-phase flight stage by id (e.g. {@code "loiter"} for a loitering drone).
         */
        public Builder cruiseStage(String id) {
            this.cruiseStageId = FlightStageRegistry.parse(MissileEntity.Phase.CRUISE, id);
            return this;
        }

        /**
         * Pick the attack-phase flight stage by id (e.g. {@code "dive"} for a near-vertical top-attack).
         */
        public Builder attackStage(String id) {
            this.attackStageId = FlightStageRegistry.parse(MissileEntity.Phase.ATTACK, id);
            return this;
        }

        /**
         * Desired terminal impact angle in degrees below horizontal (90 = straight down). Leave unset for best
         * fit, the attack stage's natural dive (see {@link MissileEntity.Builder#attackAngle}).
         */
        public Builder attackAngle(double degrees) {
            this.attackAngle = degrees;
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
         * Tint of the exhaust trail (hot RGB 0xRRGGBB) the client-side plume fades from (see
         * {@link MissileEntity.Builder#exhaustColor}). Default {@link MissileEntity#DEFAULT_EXHAUST_COLOR}.
         */
        public Builder exhaustColor(int rgb) {
            this.exhaustColor = rgb;
            return this;
        }

        public MissilePreset build() {
            return new MissilePreset(this);
        }
    }
}
