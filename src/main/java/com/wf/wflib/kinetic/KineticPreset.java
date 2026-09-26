package com.wf.wflib.kinetic;

import com.wf.wflib.MissileModels;
import com.wf.wflib.api.ThreatKind;
import org.jetbrains.annotations.Nullable;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;

/** Everything a kinetic round is: how it flies, what it does to what it hits, and what it looks like. */
public final class KineticPreset {

    /** ywzj_vehicle's {@code PhysicsEngine.G}: the gravity its ballistic solver assumes a round falls under. */
    public static final float DEFAULT_GRAVITY = 9.8f / 400f;
    /** The drag a vehicle cannon's own rounds fly with, so a shell handles like the gun's stock ammunition. */
    public static final float DEFAULT_DRAG = 0.01f;

    private final ResourceLocation id;
    private final ResourceLocation modelId;
    private final ResourceLocation warheadId;

    private final float muzzleSpeed;
    private final float drag;
    private final float gravity;
    private final int lifeTicks;
    private final float waterDrag;
    private final float waterGravityFactor;
    private final float dispersion;

    private final float mass;
    private final float impactDamage;
    private final int penetration;
    private final float penetrationResistance;
    private final float caliberMm;
    private final float armorPenetrationMm;

    private final int fragmentCount;
    private final double airburstHeight;
    private final double proximityRadius;
    private final float blastHalfAngleDeg;
    private final boolean detonateOnEntity;
    private final float blastSize;
    private final boolean breaksBlocks;

    @Nullable
    private final ThreatKind threatKind;
    private final boolean loadsChunks;
    private final int tracerColor;
    private final int stackSize;

    private final float motorAccel;
    private final int burnTicks;
    private final float quadraticDrag;
    private final float durability;
    private final float headshot;
    private final int fuseDelay;
    private final float burrow;
    private final boolean entityContact;

    private KineticPreset(Builder b) {
        this.id = b.id;
        this.modelId = b.modelId;
        this.warheadId = b.warheadId;
        this.muzzleSpeed = b.muzzleSpeed;
        this.drag = b.drag;
        this.gravity = b.gravity;
        this.lifeTicks = b.lifeTicks;
        this.waterDrag = b.waterDrag;
        this.waterGravityFactor = b.waterGravityFactor;
        this.dispersion = b.dispersion;
        this.mass = b.mass;
        this.impactDamage = b.impactDamage;
        this.penetration = b.penetration;
        this.penetrationResistance = b.penetrationResistance;
        this.caliberMm = b.caliberMm;
        this.armorPenetrationMm = b.armorPenetrationMm;
        this.fragmentCount = b.fragmentCount;
        this.airburstHeight = b.airburstHeight;
        this.proximityRadius = b.proximityRadius;
        this.blastHalfAngleDeg = b.blastHalfAngleDeg;
        this.detonateOnEntity = b.detonateOnEntity;
        this.blastSize = b.blastSize;
        this.breaksBlocks = b.breaksBlocks;
        this.threatKind = b.threatKind;
        this.loadsChunks = b.loadsChunks;
        this.tracerColor = b.tracerColor;
        this.stackSize = b.stackSize;
        this.motorAccel = b.motorAccel;
        this.burnTicks = b.burnTicks;
        this.quadraticDrag = b.quadraticDrag;
        this.durability = b.durability;
        this.headshot = b.headshot;
        this.fuseDelay = b.fuseDelay;
        this.burrow = b.burrow;
        this.entityContact = b.entityContact;
    }

    public static Builder builder(ResourceLocation id, ResourceLocation modelId, ResourceLocation warheadId) {
        return new Builder(id, modelId, warheadId);
    }

    public ResourceLocation id() {
        return id;
    }

    /** A {@link MissileModels} id: the mesh drawn in flight and in the hand; null = tracer only (no item art). */
    @Nullable
    public ResourceLocation modelId() {
        return modelId;
    }

    /** The {@link WarheadRegistry} entry fired when the shell goes off. */
    public ResourceLocation warheadId() {
        return warheadId;
    }

    /** Blocks per tick as the shell leaves the barrel. */
    public float muzzleSpeed() {
        return muzzleSpeed;
    }

    /** Fraction of its speed the shell loses per tick. */
    public float drag() {
        return drag;
    }

    /**
     * Fraction of its speed the shell <em>keeps</em> per tick, rounded through a float exactly the way a solver
     * predicting this round does.
     */
    public double decay() {
        return (double) (1.0f - Math.max(drag, 0.0f));
    }

    /** Blocks per tick squared. */
    public float gravity() {
        return gravity;
    }

