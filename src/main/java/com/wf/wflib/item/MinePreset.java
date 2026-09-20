package com.wf.wflib.item;

import com.wf.wflib.ModEntities;
import com.wf.wflib.mine.DefuseMethod;
import com.wf.wflib.mine.MineEntity;
import com.wf.wflib.mine.MineModels;
import com.wf.wflib.mine.MineTriggers;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * An immutable, deploy-ready mine configuration: the full {@link MineEntity.Builder} minus the position and facing,
 * which are supplied at deploy time.
 */
public final class MinePreset {

    private final ResourceLocation id;
    private final ResourceLocation modelId;
    private final ResourceLocation warheadId;
    private final ResourceLocation triggerId;
    private final Double width;
    private final Double height;
    private final double triggerRange;
    private final Double sneakTriggerRange;
    private final double arc;
    private final int scanInterval;
    private final int armDelay;
    private final boolean requiresActivation;
    private final DefuseMethod defuseMethod;
    private final TagKey<Item> defuseTool;
    private final int canisterCount;
    private final ResourceLocation canisterMineId;
    private final int defuseTicks;
    private final float defuseFailChance;
    private final boolean floats;
    private final double draught;
    private final int selfDestructTicks;
    private final boolean armsOnlyInWater;
    private final boolean sownOnly;
    private final boolean buriable;
    private final double bounceHeight;
    private final float tumble;
    private final float restTilt;
    private final int fragmentCount;
    private final Vec3 blastDirection;
    private final boolean blastAlongFacing;
    private final float blastHalfAngle;

