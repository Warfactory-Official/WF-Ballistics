package com.wf.wflib.mine;

import com.wf.wflib.demolition.IDetonatableEntity;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * A mine: ordnance that is deployed rather than fired, lies where it was put, and goes off when something it was
 * fused for comes close enough.
 */
public class MineEntity extends Entity implements WarheadCarrier, IDetonatableEntity {

    public static final double DEFAULT_TRIGGER_RANGE = 4.0;
    /** A crouching approach is only seen this fraction of the way in. */
    public static final double DEFAULT_SNEAK_FACTOR = 0.35;
    /** Full circle: a mine has no front until an arc is set, and {@link MineTrigger#inArc} is a no-op. */
    public static final double DEFAULT_ARC = 360.0;
    public static final int DEFAULT_SCAN_INTERVAL = 4;
    public static final int DEFAULT_ARM_DELAY = 40;
    /** A typical bouncing-Betty hop. Not a default: a mine only hops if given a height. */
    public static final double BOUNDING_HEIGHT = 1.4;
    public static final int DEFAULT_DEFUSE_TICKS = 60;
    public static final int DEFAULT_FRAGMENT_COUNT = 12;
    /** Degrees per tick a tumbling mine spins on each axis once it is off the ground. */
    public static final float DEFAULT_TUMBLE = 16.0f;
    /** How far off flat a mine that has tumbled is allowed to come to rest. */
    public static final float DEFAULT_REST_TILT = 22.0f;
    /** Half-angle of the cone a directional warhead fires into. */
    public static final float DEFAULT_BLAST_HALF_ANGLE = 70.0f;

    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);
    private static final double GRAVITY = 0.04;
    private static final double DRAG = 0.98;
    private static final double BUOYANCY = 0.05;
    private static final double MAX_RISE = 0.25;
    /** What water does to a mine arriving in it at speed, per tick. */
    private static final double WATER_ENTRY_DRAG = 0.6;
    /** Ceiling on how long a hop may last before the warhead fires anyway. */
    private static final int BOUNCE_FUSE = 40;
    private static final double DEFUSE_REACH = 4.0;
    /** Ticks between a mine taking a hit and going off, so a chain ripples instead of recursing. */
    private static final int DAMAGE_FUSE = 1;
    /** How close another mine has to be going off for this one to go with it, in blocks. */
    private static final double SYMPATHY_RANGE = 1.5;
    /**
     * Past this, a position update is a teleport rather than a fall, and walking to it would slide the mine across
     * the world instead of putting it where it now is.
     */
    private static final double LERP_SNAP_DISTANCE = 8.0;
    /** Steps the packet handler asks a position update to be walked over; the roll walk matches it. */
    private static final int LERP_STEPS = 3;
    /** Ticks a mine takes to flop from however it landed down onto its resting tilt. */
    private static final int FLOP_TICKS = 6;
    /** How fast a mine has to be going before it counts as thrown rather than set down. */
    private static final double TUMBLE_SPEED = 0.1;

    /** Odds per tick that a mine which has moved re-reads the ground it is now on. */
    private static final int CAMO_CHECK_CHANCE = 8;
    /** How close to its resting depth a floating mine has to get before it is simply put there. */
    private static final double SETTLE_EPSILON = 0.05;
    /** How far up a floating mine will look for the surface before giving up on finding one. */
    private static final int MAX_FLUID_DEPTH = 64;
    /** How far a dug-in mine has to end up from its hole to count as out of it, squared. */
    private static final double DISLODGED_SQR = 0.04;

    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> WIDTH =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> STATE =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.BYTE);
    /** Roll has no vanilla channel: yaw and pitch ride the tracker's rotation packets, this does not. */
    private static final EntityDataAccessor<Float> ROLL =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.FLOAT);
    /** Which finish it is wearing, see {@link MineCamo}. Synced because only the client draws it. */
    private static final EntityDataAccessor<String> CAMO =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.STRING);
    /** Whether it has been dug in. Changes both what is drawn and what you can walk into. */
    private static final EntityDataAccessor<Boolean> BURIED =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.BOOLEAN);
    /** Whether it is a mine that CAN be dug in, and which preset it was laid from. */
    private static final EntityDataAccessor<Boolean> BURIABLE =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> PRESET =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.STRING);
    /** Whether it is riding a fluid on a mooring rather than sitting on the ground. */
    /** How many canisters a rack still has on it, and therefore how many of its bones to draw. */
    private static final EntityDataAccessor<Byte> CANISTERS =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.BYTE);

    private static final EntityDataAccessor<Byte> CANISTER_CAPACITY =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.BYTE);

    /** What this rack's canisters hold, as an id, or empty for anything that is not a rack. */
    private static final EntityDataAccessor<String> CANISTER_MINE =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Boolean> MOORED =
            SynchedEntityData.defineId(MineEntity.class, EntityDataSerializers.BOOLEAN);

    // --- fuse / payload -------------------------------------------------------------------------
    private ResourceLocation detonationId = WarheadRegistry.defaultId();
    private WarheadRegistry.Detonation detonation = WarheadRegistry.STANDARD;
    private ResourceLocation triggerId = MineTriggers.defaultId();
    private MineTrigger trigger = MineTriggers.LIVING;
    private int fragmentCount = DEFAULT_FRAGMENT_COUNT;
    /** null = straight up, or the facing when {@link #blastAlongFacing}. Never used as given if it points down. */
    @Nullable
    private Vec3 blastDirection = null;
    private boolean blastAlongFacing = false;
    private float blastHalfAngle = DEFAULT_BLAST_HALF_ANGLE;

    // --- sensor ---------------------------------------------------------------------------------
    private double triggerRange = DEFAULT_TRIGGER_RANGE;
    private double sneakTriggerRange = DEFAULT_TRIGGER_RANGE * DEFAULT_SNEAK_FACTOR;
    private double arc = DEFAULT_ARC;
    private int scanInterval = DEFAULT_SCAN_INTERVAL;

    // --- arming / defusing ----------------------------------------------------------------------
    private int armDelay = DEFAULT_ARM_DELAY;
    private boolean requiresActivation = false;
    private DefuseMethod defuseMethod = DefuseMethod.HAND;
    @Nullable
    private TagKey<Item> defuseTool = null;
    private int defuseTicks = DEFAULT_DEFUSE_TICKS;
    private float defuseFailChance = 0.0f;

    // --- body -----------------------------------------------------------------------------------
    private boolean floats = false;
    /** Ticks this mine stays live before destroying itself, or 0 to sit there for ever. */
    private int selfDestructTicks = 0;
    /**
     * Only arms while it is in a fluid: a naval mine that came down on a beach is inert until the tide, rather than
     * being an unusually large land mine.
     */
    private boolean armsOnlyInWater = false;
    private double bounceHeight = 0.0;
    private float tumble = 0.0f;
    private float restTilt = DEFAULT_REST_TILT;

    // --- ownership ------------------------------------------------------------------------------
    @Nullable
    private UUID teamId = null;
    @Nullable
    private UUID ownerId = null;
    @Nullable
    private ResourceLocation presetId = null;
    // --- runtime --------------------------------------------------------------------------------
    private State state = State.ARMING;
    private int armTicks = 0;
    private boolean detonated = false;
    private int fuse = -1;
    private boolean bouncing = false;
    private int bounceTicks = 0;

    /** False forces one settle pass; a loaded mine always takes it, since the ground may have moved. */
    private boolean resting = false;
    /** Whether this mine is one that can be dug in at all; see {@link Builder#buriable}. */
    private boolean buriable = false;
    /** How far below a fluid's surface a floating mine rides; see {@link Builder#floats(double)}. */
    private double draught = 0.0;
    /** Set whenever the mine moves, cleared once it has re-read the ground it landed on. */
    /** How long this mine has been in the world, for {@link #selfDestructTicks}. */
    private int lifeTicks = 0;
    /** Whether the mine is standing in a fluid, as of the last time it moved. */
    private boolean wet = false;
    private boolean camoStale = true;
    /** Where it was dug in, or null if it is not. Server-side only; a reload re-derives it on resting. */
    @Nullable
    private Vec3 buriedAt = null;
    /** Where it came to rest, so being moved off it counts as a disturbance too. */
    private double restX;
    private double restY;
    private double restZ;
    private boolean watching = false;
    private long ownSection;
    private long supportSection;
    private int ownVersion;
    private int supportVersion;

    @Nullable
    private UUID defuserId = null;
    private int defuseProgress = 0;

    // --- attitude -------------------------------------------------------------------------------
    /** Roll in degrees. Yaw and pitch are the entity's own; there is no vanilla field for the third. */
    private float roll;
    private float rollO;
    private float spinYaw;
    private float spinPitch;
    private float spinRoll;
    private int flopTicks;
    private float flopPitch;
    private float flopRoll;
    /** Whether this mine has turned over since it was last at rest, and so has an attitude to settle. */
    private boolean tumbled;

    // --- client interpolation -------------------------------------------------------------------
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private float lerpYRot;
    private float lerpXRot;
    private int lerpSteps;
    private float lerpRoll;
    private int lerpRollSteps;

    public MineEntity(EntityType<? extends MineEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = false;
    }

    public static Builder builder(EntityType<? extends MineEntity> type, Level level) {
        return new Builder(type, level);
    }

    @Override
    public int getFragmentCount() {
        return this.fragmentCount;
    }

    @Override
    @Nullable
    public UUID igniterFactionId() {
        return this.teamId;
    }

    /** The jet axis a directional warhead fires along. */
    @Override
    public Vec3 angle() {
        Vec3 axis = this.blastDirection;
        if (axis == null && this.blastAlongFacing) {
            axis = bodyForward();
        }
        if (axis == null || axis.lengthSqr() < 1.0E-8) {
            return UP;
        }
        Vec3 unit = axis.normalize();
        return unit.y < 0.0 ? new Vec3(unit.x, -unit.y, unit.z).normalize() : unit;
    }

    @Override
    public float blastHalfAngleDeg() {
        return this.blastHalfAngle;
    }

    /** The horizontal direction this mine faces, which is the bearing a directional fuse watches. */
    public Vec3 facing() {
        double yaw = Math.toRadians(this.getYRot());
        return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    /**
     * The direction the body points, tilt included, which is what a charge aimed along the facing fires along: one
     * that came down on a slant sprays where it is actually pointing.
     */
    public Vec3 bodyForward() {
        float degrees = this.getXRot();
        if (degrees == 0.0f) {
            return facing();
        }
        double yaw = Math.toRadians(this.getYRot());
        double pitch = Math.toRadians(degrees);
        double flat = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * flat, -Math.sin(pitch), Math.cos(yaw) * flat);
    }

    public ResourceLocation getModelId() {
        return MineModels.parse(this.entityData.get(MODEL_ID));
    }

    public double getTriggerRange() {
        return this.triggerRange;
    }

    public double getSneakTriggerRange() {
        return this.sneakTriggerRange;
    }

    public double getArc() {
        return this.arc;
    }

    public double getBounceHeight() {
        return this.bounceHeight;
    }

    /** Degrees per tick this mine turns over at while it is in the air; 0 for one that never tumbles. */
    public float getTumble() {
        return this.tumble;
    }

    public float getRestTilt() {
        return this.restTilt;
    }

    /** Roll in degrees. Yaw is {@link #getYRot()} and pitch {@link #getXRot()}. */
    public float getRoll() {
        return this.roll;
    }

    /** Roll as of the previous tick, for the renderer to interpolate from. */
    public float getRollO() {
        return this.rollO;
    }

    public boolean floats() {
        return this.floats;
    }

    public MineTrigger getTrigger() {
        return this.trigger;
    }

    public ResourceLocation getTriggerId() {
        return this.triggerId;
    }

    /** @return true if this mine throws itself clear before the warhead fires. */
    public boolean bounds() {
        return this.bounceHeight > 0.0;
    }

    public ResourceLocation getDetonationId() {
        return this.detonationId;
    }

    /** @return the preset this mine was laid from, or null for one built by hand. */
    @Nullable
    public ResourceLocation getPresetId() {
        String id = this.entityData.get(PRESET);
        return id.isEmpty() ? null : ResourceLocation.tryParse(id);
    }

    /** @return the finish this mine is wearing, which is a fact about the ground it is lying on. */
    public MineCamo getCamo() {
        return MineCamo.parse(this.entityData.get(CAMO));
    }

    /**
     * @return the mine's full height in blocks, before any of it is put under the ground.
     */
    public float bodyHeight() {
        return this.entityData.get(HEIGHT);
    }

    /** @return whether this mine has been dug into the ground. */
    public boolean isBuried() {
        return this.entityData.get(BURIED);
    }

    /**
     * @return whether it is floating on a mooring, which is what decides whether a chain is drawn
     *      below it. False on land, false while it is still on its way up through the water.
     */
    public boolean isMoored() {
        return this.entityData.get(MOORED);
    }

    /** How far below the surface it rides; the mooring chain starts at its underside, not here. */
    public double draught() {
        return this.draught;
    }

    /** @return ticks this mine will stay live for, or 0 if it never expires. */
    public int selfDestructTicks() {
        return this.selfDestructTicks;
    }

    /** @return ticks left before it destroys itself, or -1 if it never will. */
    public int ticksToSelfDestruct() {
        return this.selfDestructTicks > 0 ? Math.max(0, this.selfDestructTicks - this.lifeTicks) : -1;
    }

    /** @return true if this mine is only live while it is in a fluid. */
    public boolean armsOnlyInWater() {
        return this.armsOnlyInWater;
    }

    /** @return whether the mine was standing in a fluid the last time it moved. */
    public boolean isWet() {
        return this.wet;
    }

    /** @return whether this mine is one that can be dug in at all. */
    public boolean isBuriable() {
        return this.entityData.get(BURIABLE);
    }

    /** Whether this is a rack: something that holds canisters and sheds them rather than being consumed. */
    public boolean isRack() {
        return canisterCapacity() > 0;
    }

    /** Canisters still on the rack. Synced: it is what the model draws. */
    public int canisters() {
        return this.entityData.get(CANISTERS);
    }

    public int canisterCapacity() {
        return this.entityData.get(CANISTER_CAPACITY);
    }

    /** What this rack sows, and therefore what reloads it. Null for anything that is not a rack. */
    @Nullable
    public ResourceLocation canisterMineId() {
        String id = this.entityData.get(CANISTER_MINE);
        return id.isEmpty() ? null : ResourceLocation.tryParse(id);
    }

    /** @return how full the rack is, 0 (empty) to 1 (full); 0 for anything that is not a rack. */
    public float canisterFraction() {
        int capacity = canisterCapacity();
        return capacity <= 0 ? 0.0f : (float) canisters() / capacity;
    }

    private void setCanisters(int count) {
        this.entityData.set(CANISTERS, (byte) Mth.clamp(count, 0, canisterCapacity()));
    }

    /** Puts this rack down carrying {@code count} canisters rather than a full load. */
    public void loadCanisters(int count) {
        setCanisters(count);
    }

    /**
     * Puts one canister back on the rack.
     *
     * @return true if there was room
     */
    public boolean reload() {
        if (!isRack() || canisters() >= canisterCapacity()) {
            return false;
        }
        setCanisters(canisters() + 1);
        playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.6f, 1.3f);
        return true;
    }

    /** Puts one canister on from the player's hand, having checked that it may be. */
    public InteractionResult tryReload(Player player, InteractionHand hand) {
        Component refusal = reloadRefusal(player);
        if (refusal != null) {
            player.displayClientMessage(refusal.copy()
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.FAIL;
        }
        ItemStack canister = canisterItem(player);
        if (canister.isEmpty() || !reload()) {
            return InteractionResult.FAIL;
        }
        if (!player.getAbilities().instabuild) {
            canister.shrink(1);
        }
        player.swing(hand, true);
        return InteractionResult.CONSUME;
    }

    /** @return why this rack cannot be reloaded from {@code player}'s hands, or null if it can. */
    @Nullable
    public Component reloadRefusal(Player player) {
        if (!isRack()) {
            return Component.translatable("probe.wflib.mine.not_a_rack");
        }
        if (canisters() >= canisterCapacity()) {
            return Component.translatable("probe.wflib.mine.rack_full");
        }
        if (canisterItem(player).isEmpty()) {
            return Component.translatable("probe.wflib.mine.need_canister");
        }
        return null;
    }

    /** The stack in {@code player}'s hands that this rack will accept, or empty. */
    public ItemStack canisterItem(Player player) {
        ResourceLocation wanted = canisterMineId();
        if (wanted == null) {
            return ItemStack.EMPTY;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof com.wf.wflib.item.MineItem mine
                    && wanted.equals(mine.preset()
                            .id())) {
                return held;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * @return how far below the ground the mine's body sits, in blocks.
     */
    public double buryDepth() {
        return isBuried()
                ? this.entityData.get(HEIGHT) * MineModels.buryFraction(getModelId())
                : 0.0;
    }

    public DefuseMethod getDefuseMethod() {
        return this.defuseMethod;
    }

    public State getState() {
        return State.byId(this.entityData.get(STATE));
    }

    public boolean isArmed() {
        return this.state == State.ARMED;
    }

    @Nullable
    public UUID getOwnerId() {
        return this.ownerId;
    }

    public void setTeamId(@Nullable UUID teamId) {
        this.teamId = teamId;
    }

    public void setOwnerId(@Nullable UUID ownerId) {
        this.ownerId = ownerId;
    }

    /**
     * Arms a mine that is {@link State#SAFE}: one built {@link Builder#requiresActivation() needing activation}, or
     * one {@link #layInert() laid inert}.
     */
    public boolean activate() {
        if (this.state != State.SAFE || (isRack() && canisters() <= 0)) {
            return false;
        }
        this.armTicks = 0;
        setState(this.armDelay > 0 ? State.ARMING : State.ARMED);
        playSound(SoundEvents.LEVER_CLICK, 0.6f, 1.6f);
        return true;
    }

    /** Skips whatever is left of the arming delay on a mine that is already counting down. */
    public boolean forceArmed() {
        if (this.state != State.ARMING) {
            return false;
        }
        this.armTicks = this.armDelay;
        setState(State.ARMED);
        return true;
    }

    /**
     * Puts a mine that was arming or armed back to {@link State#SAFE} without defusing it: the state a mine is laid
     * in when whoever laid it was crouching.
     *
     * @return true if it had anything to stop
     */
    public boolean layInert() {
        if (this.state != State.ARMING && this.state != State.ARMED) {
            return false;
        }
        this.armTicks = 0;
        setState(State.SAFE);
        return true;
    }

    /**
     * @return why this mine cannot be armed right now, or null if it can.
     */
    @Nullable
    public Component armRefusal(Player player) {
        if (getState() != State.SAFE) {
            return Component.translatable("probe.wflib.mine.already_live");
        }
        if (isRack() && canisters() <= 0) {
            return Component.translatable("probe.wflib.mine.rack_empty");
        }
        return null;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            this.rollO = this.roll;
            tickLerp();
            return;
        }
        this.rollO = this.roll;
        if (this.detonated) {
            return;
        }

        ServerLevel level = (ServerLevel) this.level();

        if (this.state == State.TRIPPED) {
            tickTripped(level);
            return;
        }

        if (tickLife()) {
            return;
        }
        settle(level);
        tickCamo(level);
        if (this.flopTicks > 0) {
            tickFlop();
        }
        tickArming();
        tickDefuse(level);

        if (this.state == State.ARMED && this.tickCount % this.scanInterval == Math.floorMod(getId(), this.scanInterval)) {
            scan(level);
        }
    }

    /**
     * The self-destruct clock.
     *
     * @return true if the mine has just destroyed itself and nothing else should run this tick
     */
    private boolean tickLife() {
        if (this.selfDestructTicks <= 0) {
            return false;
        }
        if (++this.lifeTicks < this.selfDestructTicks) {
            return false;
        }
        detonate();
        return true;
    }

    /** Arming. */
    private void tickArming() {
        if (this.armsOnlyInWater && !this.wet && this.state != State.SAFE) {
            if (this.state == State.ARMED) {
                setState(State.ARMING);
            }
            this.armTicks = 0;
            return;
        }
        if (this.state != State.ARMING) {
            return;
        }
        if (++this.armTicks >= this.armDelay) {
            setState(State.ARMED);
        }
    }

    /**
     * A mine only moves on the server, so the client learns where it is from the tracker's position updates, and
     * {@link Entity#lerpTo} snaps to them.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (steps <= 1 || this.distanceToSqr(x, y, z) > LERP_SNAP_DISTANCE * LERP_SNAP_DISTANCE) {
            this.lerpSteps = 0;
            this.setPos(x, y, z);
            this.setRot(yRot, xRot);
            return;
        }
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpXRot = xRot;
        this.lerpSteps = steps;
    }

    @Override
    public double lerpTargetX() {
        return this.lerpSteps > 0 ? this.lerpX : this.getX();
    }

    @Override
    public double lerpTargetY() {
        return this.lerpSteps > 0 ? this.lerpY : this.getY();
    }

    @Override
    public double lerpTargetZ() {
        return this.lerpSteps > 0 ? this.lerpZ : this.getZ();
    }

    @Override
    public float lerpTargetYRot() {
        return this.lerpSteps > 0 ? this.lerpYRot : this.getYRot();
    }

    @Override
    public float lerpTargetXRot() {
        return this.lerpSteps > 0 ? this.lerpXRot : this.getXRot();
    }

    /** One step of that walk. */
    private void tickLerp() {
        tickRollLerp();
        if (this.lerpSteps <= 0) {
            return;
        }
        this.lerpPositionAndRotationStep(this.lerpSteps, this.lerpX, this.lerpY, this.lerpZ,
                this.lerpYRot, this.lerpXRot);
        this.lerpSteps--;
    }

    /**
     * The same walk for roll, which arrives on its own channel: stepping it over the same number of ticks keeps it
     * in phase with the yaw and pitch riding the position packets, so a tumbling mine spins about one attitude
     * rather than wobbling between two.
     */
    private void tickRollLerp() {
        if (this.lerpRollSteps <= 0) {
            return;
        }
        this.roll += Mth.wrapDegrees(this.lerpRoll - this.roll) / this.lerpRollSteps;
        this.lerpRollSteps--;
    }

    private void tickTripped(ServerLevel level) {
        if (this.bouncing) {
            tumbleStep();
            this.move(MoverType.SELF, this.getDeltaMovement());
            Vec3 motion = this.getDeltaMovement();
            this.setDeltaMovement(motion.x * DRAG, motion.y - GRAVITY, motion.z * DRAG);
            // Apex, ceiling hit, or the hop simply ran out of patience.
            if (this.getDeltaMovement().y <= 0.0 || this.verticalCollision || ++this.bounceTicks >= BOUNCE_FUSE) {
                detonate();
            }
            return;
        }
        if (--this.fuse <= 0) {
            detonate();
        }
    }

    private void settle(ServerLevel level) {
        if (this.resting && undisturbed(level)) {
            return;
        }
        this.resting = false;
        // On the move, so the ground it is painted for may be behind it. Decided again where it lands.
        this.camoStale = true;
        tumbleStep();

        BlockPos at = this.blockPosition();
        this.wet = !level.getFluidState(at).isEmpty();
        if (this.floats) {
            boolean submerged = this.wet;
            if (submerged) {
                double target = surface(level, at) - this.draught;
                if (this.getY() >= target - SETTLE_EPSILON) {
                    this.setPos(this.getX(), target, this.getZ());
                    this.entityData.set(MOORED, true);
                    rest(level);
                    return;
                }
                Vec3 motion = this.getDeltaMovement();
                double rise = motion.y < 0.0 ? motion.y * WATER_ENTRY_DRAG + BUOYANCY : motion.y + BUOYANCY;
                this.setDeltaMovement(motion.x * 0.8, Math.min(rise, MAX_RISE), motion.z * 0.8);
                this.move(MoverType.SELF, this.getDeltaMovement());
                dampSpin();
                return;
            }
            if (!level.getFluidState(at.below()).isEmpty()) {
                // Came up through the surface entirely: put it back down to its draught.
                this.setPos(this.getX(), at.getY() - this.draught, this.getZ());
                this.entityData.set(MOORED, true);
                rest(level);
                return;
            }
        }

        this.entityData.set(MOORED, false);
        this.move(MoverType.SELF, this.getDeltaMovement());
        if (this.onGround()) {
            rest(level);
            return;
        }
        Vec3 motion = this.getDeltaMovement();
        this.setDeltaMovement(motion.x * DRAG, motion.y - GRAVITY, motion.z * DRAG);
    }

    /**
     * @return the y of the fluid surface above {@code from}: the first block that is not fluid.
     */
    private static double surface(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos cursor = from.mutable();
        for (int step = 0; step < MAX_FLUID_DEPTH; step++) {
            if (level.getFluidState(cursor).isEmpty()) {
                return cursor.getY();
            }
            cursor.move(0, 1, 0);
        }
        return cursor.getY();
    }

    /** One tick of tumbling. */
    private void tumbleStep() {
        if (this.tumble <= 0.0f) {
            return;
        }
        if (!spinning()) {
            if (this.getDeltaMovement().lengthSqr() < TUMBLE_SPEED * TUMBLE_SPEED) {
                return;
            }
            seedSpin(this.random, this.tumble);
        }
        this.tumbled = true;
        this.flopTicks = 0;
        this.setYRot(Mth.wrapDegrees(this.getYRot() + this.spinYaw));
        this.setXRot(Mth.wrapDegrees(this.getXRot() + this.spinPitch));
        setRoll(Mth.wrapDegrees(this.roll + this.spinRoll));
    }

    /** Water takes the spin out of it, so a mine that sinks in still settles instead of turning forever. */
    private void dampSpin() {
        this.spinYaw *= 0.85f;
        this.spinPitch *= 0.85f;
        this.spinRoll *= 0.85f;
    }

    private boolean spinning() {
        return this.spinYaw != 0.0f || this.spinPitch != 0.0f || this.spinRoll != 0.0f;
    }

    private void seedSpin(RandomSource random, float rate) {
        this.spinYaw = spinAxis(random, rate);
        this.spinPitch = spinAxis(random, rate);
        this.spinRoll = spinAxis(random, rate);
    }

    /** A third of the rate at least, either way round: no axis comes out dead and none of them match. */
    private static float spinAxis(RandomSource random, float rate) {
        float magnitude = rate * (0.33f + random.nextFloat() * 0.67f);
        return random.nextBoolean() ? magnitude : -magnitude;
    }

    /** Landing. */
    private void settleAttitude() {
        this.spinYaw = this.spinPitch = this.spinRoll = 0.0f;
        if (!this.tumbled) {
            return;
        }
        this.tumbled = false;
        float tilt = Mth.clamp(this.restTilt, 0.0f, 89.0f) / 180.0f;
        this.flopPitch = Mth.wrapDegrees(this.getXRot()) * tilt;
        this.flopRoll = Mth.wrapDegrees(this.roll) * tilt;
        this.flopTicks = this.flopPitch != this.getXRot() || this.flopRoll != this.roll ? FLOP_TICKS : 0;
    }

    private void tickFlop() {
        float steps = this.flopTicks;
        this.setXRot(this.getXRot() + Mth.wrapDegrees(this.flopPitch - this.getXRot()) / steps);
        setRoll(this.roll + Mth.wrapDegrees(this.flopRoll - this.roll) / steps);
        if (--this.flopTicks <= 0) {
            this.setXRot(this.flopPitch);
            setRoll(this.flopRoll);
        }
    }

    /**
     * Throws this mine, for a deployer or a dispersing payload: it takes the velocity, turns to a random heading
     * and tumbles the rest of the way down, then lies wherever it lands.
     */
    public void scatter(Vec3 velocity, RandomSource random) {
        scatter(velocity, random, this.tumble > 0.0f ? this.tumble : DEFAULT_TUMBLE);
    }

    public void scatter(Vec3 velocity, RandomSource random, float spinDegreesPerTick) {
        this.setDeltaMovement(velocity);
        this.hasImpulse = true;
        this.resting = false;
        this.flopTicks = 0;
        this.setYRot(random.nextFloat() * 360.0f);
        if (spinDegreesPerTick > 0.0f) {
            seedSpin(random, spinDegreesPerTick);
        }
    }

    /** @return true if neither watched subchunk has changed since this mine settled. */
    private boolean undisturbed(ServerLevel level) {
        if (!this.watching) {
            return false;
        }
        if (this.getX() != this.restX || this.getY() != this.restY || this.getZ() != this.restZ
                || this.getDeltaMovement().lengthSqr() > 1.0E-9) {
            return false;
        }
        if (MineSectionWatch.version(level, this.ownSection) != this.ownVersion) {
            return false;
        }
        return this.ownSection == this.supportSection
                || MineSectionWatch.version(level, this.supportSection) == this.supportVersion;
    }

    private void rest(ServerLevel level) {
        if (isBuried()) {
            if (this.buriedAt == null) {
                // Loaded already dug in: wherever it is now is the hole.
                this.buriedAt = this.position();
            } else if (this.position()
                    .distanceToSqr(this.buriedAt) > DISLODGED_SQR) {
                setBuried(false);
            }
        }
        this.resting = true;
        this.setDeltaMovement(Vec3.ZERO);
        this.restX = this.getX();
        this.restY = this.getY();
        this.restZ = this.getZ();
        settleAttitude();

        BlockPos at = this.blockPosition();
        long own = MineSectionWatch.key(at);
        long support = MineSectionWatch.key(at.below());
        if (this.watching && own == this.ownSection && support == this.supportSection) {
            // Same two sections; just take the revisions we are now current with.
            this.ownVersion = MineSectionWatch.version(level, own);
            this.supportVersion = own == support ? this.ownVersion : MineSectionWatch.version(level, support);
            return;
        }

        releaseSections(level);
        this.ownSection = own;
        this.supportSection = support;
        this.ownVersion = MineSectionWatch.watch(level, own);
        this.supportVersion = own == support ? this.ownVersion : MineSectionWatch.watch(level, support);
        this.watching = true;
    }

    private void releaseSections(ServerLevel level) {
        if (!this.watching) {
            return;
        }
        MineSectionWatch.unwatch(level, this.ownSection);
        if (this.supportSection != this.ownSection) {
            MineSectionWatch.unwatch(level, this.supportSection);
        }
        this.watching = false;
    }

    /**
     * The trigger decides everything; the trigger range only decides how big a box is worth looking in, so a lambda
     * that ignores range still cannot see past it.
     */
    private void scan(ServerLevel level) {
        AABB box = this.getBoundingBox()
                .inflate(this.triggerRange);
        for (Entity candidate : nearby(level, this.trigger.narrow(), box)) {
            if (this.trigger.test(this, candidate)) {
                trip(level);
                return;
            }
        }
    }

    /** Generic so the wildcard on {@link MineTrigger#narrow()} has somewhere to be captured. */
    private <T extends Entity> List<T> nearby(ServerLevel level, Class<T> narrow, AABB box) {
        return level.getEntitiesOfClass(narrow, box, this::candidate);
    }

    /** The cheap gate every trigger would otherwise have to repeat for itself. */
    private boolean candidate(Entity entity) {
        return entity != this && entity.isAlive() && !(entity instanceof MineEntity);
    }

    private void trip(ServerLevel level) {
        if (this.bounceHeight > 0.0) {
            setState(State.TRIPPED);
            this.bouncing = true;
            this.bounceTicks = 0;
            this.resting = false;
            releaseSections(level);
            // v = sqrt(2gh): the hop peaks at the configured height.
            this.setDeltaMovement(0.0, Math.sqrt(2.0 * GRAVITY * this.bounceHeight), 0.0);
            this.hasImpulse = true;
            playSound(SoundEvents.PISTON_EXTEND, 0.9f, 1.8f);
            return;
        }
        detonate();
    }

    /** Goes off. */
    private void detonate() {
        if (this.detonated) {
            return;
        }
        boolean expended = !isRack();
        this.detonated = true; // set before the blast: it can hurt this mine before discard() runs
        if (expended && this.level() instanceof ServerLevel level) {
            releaseSections(level);
        }
        MineEntity outer = detonating;
        detonating = this;
        try {
            this.detonation.detonate(this, this.position()
                    .add(0.0, this.getBbHeight() * 0.5, 0.0));
        } finally {
            detonating = outer;
        }
        if (expended) {
            this.discard();
            return;
        }
        emptied();
    }

    /** What a rack looks like the moment after it fires: no canisters, nothing live, nothing armed. */
    private void emptied() {
        setCanisters(0);
        this.detonated = false;
        this.fuse = -1;
        this.bouncing = false;
        this.armTicks = 0;
        setState(State.SAFE);
    }

    /** Re-reads the ground under the mine and repaints it to suit, if it has moved since it last looked. */
    private void tickCamo(ServerLevel level) {
        if (!this.camoStale) {
            return;
        }
        if (!this.resting && this.random.nextInt(CAMO_CHECK_CHANCE) != 0) {
            return;
        }
        this.entityData.set(CAMO, MineCamo.forGround(level, this.getOnPos())
                .id());
        this.camoStale = !this.resting;
    }

    private void setBuried(boolean value) {
        if (this.entityData.get(BURIED) == value) {
            return;
        }
        this.entityData.set(BURIED, value);
        this.buriedAt = value ? this.position() : null;
        this.refreshDimensions();
    }

    /**
     * @return why this mine cannot be dug {@code digIn ? in : out} right now, or null if it can.
     */
    @Nullable
    public Component buryRefusal(Player player, boolean digIn) {
        if (!isBuriable()) {
            return Component.translatable("probe.wflib.mine.cannot_bury");
        }
        if (!player.getMainHandItem()
                .is(ItemTags.SHOVELS) && !player.getOffhandItem()
                .is(ItemTags.SHOVELS)) {
            return Component.translatable("probe.wflib.mine.need_shovel");
        }
        if (digIn && !this.level()
                .getBlockState(this.getOnPos())
                .is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return Component.translatable("probe.wflib.mine.ground_too_hard");
        }
        return null;
    }

    /** Digs a mine into the ground, or back out of it, having already decided that it may be. */
    public void applyBury(ServerLevel level, Player player, InteractionHand hand, boolean digIn) {
        setBuried(digIn);
        BlockState ground = level.getBlockState(this.getOnPos());
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                digIn ? SoundEvents.SHOVEL_FLATTEN : SoundEvents.ROOTED_DIRT_BREAK,
                SoundSource.BLOCKS, 0.8f, digIn ? 0.9f : 1.1f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                this.getX(), this.getY() + 0.05, this.getZ(), 12, 0.25, 0.02, 0.25, 0.02);
        player.swing(hand, true);
    }

    /** The shovel-in-hand half of digging in: a plain right-click toggles it. */
    private InteractionResult tryBury(ServerLevel level, Player player, InteractionHand hand) {
        boolean digIn = !isBuried();
        Component refusal = buryRefusal(player, digIn);
        if (refusal != null) {
            player.displayClientMessage(refusal.copy()
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.FAIL;
        }
        applyBury(level, player, hand, digIn);
        return InteractionResult.CONSUME;
    }

    /** Begins (or restarts) a defusal. */
    public InteractionResult tryDefuse(Player player, InteractionHand hand) {
        Component refusal = defuseRefusal(player, this.defuseMethod, this.defuseTool);
        if (refusal != null) {
            player.displayClientMessage(refusal.copy()
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.FAIL;
        }
        this.defuserId = player.getUUID();
        this.defuseProgress = 0;
        return InteractionResult.CONSUME;
    }

    /**
     * @return why a {@code method}/{@code tool} defusal cannot be started, or null if it can.
     */
    @Nullable
    public static Component defuseRefusal(Player player, DefuseMethod method,
                                          @Nullable TagKey<Item> tool) {
        if (method == DefuseMethod.NONE) {
            return Component.translatable("probe.wflib.mine.cannot_defuse");
        }
        if (method == DefuseMethod.TOOL && tool != null
                && !player.getMainHandItem()
                        .is(tool)
                && !player.getOffhandItem()
                        .is(tool)) {
            return Component.translatable("probe.wflib.mine.need_defuser");
        }
        if (!player.isCrouching()) {
            return Component.translatable("probe.wflib.mine.need_crouch");
        }
        return null;
    }

    private void tickDefuse(ServerLevel level) {
        if (this.defuserId == null) {
            return;
        }
        Player player = level.getPlayerByUUID(this.defuserId);
        if (player == null || !player.isAlive() || !player.isCrouching()
                || player.distanceToSqr(this) > DEFUSE_REACH * DEFUSE_REACH) {
            this.defuserId = null;
            this.defuseProgress = 0;
            return;
        }
        if (++this.defuseProgress % 10 == 0) {
            playSound(SoundEvents.STONE_BUTTON_CLICK_ON, 0.3f, 1.4f);
        }
        if (this.defuseProgress < this.defuseTicks) {
            return;
        }
        if (this.defuseFailChance > 0.0f && this.random.nextFloat() < this.defuseFailChance) {
            detonate();
            return;
        }
        defused(level, player);
    }

    private void defused(ServerLevel level, Player player) {
        setState(State.SAFE);
        this.defuserId = null;
        this.defuseProgress = 0;
        level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ITEM_PICKUP,
                SoundSource.NEUTRAL, 0.7f, 1.2f);
        ItemStack drop = com.wf.wflib.item.MinePresetRegistry.stackFor(this.presetId);
        if (isRack() && !drop.isEmpty() && canisters() < canisterCapacity()) {
            drop.set(com.wf.wflib.item.ModDataComponents.MINE_CANISTERS.get(), canisters());
        }
        if (!drop.isEmpty() && !player.addItem(drop)) {
            this.spawnAtLocation(drop);
        }
        this.discard();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player.isSecondaryUseActive()) {
            return tryDefuse(player, hand);
        }
        if (isBuriable() && player.getItemInHand(hand)
                .is(ItemTags.SHOVELS)) {
            return tryBury((ServerLevel) this.level(), player, hand);
        }
        if (isRack() && !canisterItem(player).isEmpty()) {
            return tryReload(player, hand);
        }
        return InteractionResult.PASS;
    }

    /** Fires this mine from a detonator, whatever it was waiting for. */
    @Override
    public boolean detonateOnCommand(ServerLevel level, @Nullable Player detonator) {
        if (this.detonated || this.isRemoved() || this.state == State.TRIPPED) {
            return false;
        }
        if (detonator != null) {
            this.ownerId = detonator.getUUID();
        }
        trip(level);
        return true;
    }

    /** Only the owner may wire a mine to a clacker, and only while it has one. */
    @Override
    public boolean canWireDetonator(Player player) {
        return this.ownerId == null || this.ownerId.equals(player.getUUID())
                || player.hasPermissions(2);
    }

    @Override
    public String detonatorLabel() {
        return this.presetId == null ? "mine" : this.presetId.getPath();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved() || this.detonated || this.isInvulnerableTo(source)) {
            return false;
        }
        if (!sympathetic()) {
            return false;
        }
        if (this.state == State.SAFE) {
            this.discard();
            return true;
        }
        setState(State.TRIPPED);
        this.bouncing = false;
        this.fuse = DAMAGE_FUSE;
        return true;
    }

    /** The mine whose warhead is running right now, or null. */
    @Nullable
    private static MineEntity detonating = null;

    /** Whether this mine should answer the damage it is being dealt at all. */
    private boolean sympathetic() {
        MineEntity blast = detonating;
        return blast == null || blast == this
                || this.distanceToSqr(blast) <= SYMPATHY_RANGE * SYMPATHY_RANGE;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel level) {
            releaseSections(level);
        }
        super.remove(reason);
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    private void setState(State next) {
        this.state = next;
        this.entityData.set(STATE, next.id());
    }

    private void setRoll(float degrees) {
        this.roll = degrees;
        this.entityData.set(ROLL, degrees);
    }

    private void setSize(double width, double height) {
        this.entityData.set(WIDTH, (float) width);
        this.entityData.set(HEIGHT, (float) height);
        this.refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(this.entityData.get(WIDTH),
                (float) Math.max(0.05, this.entityData.get(HEIGHT) - buryDepth()));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (WIDTH.equals(key) || HEIGHT.equals(key) || BURIED.equals(key)) {
            this.refreshDimensions();
        } else if (ROLL.equals(key) && this.level().isClientSide) {
            float target = this.entityData.get(ROLL);
            if (this.tickCount == 0) {
                // Freshly spawned: this is the attitude it already has, not one to turn towards.
                this.roll = this.rollO = target;
                this.lerpRollSteps = 0;
            } else {
                this.lerpRoll = target;
                this.lerpRollSteps = LERP_STEPS;
            }
        }
        super.onSyncedDataUpdated(key);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODEL_ID, MineModels.DEFAULT.toString());
        Vec3 size = MineModels.size(MineModels.DEFAULT);
        builder.define(WIDTH, (float) size.x);
        builder.define(HEIGHT, (float) size.y);
        builder.define(STATE, State.ARMING.id());
        builder.define(ROLL, 0.0f);
        builder.define(CAMO, MineCamo.DEFAULT.id());
        builder.define(BURIED, false);
        builder.define(BURIABLE, false);
        builder.define(PRESET, "");
        builder.define(CANISTERS, (byte) 0);
        builder.define(CANISTER_CAPACITY, (byte) 0);
        builder.define(CANISTER_MINE, "");
        builder.define(MOORED, false);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("Model", this.entityData.get(MODEL_ID));
        tag.putFloat("Width", this.entityData.get(WIDTH));
        tag.putFloat("Height", this.entityData.get(HEIGHT));

        tag.putString("Detonation", this.detonationId.toString());
        tag.putString("Trigger", this.triggerId.toString());
        tag.putInt("FragmentCount", this.fragmentCount);
        tag.putFloat("BlastHalfAngle", this.blastHalfAngle);
        tag.putBoolean("BlastAlongFacing", this.blastAlongFacing);
        if (this.blastDirection != null) {
            tag.putDouble("BlastX", this.blastDirection.x);
            tag.putDouble("BlastY", this.blastDirection.y);
            tag.putDouble("BlastZ", this.blastDirection.z);
        }

        tag.putDouble("TriggerRange", this.triggerRange);
        tag.putDouble("SneakTriggerRange", this.sneakTriggerRange);
        tag.putDouble("Arc", this.arc);
        tag.putInt("ScanInterval", this.scanInterval);

        tag.putInt("ArmDelay", this.armDelay);
        tag.putBoolean("RequiresActivation", this.requiresActivation);
        tag.putByte("State", this.state.id());
        tag.putInt("ArmTicks", this.armTicks);
        tag.putInt("Fuse", this.fuse);
        tag.putBoolean("Bouncing", this.bouncing);

        tag.putString("DefuseMethod", this.defuseMethod.name());
        if (this.defuseTool != null) {
            tag.putString("DefuseTool", this.defuseTool.location()
                    .toString());
        }
        tag.putInt("DefuseTicks", this.defuseTicks);
        tag.putFloat("DefuseFailChance", this.defuseFailChance);

        tag.putString("Camo", this.entityData.get(CAMO));
        tag.putBoolean("Buriable", this.buriable);
        tag.putBoolean("Buried", this.entityData.get(BURIED));

        tag.putBoolean("Floats", this.floats);
        tag.putDouble("Draught", this.draught);
        if (this.selfDestructTicks > 0) {
            tag.putInt("SelfDestruct", this.selfDestructTicks);
            tag.putInt("Life", this.lifeTicks);
        }
        tag.putBoolean("WaterOnly", this.armsOnlyInWater);
        tag.putBoolean("Moored", this.entityData.get(MOORED));
        tag.putDouble("BounceHeight", this.bounceHeight);
        tag.putFloat("Tumble", this.tumble);
        tag.putFloat("RestTilt", this.restTilt);
        tag.putFloat("Roll", this.roll);
        if (spinning()) {
            tag.putFloat("SpinYaw", this.spinYaw);
            tag.putFloat("SpinPitch", this.spinPitch);
            tag.putFloat("SpinRoll", this.spinRoll);
        }

        if (this.teamId != null) {
            tag.putUUID("TeamId", this.teamId);
        }
        if (this.ownerId != null) {
            tag.putUUID("OwnerId", this.ownerId);
        }
        if (this.presetId != null) {
            tag.putString("Preset", this.presetId.toString());
        }
        if (canisterCapacity() > 0) {
            tag.putByte("Canisters", (byte) canisters());
            tag.putByte("CanisterCapacity", (byte) canisterCapacity());
            String canisterMine = this.entityData.get(CANISTER_MINE);
            if (!canisterMine.isEmpty()) {
                tag.putString("CanisterMine", canisterMine);
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Model")) {
            ResourceLocation model = MineModels.parse(tag.getString("Model"));
            this.entityData.set(MODEL_ID, model.toString());
            if (!(tag.contains("Width") && tag.contains("Height"))) {
                Vec3 size = MineModels.size(model);
                setSize(size.x, size.y);
            }
        }
        if (tag.contains("Width") && tag.contains("Height")) {
            setSize(tag.getFloat("Width"), tag.getFloat("Height"));
        }

        if (tag.contains("Detonation")) {
            this.detonationId = WarheadRegistry.parse(tag.getString("Detonation"));
            this.detonation = WarheadRegistry.get(this.detonationId);
        }
        if (tag.contains("Trigger")) {
            this.triggerId = MineTriggers.parse(tag.getString("Trigger"));
            this.trigger = MineTriggers.get(this.triggerId);
        } else if (tag.contains("Target")) {
            this.triggerId = MineTriggers.parse(tag.getString("Target"));
            this.trigger = MineTriggers.get(this.triggerId);
        }
        this.fragmentCount = tag.contains("FragmentCount") ? tag.getInt("FragmentCount") : this.fragmentCount;
        this.blastHalfAngle = tag.contains("BlastHalfAngle")
                ? Mth.clamp(tag.getFloat("BlastHalfAngle"), 1.0f, 89.0f) : this.blastHalfAngle;
        this.blastDirection = tag.contains("BlastY")
                ? new Vec3(tag.getDouble("BlastX"), tag.getDouble("BlastY"), tag.getDouble("BlastZ"))
                : null;
        this.blastAlongFacing = tag.getBoolean("BlastAlongFacing");

        this.triggerRange = tag.contains("TriggerRange") ? tag.getDouble("TriggerRange") : this.triggerRange;
        this.sneakTriggerRange = tag.contains("SneakTriggerRange")
                ? tag.getDouble("SneakTriggerRange") : this.sneakTriggerRange;
        this.arc = tag.contains("Arc") ? tag.getDouble("Arc") : this.arc;
        this.scanInterval = Math.max(1, tag.contains("ScanInterval") ? tag.getInt("ScanInterval") : this.scanInterval);

        this.armDelay = tag.contains("ArmDelay") ? tag.getInt("ArmDelay") : this.armDelay;
        this.requiresActivation = tag.getBoolean("RequiresActivation");
        setState(tag.contains("State") ? State.byId(tag.getByte("State")) : this.state);
        this.armTicks = tag.getInt("ArmTicks");
        this.fuse = tag.contains("Fuse") ? tag.getInt("Fuse") : -1;
        this.bouncing = tag.getBoolean("Bouncing");

        if (tag.contains("DefuseMethod")) {
            this.defuseMethod = defuseMethod(tag.getString("DefuseMethod"));
        }
        if (tag.contains("DefuseTool")) {
            ResourceLocation toolTag = ResourceLocation.tryParse(tag.getString("DefuseTool"));
            this.defuseTool = toolTag == null ? null : TagKey.create(Registries.ITEM, toolTag);
        }
        this.defuseTicks = tag.contains("DefuseTicks") ? tag.getInt("DefuseTicks") : this.defuseTicks;
        this.defuseFailChance = tag.contains("DefuseFailChance")
                ? tag.getFloat("DefuseFailChance") : this.defuseFailChance;

        if (tag.contains("Camo")) {
            this.entityData.set(CAMO, MineCamo.parse(tag.getString("Camo"))
                    .id());
        }
        this.buriable = tag.getBoolean("Buriable");
        this.entityData.set(BURIABLE, this.buriable);
        if (tag.contains("CanisterCapacity")) {
            this.entityData.set(CANISTER_CAPACITY, tag.getByte("CanisterCapacity"));
            setCanisters(tag.getByte("Canisters"));
        }
        if (tag.contains("CanisterMine")) {
            this.entityData.set(CANISTER_MINE, tag.getString("CanisterMine"));
        }
        this.entityData.set(BURIED, tag.getBoolean("Buried"));
        refreshDimensions();

        this.floats = tag.getBoolean("Floats");
        this.draught = tag.getDouble("Draught");
        this.selfDestructTicks = tag.getInt("SelfDestruct");
        this.lifeTicks = tag.getInt("Life");
        this.armsOnlyInWater = tag.getBoolean("WaterOnly");
        this.entityData.set(MOORED, tag.getBoolean("Moored"));
        this.bounceHeight = tag.contains("BounceHeight") ? tag.getDouble("BounceHeight") : this.bounceHeight;
        this.tumble = tag.contains("Tumble") ? Math.max(0.0f, tag.getFloat("Tumble")) : this.tumble;
        this.restTilt = tag.contains("RestTilt") ? Mth.clamp(tag.getFloat("RestTilt"), 0.0f, 89.0f) : this.restTilt;
        setRoll(tag.getFloat("Roll"));
        this.rollO = this.roll;
        this.spinYaw = tag.getFloat("SpinYaw");
        this.spinPitch = tag.getFloat("SpinPitch");
        this.spinRoll = tag.getFloat("SpinRoll");

        this.teamId = tag.hasUUID("TeamId") ? tag.getUUID("TeamId") : null;
        this.ownerId = tag.hasUUID("OwnerId") ? tag.getUUID("OwnerId") : null;
        this.presetId = tag.contains("Preset") ? ResourceLocation.tryParse(tag.getString("Preset")) : null;
        this.entityData.set(PRESET, this.presetId == null ? "" : this.presetId.toString());

        // The world may have changed under it while it was unloaded, so it settles once more on load.
        this.resting = false;
    }

    private static DefuseMethod defuseMethod(String name) {
        for (DefuseMethod method : DefuseMethod.values()) {
            if (method.name()
                    .equals(name)) {
                return method;
            }
        }
        return DefuseMethod.HAND;
    }

    /** Where a mine is in its life. Synced so the client can tell a live mine from a safe one. */
    public enum State {
        /** Deployed but inert: it was built needing {@link MineEntity#activate()}, or it was defused. */
        SAFE,
        /** Counting down its arming delay. */
        ARMING,
        /** Live. */
        ARMED,
        /** Set off, and on its way to going off: mid-hop, or on a short fuse from a hit. */
        TRIPPED;

        private static final State[] VALUES = values();

        public static State byId(byte id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : ARMING;
        }

        public byte id() {
            return (byte) ordinal();
        }
    }

    /** Configures and creates a mine. */
    public static final class Builder {

        private final EntityType<? extends MineEntity> type;
        private final Level level;

        private ResourceLocation modelId = MineModels.DEFAULT;
        private Double width = null;  // null = the model's own
        private Double height = null;

        private ResourceLocation detonationId = WarheadRegistry.defaultId();
        private ResourceLocation triggerId = MineTriggers.defaultId();
        private MineTrigger trigger = null; // set directly, bypassing the registry
        private int canisterCount = 0;
        private ResourceLocation canisterMineId = null;
        private int fragmentCount = DEFAULT_FRAGMENT_COUNT;
        private Vec3 blastDirection = null;
        private boolean blastAlongFacing = false;
        private float blastHalfAngle = DEFAULT_BLAST_HALF_ANGLE;

        private Double triggerRange = null;
        private Double sneakTriggerRange = null;
        private double arc = DEFAULT_ARC;
        private int scanInterval = DEFAULT_SCAN_INTERVAL;

        private int armDelay = DEFAULT_ARM_DELAY;
        private boolean requiresActivation = false;
        private DefuseMethod defuseMethod = DefuseMethod.HAND;
        private TagKey<Item> defuseTool = null;
        private int defuseTicks = DEFAULT_DEFUSE_TICKS;
        private float defuseFailChance = 0.0f;

        private boolean floats = false;
        private int selfDestructTicks = 0;
        private boolean armsOnlyInWater = false;
        private double draught = 0.0;
        private boolean buriable = false;
        private double bounceHeight = 0.0;
        private float tumble = 0.0f;
        private float restTilt = DEFAULT_REST_TILT;

        private UUID teamId = null;
        private UUID ownerId = null;
        private ResourceLocation presetId = null;
        private float yaw = 0.0f;

        private Builder(EntityType<? extends MineEntity> type, Level level) {
            this.type = type;
            this.level = level;
        }

        /** Pick the model by registered id (see {@link MineModels}); it also supplies the default size. */
        public Builder model(ResourceLocation modelId) {
            this.modelId = MineModels.exists(modelId) ? modelId : MineModels.defaultId();
            return this;
        }

        /** Body size in blocks, overriding the model's own. Height is measured up from the ground. */
        public Builder size(double width, double height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Pick the warhead by registered id (see {@link WarheadRegistry#register}); defaults to {@code "standard"}.
         */
        public Builder detonation(ResourceLocation detonationId) {
            this.detonationId = detonationId;
            return this;
        }

        /** Pick the whole trigger condition by registered id (see {@link MineTriggers}). */
        public Builder trigger(ResourceLocation triggerId) {
            this.triggerId = triggerId;
            this.trigger = null;
            return this;
        }

        /** Hand the condition straight in as a lambda. */
        public Builder trigger(MineTrigger trigger) {
            this.trigger = trigger;
            return this;
        }

        /** Bomblets a {@code fragmentation} warhead scatters. No effect for other warheads. */
        public Builder fragmentCount(int fragmentCount) {
            this.fragmentCount = fragmentCount;
            return this;
        }

        /** Force the jet axis of a directional warhead. */
        public Builder blastDirection(Vec3 direction) {
            this.blastDirection = direction;
            return this;
        }

        /**
         * Fire a directional warhead along the mine's facing rather than straight up: a claymore rather than an
         * anti-armour charge.
         */
        public Builder blastAlongFacing() {
            this.blastAlongFacing = true;
            return this;
        }

        /** Half-angle (degrees, 1..89) of the cone a directional warhead fires into. */
        public Builder blastHalfAngle(float degrees) {
            this.blastHalfAngle = Mth.clamp(degrees, 1.0f, 89.0f);
            return this;
        }

        /** How far out it sees something that is not being careful. */
        public Builder triggerRange(double blocks) {
            this.triggerRange = blocks;
            return this;
        }

        /** How far out it sees a <em>crouching</em> approach. */
        public Builder sneakTriggerRange(double blocks) {
            this.sneakTriggerRange = blocks;
            return this;
        }

        /** Total arc in degrees the mine watches, centred on its facing, read by {@link MineTrigger#inArc}. */
        /**
         * Makes this a rack: {@code count} canisters of {@code mineId}, which it sheds when it goes off instead of
         * being consumed, and which reload it.
         */
        public Builder canisters(int count, ResourceLocation mineId) {
            this.canisterCount = Math.max(0, count);
            this.canisterMineId = mineId;
            return this;
        }

        public Builder arc(double degrees) {
            this.arc = degrees;
            return this;
        }

        /** Ticks between sensor sweeps; 1 is every tick. */
        public Builder scanInterval(int ticks) {
            this.scanInterval = Math.max(1, ticks);
            return this;
        }

        /** Ticks after deployment before it goes live. 0 arms it the moment it is put down. */
        public Builder armDelay(int ticks) {
            this.armDelay = Math.max(0, ticks);
            return this;
        }

        /**
         * Deploy inert: it stays {@link State#SAFE} until {@link MineEntity#activate()} is called (which
         * right-clicking it does), and only then serves its {@link #armDelay}.
         */
        public Builder requiresActivation() {
            this.requiresActivation = true;
            return this;
        }

        /** Can be made safe again by hand. */
        public Builder defusable(DefuseMethod method) {
            this.defuseMethod = method == null ? DefuseMethod.NONE : method;
            return this;
        }

        /** As above, requiring a held item in {@code tool}; implies {@link DefuseMethod#TOOL}. */
        public Builder defusable(DefuseMethod method, TagKey<Item> tool) {
            this.defuseMethod = method == null ? DefuseMethod.NONE : method;
            this.defuseTool = tool;
            return this;
        }

        /** The item tag a {@link DefuseMethod#TOOL} defusal needs in hand. */
        public Builder defuseTool(TagKey<Item> tool) {
            this.defuseTool = tool;
            return this;
        }

        /** Ticks of uninterrupted work a defusal takes. */
        public Builder defuseTicks(int ticks) {
            this.defuseTicks = Math.max(1, ticks);
            return this;
        }

        /** Chance (0..1) a completed defusal sets the mine off instead. 0 (default) is a safe job. */
        public Builder defuseFailChance(float chance) {
            this.defuseFailChance = Mth.clamp(chance, 0.0f, 1.0f);
            return this;
        }

        /** Cannot be defused at all. */
        public Builder undefusable() {
            this.defuseMethod = DefuseMethod.NONE;
            return this;
        }

        /** Rides a fluid instead of sinking through it: a naval mine. */
        public Builder floats() {
            return floats(0.0);
        }

        /** Rides a fluid at {@code draught} blocks below its surface. */
        public Builder floats(double draught) {
            this.floats = true;
            this.draught = Math.max(0.0, draught);
            return this;
        }

        /** Destroys itself {@code ticks} after it is deployed. */
        public Builder selfDestructs(int ticks) {
            this.selfDestructTicks = Math.max(0, ticks);
            return this;
        }

        /** Only live while it is in a fluid. */
        public Builder armsOnlyInWater() {
            this.armsOnlyInWater = true;
            return this;
        }

        /** Can be dug into soft ground with a shovel, sinking most of its body below the surface. */
        public Builder buriable() {
            this.buriable = true;
            return this;
        }

        /** Turns over in the air rather than staying upright, and lies where it lands. */
        public Builder tumbles() {
            return tumble(DEFAULT_TUMBLE);
        }

        /** As {@link #tumbles()}, at a chosen rate in degrees per tick per axis. 0 disables it. */
        public Builder tumble(float degreesPerTick) {
            this.tumble = Math.max(0.0f, degreesPerTick);
            return this;
        }

        /**
         * How far off flat (degrees, per axis) a mine that has tumbled may come to rest: the difference between a
         * field that looks scattered and one that looks placed.
         */
        public Builder restTilt(float degrees) {
            this.restTilt = Mth.clamp(degrees, 0.0f, 89.0f);
            return this;
        }

        /** How high to throw the body before the warhead fires; 0 (the default) goes off where it lies. */
        public Builder bounceHeight(double blocks) {
            this.bounceHeight = Math.max(0.0, blocks);
            return this;
        }

        /** The WarForge faction the blast is attributed to (see {@link WarheadCarrier#igniterFactionId}). */
        public Builder teamId(UUID teamId) {
            this.teamId = teamId;
            return this;
        }

        public Builder ownerId(UUID ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        /** The preset this was built from, so a defused mine gives back the right item. */
        public Builder preset(ResourceLocation presetId) {
            this.presetId = presetId;
            return this;
        }

        /** Which way it faces, which is what a {@code directional} fuse watches and fires along. */
        public Builder facing(float yaw) {
            this.yaw = yaw;
            return this;
        }

        public MineEntity build() {
            MineEntity mine = new MineEntity(this.type, this.level);

            mine.entityData.set(MODEL_ID, this.modelId.toString());
            Vec3 modelSize = MineModels.size(this.modelId);
            mine.setSize(this.width != null ? this.width : modelSize.x,
                    this.height != null ? this.height : modelSize.y);

            mine.detonationId = this.detonationId;
            mine.detonation = WarheadRegistry.get(this.detonationId);
            mine.triggerId = this.triggerId;
            mine.trigger = this.trigger != null ? this.trigger : MineTriggers.get(this.triggerId);
            mine.fragmentCount = this.fragmentCount;
            mine.blastDirection = this.blastDirection;
            mine.blastAlongFacing = this.blastAlongFacing;
            mine.blastHalfAngle = this.blastHalfAngle;

            double range = this.triggerRange != null ? this.triggerRange : DEFAULT_TRIGGER_RANGE;
            mine.triggerRange = range;
            mine.sneakTriggerRange = this.sneakTriggerRange != null
                    ? Math.min(this.sneakTriggerRange, range) : range * DEFAULT_SNEAK_FACTOR;
            mine.arc = this.arc;
            mine.scanInterval = this.scanInterval;

            mine.armDelay = this.armDelay;
            mine.requiresActivation = this.requiresActivation;
            mine.setState(this.requiresActivation ? State.SAFE
                    : (this.armDelay > 0 ? State.ARMING : State.ARMED));

            mine.defuseMethod = this.defuseMethod;
            mine.defuseTool = this.defuseTool;
            mine.defuseTicks = this.defuseTicks;
            mine.defuseFailChance = this.defuseFailChance;

            mine.floats = this.floats;
            mine.draught = this.draught;
            mine.selfDestructTicks = this.selfDestructTicks;
            mine.armsOnlyInWater = this.armsOnlyInWater;
            mine.buriable = this.buriable;
            mine.entityData.set(BURIABLE, this.buriable);
            mine.bounceHeight = this.bounceHeight;
            mine.tumble = this.tumble;
            mine.restTilt = this.restTilt;

            mine.entityData.set(CANISTER_CAPACITY, (byte) Math.min(Byte.MAX_VALUE, this.canisterCount));
            mine.entityData.set(CANISTERS, (byte) Math.min(Byte.MAX_VALUE, this.canisterCount));
            mine.entityData.set(CANISTER_MINE,
                    this.canisterMineId == null ? "" : this.canisterMineId.toString());

            mine.teamId = this.teamId;
            mine.ownerId = this.ownerId;
            mine.presetId = this.presetId;
            mine.entityData.set(PRESET, this.presetId == null ? "" : this.presetId.toString());
            mine.setYRot(this.yaw);
            mine.yRotO = this.yaw;
            return mine;
        }
    }
}