    /** Ticks before an unspent shell deletes itself. */
    public int lifeTicks() {
        return lifeTicks;
    }

    /** Fraction of its speed the shell loses per tick underwater. */
    public float waterDrag() {
        return waterDrag;
    }

    /** What underwater gravity is multiplied by (buoyancy, roughly). */
    public float waterGravityFactor() {
        return waterGravityFactor;
    }

    /** Extra spread this round adds to the gun's own, in degrees. */
    public float dispersion() {
        return dispersion;
    }

    /** Shell mass in kilograms: what a direct hit is worth when no flat damage is set. */
    public float mass() {
        return mass;
    }

    /** Flat damage for a direct hit, or 0 to derive it from {@link #mass()} and impact speed. */
    public float impactDamage() {
        return impactDamage;
    }

    /** Blocks of cover the shell drills through before the warhead goes off. 0 detonates on the surface. */
    public int penetration() {
        return penetration;
    }

    /** The hardest thing the shell can drill: blast resistance above this stops it dead. */
    public float penetrationResistance() {
        return penetrationResistance;
    }

    public float caliberMm() {
        return caliberMm;
    }

    /** RHA defeated at normal incidence by a direct hit on vehicle armour; 0 = none. */
    public float armorPenetrationMm() {
        return armorPenetrationMm;
    }

    /** Fragments thrown by a fragmentation warhead. */
    public int fragmentCount() {
        return fragmentCount;
    }

    /** Height above the ground to burst at, or 0 for a contact fuse. */
    public double airburstHeight() {
        return airburstHeight;
    }

    /** Distance at which a passing entity trips the fuse, or 0 for contact only. */
    public double proximityRadius() {
        return proximityRadius;
    }

    /** Half-angle of a directional warhead's cone, in degrees. */
    public float blastHalfAngleDeg() {
        return blastHalfAngleDeg;
    }

    /** Whether hitting an entity sets the warhead off, or the shell just punches through. */
    public boolean detonateOnEntity() {
        return detonateOnEntity;
    }

    /** Warhead blast size override (vanilla explosion power); {@code <= 0} = the warhead's own. */
    public float blastSize() {
        return blastSize;
    }

    /** False = the blast hurts entities only. */
    public boolean breaksBlocks() {
        return breaksBlocks;
    }

    /** Armour-model kind; null = derived from warhead and penetration. */
    @Nullable
    public ThreatKind threatKind() {
        return threatKind;
    }

    /** Force-loads the chunk it descends into (artillery); false => lost at unloaded ground (bullets). */
    public boolean loadsChunks() {
        return loadsChunks;
    }

    /** 0xRRGGBB streak; 0 = none. */
    public int tracerColor() {
        return tracerColor;
    }

    public int stackSize() {
        return stackSize;
    }

    /** RocketEntity only: blocks/tick^2 along the nose while burning. */
    public float motorAccel() {
        return motorAccel;
    }

    /** RocketEntity only. */
    public int burnTicks() {
        return burnTicks;
    }

    /** {@code v -= k|v|v} per tick, on top of {@link #drag}. */
    public float quadraticDrag() {
        return quadraticDrag;
    }

    /** Per-tick velocity factor in air at {@code speed}: {@link #decay()} when there is no quadratic drag. */
    public double decay(double speed) {
        return quadraticDrag == 0.0f ? decay() : Math.max(decay() - quadraticDrag * speed, 0.0);
    }

    /** Direct-hit damage factor on a living target struck within 0.25 of its eye height. */
    public float headshot() {
        return headshot;
    }

    /** Block contact => rest this many ticks, then detonate; 0 = on contact. */
    public int fuseDelay() {
        return fuseDelay;
    }

    /** Resting: dig straight down, each block costing its destroy speed (min 0.1) from this budget, before the fuse runs. */
    public float burrow() {
        return burrow;
    }

    /** False = passes entities untouched (bombs). */
    public boolean entityContact() {
        return entityContact;
    }

    /** RocketEntity only: health against air defence. */
    public float durability() {
        return durability;
    }

    public static final class Builder {

        private final ResourceLocation id;
        private final ResourceLocation modelId;
        private final ResourceLocation warheadId;

        private float muzzleSpeed = 6.0f;
        private float drag = DEFAULT_DRAG;
        private float gravity = DEFAULT_GRAVITY;
        private int lifeTicks = 2400;
        private float waterDrag = 0.4f;
        private float waterGravityFactor = 0.6f;
        private float dispersion = 0.0f;

        private float mass = 20.0f;
        private float impactDamage = 0.0f;
        private int penetration = 0;
        private float penetrationResistance = 30.0f;
        private float caliberMm = 120.0f;
        private float armorPenetrationMm = 0.0f;