    private MinePreset(Builder b) {
        this.id = b.id;
        this.modelId = b.modelId;
        this.warheadId = b.warheadId;
        this.triggerId = b.triggerId;
        this.width = b.width;
        this.height = b.height;
        this.triggerRange = b.triggerRange;
        this.sneakTriggerRange = b.sneakTriggerRange;
        this.arc = b.arc;
        this.scanInterval = b.scanInterval;
        this.armDelay = b.armDelay;
        this.requiresActivation = b.requiresActivation;
        this.defuseMethod = b.defuseMethod;
        this.defuseTool = b.defuseTool;
        this.canisterCount = b.canisterCount;
        this.canisterMineId = b.canisterMineId;
        this.defuseTicks = b.defuseTicks;
        this.defuseFailChance = b.defuseFailChance;
        this.floats = b.floats;
        this.draught = b.draught;
        this.selfDestructTicks = b.selfDestructTicks;
        this.armsOnlyInWater = b.armsOnlyInWater;
        this.sownOnly = b.sownOnly;
        this.buriable = b.buriable;
        this.bounceHeight = b.bounceHeight;
        this.tumble = b.tumble;
        this.restTilt = b.restTilt;
        this.fragmentCount = b.fragmentCount;
        this.blastDirection = b.blastDirection;
        this.blastAlongFacing = b.blastAlongFacing;
        this.blastHalfAngle = b.blastHalfAngle;
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

    public ResourceLocation triggerId() {
        return triggerId;
    }

    public double triggerRange() {
        return triggerRange;
    }

    /** The range a crouching approach is seen at; {@link MineEntity#DEFAULT_SNEAK_FACTOR} of the above by default. */
    public double sneakTriggerRange() {
        return sneakTriggerRange != null
                ? Math.min(sneakTriggerRange, triggerRange)
                : triggerRange * MineEntity.DEFAULT_SNEAK_FACTOR;
    }

    public double arc() {
        return arc;
    }

    public int armDelay() {
        return armDelay;
    }

    public boolean requiresActivation() {
        return requiresActivation;
    }

    /** How many canisters a rack carries, or 0 for anything that is not one. */
    public int canisterCount() {
        return canisterCount;
    }

    /** What this rack's canisters hold, and therefore what reloads it. Null for anything that is not one. */
    @Nullable
    public ResourceLocation canisterMineId() {
        return canisterMineId;
    }

    public DefuseMethod defuseMethod() {
        return defuseMethod;
    }

    /**
     * The item tag a {@link DefuseMethod#TOOL} defusal needs in hand, or null for a mine that names none (which
     * {@link MineEntity#tryDefuse} treats as {@link DefuseMethod#HAND}).
     */
    @Nullable
    public TagKey<Item> defuseTool() {
        return defuseTool;
    }

    public int defuseTicks() {
        return defuseTicks;
    }

    public float defuseFailChance() {
        return defuseFailChance;
    }

    public boolean floats() {
        return floats;
    }

    /** @see MineEntity.Builder#floats(double) */
    public double draught() {
        return draught;
    }

    /** @see MineEntity.Builder#buriable */
    public boolean buriable() {
        return buriable;
    }

    /** @see MineEntity.Builder#selfDestructs(int) */
    public int selfDestructTicks() {
        return selfDestructTicks;
    }

    /** @see MineEntity.Builder#armsOnlyInWater() */
    public boolean armsOnlyInWater() {
        return armsOnlyInWater;
    }

    /**
     * @return true if this mine is built to be dispensed from the air rather than laid by hand.
     */
    public boolean sownOnly() {
        return sownOnly;
    }

    public double bounceHeight() {
        return bounceHeight;
    }

    /** Degrees per tick a mine of this preset turns over at in the air; 0 for one that never tumbles. */
    public float tumble() {
        return tumble;
    }

    public float restTilt() {
        return restTilt;
    }

    /** @return true if this preset throws the body clear before the warhead fires. */
    public boolean bounds() {
        return bounceHeight > 0.0;
    }

    /**
     * Builds (but does not spawn) a mine facing {@code yaw}.
     */
    public MineEntity build(Level level, float yaw) {
        MineEntity.Builder b = MineEntity.builder(ModEntities.MINE.get(), level)
                .model(modelId)
                .detonation(warheadId)
                .trigger(triggerId)
                .triggerRange(triggerRange)
                .arc(arc)
                .scanInterval(scanInterval)
                .armDelay(armDelay)
                .bounceHeight(bounceHeight)
                .tumble(tumble)
                .restTilt(restTilt)
                .fragmentCount(fragmentCount)
                .defusable(defuseMethod, defuseTool)
                .canisters(canisterCount, canisterMineId)
                .defuseTicks(defuseTicks)
                .defuseFailChance(defuseFailChance)
                .blastHalfAngle(blastHalfAngle)
                .preset(id)
                .facing(yaw);
        if (width != null && height != null) {
            b.size(width, height);
        }
        if (sneakTriggerRange != null) {
            b.sneakTriggerRange(sneakTriggerRange);
        }
        if (requiresActivation) {
            b.requiresActivation();
        }
        if (floats) {
            b.floats(draught);
        }
        if (selfDestructTicks > 0) {
            b.selfDestructs(selfDestructTicks);
        }
        if (armsOnlyInWater) {
            b.armsOnlyInWater();
        }
        if (buriable) {
            b.buriable();
        }
        if (blastDirection != null) {
            b.blastDirection(blastDirection);
        }
        if (blastAlongFacing) {
            b.blastAlongFacing();
        }
        return b.build();
    }

    public static final class Builder {

        private final ResourceLocation id;
        private final ResourceLocation modelId;
        private final ResourceLocation warheadId;
        private ResourceLocation triggerId = MineTriggers.defaultId();
        private Double width = null;
        private Double height = null;
        private double triggerRange = MineEntity.DEFAULT_TRIGGER_RANGE;
        private Double sneakTriggerRange = null;
        private double arc = MineEntity.DEFAULT_ARC;
        private int scanInterval = MineEntity.DEFAULT_SCAN_INTERVAL;
        private int armDelay = MineEntity.DEFAULT_ARM_DELAY;
        private boolean requiresActivation = false;
        private DefuseMethod defuseMethod = DefuseMethod.HAND;
        private TagKey<Item> defuseTool = null;
        private int canisterCount = 0;
        private ResourceLocation canisterMineId = null;
        private int defuseTicks = MineEntity.DEFAULT_DEFUSE_TICKS;
        private float defuseFailChance = 0.0f;
        private boolean floats = false;
        private double draught = 0.0;
        private int selfDestructTicks = 0;
        private boolean armsOnlyInWater = false;
        private boolean sownOnly = false;
        private boolean buriable = false;
        private double bounceHeight = 0.0;
        private float tumble = 0.0f;
        private float restTilt = MineEntity.DEFAULT_REST_TILT;
        private int fragmentCount = MineEntity.DEFAULT_FRAGMENT_COUNT;
        private Vec3 blastDirection = null;
        private boolean blastAlongFacing = false;
        private float blastHalfAngle = MineEntity.DEFAULT_BLAST_HALF_ANGLE;

        private Builder(ResourceLocation id, ResourceLocation modelId, ResourceLocation warheadId) {
            this.id = id;
            this.modelId = MineModels.exists(modelId) ? modelId : MineModels.defaultId();
            this.warheadId = WarheadRegistry.exists(warheadId) ? warheadId : WarheadRegistry.defaultId();
        }

        /** Pick the whole trigger condition by registered id (see {@link MineTriggers}). */
        public Builder trigger(ResourceLocation triggerId) {
            this.triggerId = MineTriggers.exists(triggerId) ? triggerId : MineTriggers.defaultId();
            return this;
        }

        /** Body size in blocks, overriding the model's own. */
        public Builder size(double width, double height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder triggerRange(double blocks) {
            this.triggerRange = blocks;
            return this;
        }

        /** @see MineEntity.Builder#sneakTriggerRange */
        public Builder sneakTriggerRange(double blocks) {
            this.sneakTriggerRange = blocks;
            return this;
        }

        /** Total arc in degrees a {@code directional} fuse watches, centred on the mine's facing. */
        /** Makes this preset a rack: {@code count} canisters of {@code mineId}. */
        public Builder canisters(int count, ResourceLocation mineId) {
            this.canisterCount = Math.max(0, count);
            this.canisterMineId = mineId;
            return this;
        }

        public Builder arc(double degrees) {
            this.arc = degrees;
            return this;
        }

        /** @see MineEntity.Builder#scanInterval */
        public Builder scanInterval(int ticks) {
            this.scanInterval = Math.max(1, ticks);
            return this;
        }

        public Builder armDelay(int ticks) {
            this.armDelay = Math.max(0, ticks);
            return this;
        }

        /** @see MineEntity.Builder#requiresActivation */
        public Builder requiresActivation() {
            this.requiresActivation = true;
            return this;
        }

        public Builder defusable(DefuseMethod method) {
            this.defuseMethod = method == null ? DefuseMethod.NONE : method;
            return this;
        }

        public Builder defusable(DefuseMethod method, @Nullable TagKey<Item> tool) {
            this.defuseMethod = method == null ? DefuseMethod.NONE : method;
            this.defuseTool = tool;
            return this;
        }

        public Builder undefusable() {
            this.defuseMethod = DefuseMethod.NONE;
            return this;
        }

        public Builder defuseTicks(int ticks) {
            this.defuseTicks = Math.max(1, ticks);
            return this;
        }

        public Builder defuseFailChance(float chance) {
            this.defuseFailChance = chance;
            return this;
        }

        /** Rides a fluid instead of sinking: a naval mine. */
        public Builder floats() {
            return floats(0.0);
        }

        /** @see MineEntity.Builder#floats(double) */
        public Builder floats(double draught) {
            this.floats = true;
            this.draught = Math.max(0.0, draught);
            return this;
        }

        /** @see MineEntity.Builder#buriable */
        public Builder buriable() {
            this.buriable = true;
            return this;
        }

        /** @see MineEntity.Builder#selfDestructs(int) */
        public Builder selfDestructs(int ticks) {
            this.selfDestructTicks = Math.max(0, ticks);
            return this;
        }

        /** @see MineEntity.Builder#armsOnlyInWater() */
        public Builder armsOnlyInWater() {
            this.armsOnlyInWater = true;
            return this;
        }

        /** Marks this preset as one built to be dispensed from the air. */
        public Builder sownOnly() {
            this.sownOnly = true;
            return this;
        }

        public Builder bounceHeight(double blocks) {
            this.bounceHeight = blocks;
            return this;
        }

        /** @see MineEntity.Builder#tumbles */
        public Builder tumbles() {
            return tumble(MineEntity.DEFAULT_TUMBLE);
        }

        /** @see MineEntity.Builder#tumble */
        public Builder tumble(float degreesPerTick) {
            this.tumble = Math.max(0.0f, degreesPerTick);
            return this;
        }

        /** @see MineEntity.Builder#restTilt */
        public Builder restTilt(float degrees) {
            this.restTilt = degrees;
            return this;
        }

        public Builder fragmentCount(int fragmentCount) {
            this.fragmentCount = fragmentCount;
            return this;
        }

        /** @see MineEntity.Builder#blastDirection */
        public Builder blastDirection(Vec3 direction) {
            this.blastDirection = direction;
            return this;
        }

        /** @see MineEntity.Builder#blastHalfAngle */
        public Builder blastHalfAngle(float degrees) {
            this.blastHalfAngle = degrees;
            return this;
        }

        /** @see MineEntity.Builder#blastAlongFacing */
        public Builder blastAlongFacing() {
            this.blastAlongFacing = true;
            return this;
        }

        public MinePreset build() {
            return new MinePreset(this);
        }
    }
}