        private int fragmentCount = 0;
        private double airburstHeight = 0.0;
        private double proximityRadius = 0.0;
        private float blastHalfAngleDeg = 22.5f;
        private boolean detonateOnEntity = true;
        private float blastSize = 0.0f;
        private boolean breaksBlocks = true;

        private ThreatKind threatKind;
        private boolean loadsChunks = true;
        private int tracerColor = 0;
        private int stackSize = 16;

        private float motorAccel = 0.0f;
        private int burnTicks = 0;
        private float quadraticDrag = 0.0f;
        private float durability = 8.0f;
        private float headshot = 1.0f;
        private int fuseDelay = 0;
        private float burrow = 0.0f;
        private boolean entityContact = true;

        private Builder(ResourceLocation id, ResourceLocation modelId, ResourceLocation warheadId) {
            this.id = id;
            this.modelId = modelId;
            this.warheadId = warheadId;
        }

        /** Muzzle velocity in blocks per tick. A field gun is around 6, a mortar 2, a tank gun 20. */
        public Builder speed(double blocksPerTick) {
            this.muzzleSpeed = (float) blocksPerTick;
            return this;
        }

        public Builder drag(double fractionPerTick) {
            this.drag = (float) fractionPerTick;
            return this;
        }

        public Builder gravity(double blocksPerTickSquared) {
            this.gravity = (float) blocksPerTickSquared;
            return this;
        }

        public Builder life(int ticks) {
            this.lifeTicks = ticks;
            return this;
        }

        public Builder water(double drag, double gravityFactor) {
            this.waterDrag = (float) drag;
            this.waterGravityFactor = (float) gravityFactor;
            return this;
        }

        public Builder dispersion(double degrees) {
            this.dispersion = (float) degrees;
            return this;
        }

        /** Shell mass in kg, which is what a direct hit is worth unless {@link #impactDamage} overrides it. */
        public Builder mass(double kilograms) {
            this.mass = (float) kilograms;
            return this;
        }

        public Builder impactDamage(double damage) {
            this.impactDamage = (float) damage;
            return this;
        }

        /** Drill through up to {@code blocks} of cover no harder than {@code resistance} before going off. */
        public Builder penetration(int blocks, double resistance) {
            this.penetration = blocks;
            this.penetrationResistance = (float) resistance;
            return this;
        }

        public Builder caliber(double mm) {
            this.caliberMm = (float) mm;
            return this;
        }

        public Builder armorPenetration(double mm) {
            this.armorPenetrationMm = (float) mm;
            return this;
        }

        public Builder fragments(int count) {
            this.fragmentCount = count;
            return this;
        }

        /** Burst this far above the ground instead of on it. */
        public Builder airburst(double height) {
            this.airburstHeight = height;
            return this;
        }

        /** Burst when an entity comes within this distance. */
        public Builder proximity(double radius) {
            this.proximityRadius = radius;
            return this;
        }

        public Builder blastHalfAngle(double degrees) {
            this.blastHalfAngleDeg = (float) degrees;
            return this;
        }

        /** An inert or delayed round that punches through a body rather than going off on it. */
        public Builder passesThroughEntities() {
            this.detonateOnEntity = false;
            return this;
        }

        /** Lost at unloaded ground instead of loading it. */
        public Builder noChunkLoading() {
            this.loadsChunks = false;
            return this;
        }

        public Builder threat(ThreatKind kind) {
            this.threatKind = kind;
            return this;
        }

        public Builder tracer(int rgb) {
            this.tracerColor = rgb;
            return this;
        }

        public Builder stackSize(int size) {
            this.stackSize = size;
            return this;
        }

        public Builder blast(double size, boolean breaksBlocks) {
            this.blastSize = (float) size;
            this.breaksBlocks = breaksBlocks;
            return this;
        }

        public Builder motor(double accel, int ticks) {
            this.motorAccel = (float) accel;
            this.burnTicks = ticks;
            return this;
        }

        public Builder quadraticDrag(double k) {
            this.quadraticDrag = (float) k;
            return this;
        }

        public Builder headshot(double multiplier) {
            this.headshot = (float) multiplier;
            return this;
        }

        public Builder fuseDelay(int ticks) {
            this.fuseDelay = ticks;
            return this;
        }

        public Builder burrow(double hardness) {
            this.burrow = (float) hardness;
            return this;
        }

        public Builder noEntityContact() {
            this.entityContact = false;
            return this;
        }

        public Builder durability(double health) {
            this.durability = (float) health;
            return this;
        }

        public KineticPreset build() {
            return new KineticPreset(this);
        }
    }
}
