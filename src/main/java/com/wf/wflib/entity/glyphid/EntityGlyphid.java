package com.wf.wflib.entity.glyphid;

import com.wf.wflib.ModEntities;
import com.wf.wflib.WFLib;
import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.standard.BlockAllocatorGlyphidDig;
import com.wf.wflib.aef.standard.BlockProcessorStandard;
import com.wf.wflib.config.WFConfig;
import com.wf.wflib.colony.GlyphidObjective;
import com.wf.wflib.damage.DynamicResistance;
import com.wf.wflib.damage.WFDamageTypes;
import com.wf.wflib.debug.ProfiledGoal;
import com.wf.wflib.debug.SwarmBench;
import com.wf.wflib.debug.SwarmProfiler;
import com.wf.wflib.drone.flight.FlightAttitude;
import com.wf.wflib.drone.flight.Multirotor;
import com.wf.wflib.entity.glyphid.ai.GlyphidBombGoal;
import com.wf.wflib.entity.glyphid.ai.GlyphidBrainGoal;
import com.wf.wflib.entity.glyphid.ai.GlyphidFlightGoal;
import com.wf.wflib.entity.glyphid.ai.GlyphidTargetGoal;
import com.wf.wflib.entity.glyphid.brain.GlyphidBody;
import com.wf.wflib.entity.glyphid.brain.GlyphidCarrier;
import com.wf.wflib.entity.glyphid.brain.GlyphidMind;
import com.wf.wflib.entity.glyphid.brain.GlyphidPlan;
import com.wf.wflib.entity.glyphid.brain.GlyphidSnapshot;
import com.wf.wflib.entity.glyphid.flight.GlyphidFlight;
import com.wf.wflib.entity.glyphid.ai.GlyphidWanderGoal;
import com.wf.wflib.entity.glyphid.nav.GlyphidBridge;
import com.wf.wflib.entity.glyphid.nav.GlyphidBridges;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import com.wf.wflib.block.GlyphidNestBlock;
import com.wf.wflib.block.GlyphidSpawnerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** The base glyphid: an armoured, wall-climbing, terrain-chewing swarm mob. */
public class EntityGlyphid extends Monster implements DynamicResistance, GlyphidCarrier {

    public static final byte TYPE_NORMAL = 0;
    public static final byte TYPE_INFECTED = 1;
    public static final byte TYPE_RADIOACTIVE = 2;

    /** All five plates intact. */
    public static final byte FULL_ARMOR = 0b11111;

    private static final EntityDataAccessor<Boolean> DW_WALL =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> DW_ARMOR =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DW_SUBTYPE =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);

    /** Wings, packed: bit 0 is "has them", bit 1 is "using them right now". */
    private static final EntityDataAccessor<Byte> DW_FLIGHT =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);
    /** Lean, quantised for the wire as {@code DroneEntity} does. Only written while airborne. */
    private static final EntityDataAccessor<Byte> DW_ROLL =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DW_PITCH =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);

    public static final byte FLIGHT_CAPABLE = 0b01;
    public static final byte FLIGHT_AIRBORNE = 0b10;

    /** Radians per unit of the quantised lean, matching {@code DroneEntity.TILT_QUANTUM}. */
    public static final float TILT_QUANTUM = 90.0F;

    /** Ticks between looking for something to push. See {@link #pushEntities}. */
    private static final int PUSH_INTERVAL = 4;

    /** How far a glyphid notices non-players. Short: a colony crosses the map for what provoked it. */
    private static final double PREY_RANGE = 24.0;

    /** What a water cell costs the pathfinder, against a vanilla default of 8. */
    private static final float WATER_MALUS = 0.0F;

    /** Ticks a submerged glyphid lasts, against a vanilla 300. */
    private static final int MAX_AIR = 600;

    public boolean hasHome = false;
    public int homeX;
    public int homeY;
    public int homeZ;

    /** This bug is a nest's standing defence, and the colony at {@link #homeX}/{@link #homeZ} is counting it. */
    public boolean garrison = false;

    public int taskX;
    public int taskY;
    public int taskZ;

    /** Where this glyphid was headed before a squad sent it somewhere else. */
    public boolean hasRally;
    public int rallyX;
    public int rallyY;
    public int rallyZ;

    protected int currentTask = GlyphidTasks.TASK_IDLE;
    protected int previousTask;
    protected @Nullable GlyphidWaypoint previousWaypoint;
    protected boolean hasWaypoint = false;
    protected @Nullable GlyphidWaypoint taskWaypoint = null;

    public int blastSize = blastSize(getGlyphidScale());

    /** Where the flight goal wants this glyphid to be. Null while walking. */
    protected @Nullable Vec3 flightTarget;
    /** True while descending onto {@link #flightTarget} rather than cruising toward it. */
    protected boolean landing;
    /** Current lean and thrust. Server-side truth; the quantised copy on the wire is what the client draws. */
    protected FlightAttitude attitude = FlightAttitude.LEVEL;
    /** Ordnance left to drop. Finite, so a bombing run is a raid rather than a permanent siege. */
    protected int bombs;

    /** AI memory. On the entity rather than a goal so a record can run the same behaviour; see
     * {@link GlyphidCarrier}. */
    private final GlyphidMind mind = new GlyphidMind();

    /** Server-side mirrors of the two synched flags rewritten every tick. See {@link #setAggressive}. */
    private boolean climbableFlag;
    private boolean aggressiveFlag;

    /** The same for the frozen-tick counter, which vanilla rewrites every tick from {@code aiStep}. */
    private int frozenTicksMirror;

    /** Whether every equipment slot has only ever held an empty stack. See {@link #setItemSlot}. */
    private boolean equipmentEmpty = true;

    /** The bridge this glyphid is walking out to join or holding still in, and the slot it has on it. */
    private @Nullable GlyphidBridge bridge;
    private @Nullable GlyphidBridge.Slot bridgeSlot;
    /** Tick the slot was claimed on, so one that cannot reach its slot gives it back rather than blocking. */
    private int bridgeClaimedAt;
    /** True once in place: no AI, no gravity, and a surface the rest of the swarm can walk over. */
    private boolean anchored;

    /** Which squad this glyphid is in and what it was sent to do. */
    public int squad;
    public @Nullable GlyphidObjective objective;

    public EntityGlyphid(EntityType<? extends EntityGlyphid> type, Level level) {
        super(type, level);
        setPathfindingMalus(PathType.WATER, WATER_MALUS);
        setPathfindingMalus(PathType.WATER_BORDER, WATER_MALUS);
        applyEntityAttributes();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, GlyphidStats.getStats().getGrunt().health())
                .add(Attributes.MOVEMENT_SPEED, GlyphidStats.getStats().getGrunt().movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, GlyphidStats.getStats().getGrunt().damage())
                .add(Attributes.WATER_MOVEMENT_EFFICIENCY, 1.0D);
    }

    /** How wide a bite this caste takes. From body scale, so a bigger caste digs faster for free. */
    public static int blastSize(double scale) {
        return Math.min((int) (3 * scale) / 2, 5);
    }

    public ResourceLocation getSkin() {
        return GlyphidCaste.byType(getType()).skin();
    }

    public double getGlyphidScale() {
        return GlyphidCaste.byType(getType()).scale();
    }

    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getGrunt();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DW_WALL, false);
        builder.define(DW_ARMOR, FULL_ARMOR);
        builder.define(DW_SUBTYPE, TYPE_NORMAL);
        builder.define(DW_FLIGHT, (byte) 0);
        builder.define(DW_ROLL, (byte) 0);
        builder.define(DW_PITCH, (byte) 0);
    }

    protected void applyEntityAttributes() {
        byte variant = subtype();
        GlyphidStats.StatBundle stats = getStats();
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(stats.health());
        getAttribute(Attributes.MOVEMENT_SPEED)
                .setBaseValue(stats.movementSpeed() * (variant == TYPE_RADIOACTIVE ? 2D : 1D));
        getAttribute(Attributes.ATTACK_DAMAGE)
                .setBaseValue(stats.damage() * (variant == TYPE_RADIOACTIVE ? 5D : 1D));
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        if (SwarmBench.vanillaMeleeGoal) {
            goalSelector.addGoal(2, new ProfiledGoal(new MeleeAttackGoal(this, 1.0D, true),
                    SwarmProfiler.Phase.PATH_MELEE));
        }
        goalSelector.addGoal(3, new GlyphidBrainGoal(this));
        // Takes no movement flag, so it runs alongside flight rather than instead of it.
        goalSelector.addGoal(2, new GlyphidBombGoal(this));
        // Flight outranks walking to the same place, and falls back to it for anything without wings.
        goalSelector.addGoal(4, new GlyphidFlightGoal(this));
        goalSelector.addGoal(4, new GlyphidWanderGoal(this, 1.0D));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new GlyphidTargetGoal(this));
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public void tick() {
        long t = SwarmProfiler.begin();
        super.tick();
        SwarmProfiler.end(SwarmProfiler.Phase.TICK, t);
    }

    // The AI bucket cannot be an override: Mob#serverAiStep is final. It is taken in MixinMob instead.

    /**
     * Glyphids do not shove each other, and only look for anything else to shove every {@link #PUSH_INTERVAL}
     * ticks.
     */
    @Override
    protected void pushEntities() {
        long t = SwarmProfiler.begin();
        if (SwarmBench.vanillaPush) {
            super.pushEntities();
            SwarmProfiler.end(SwarmProfiler.Phase.PUSH, t);
            return;
        }
        // Staggered by entity id so the whole swarm does not search on the same tick.
        if (!level().isClientSide && (tickCount + getId()) % PUSH_INTERVAL == 0) {
            Predicate<Entity> pushable = EntitySelector.pushableBy(this);
            List<Entity> nearby = level().getEntities(this, getBoundingBox(),
                    other -> !(other instanceof EntityGlyphid) && pushable.test(other));
            for (Entity other : nearby) {
                doPush(other);
            }
        }
        SwarmProfiler.end(SwarmProfiler.Phase.PUSH, t);
    }

    @Override
    public void travel(Vec3 travelVector) {
        long t = SwarmProfiler.begin();
        if (isAirborne()) {
            fly();
        } else {
            super.travel(travelVector);
        }
        SwarmProfiler.end(SwarmProfiler.Phase.MOVE, t);
    }

    /** Vanilla walks every block the hitbox overlaps. */
    @Override
    protected void checkInsideBlocks() {
        long t = SwarmProfiler.begin();
        super.checkInsideBlocks();
        SwarmProfiler.end(SwarmProfiler.Phase.INSIDE, t);
    }

    /** Fire, air, effects, portals, freezing. Was the largest unattributed block in the report. */
    @Override
    public void baseTick() {
        long t = SwarmProfiler.begin();
        super.baseTick();
        SwarmProfiler.end(SwarmProfiler.Phase.BASE, t);
    }

    @Override
    protected boolean updateInWaterStateAndDoFluidPushing() {
        long t = SwarmProfiler.begin();
        boolean inFluid = super.updateInWaterStateAndDoFluidPushing();
        SwarmProfiler.end(SwarmProfiler.Phase.FLUID, t);
        return inFluid;
    }

    /** Mirrors the frozen-tick counter so the common no-op write never reaches the synched data. */
    @Override
    public void setTicksFrozen(int ticks) {
        if (!level().isClientSide && ticks == frozenTicksMirror) {
            return;
        }
        frozenTicksMirror = ticks;
        super.setTicksFrozen(ticks);
    }

    /**
     * Notes the first time this glyphid is given anything to wear, so the equipment scan can be skipped until then.
     */
    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        super.setItemSlot(slot, stack);
        if (!stack.isEmpty()) {
            equipmentEmpty = false;
        }
    }

    /**
     * @return true while every equipment slot has only ever held {@link ItemStack#EMPTY}.
     * @see com.wf.wflib.mixin.MixinLivingEntity
     */
    public boolean equipmentEmpty() {
        return equipmentEmpty;
    }

    // --- flight ---

    public boolean canFly() {
        return (entityData.get(DW_FLIGHT) & FLIGHT_CAPABLE) != 0;
    }

    public void setCanFly(boolean value) {
        byte flags = entityData.get(DW_FLIGHT);
        entityData.set(DW_FLIGHT, (byte) (value ? flags | FLIGHT_CAPABLE : flags & ~FLIGHT_CAPABLE));
    }

    public boolean isAirborne() {
        return (entityData.get(DW_FLIGHT) & FLIGHT_AIRBORNE) != 0;
    }

    /** Take off or land. */
    public void setAirborne(boolean value) {
        if (value && !canFly()) {
            return;
        }
        byte flags = entityData.get(DW_FLIGHT);
        entityData.set(DW_FLIGHT, (byte) (value ? flags | FLIGHT_AIRBORNE : flags & ~FLIGHT_AIRBORNE));
        setNoGravity(value);
        if (value) {
            getNavigation().stop();
        } else {
            attitude = FlightAttitude.LEVEL;
            publishAttitude();
        }
    }

    public int bombs() {
        return bombs;
    }

    public void spendBomb() {
        bombs = Math.max(0, bombs - 1);
    }

    /** Load this glyphid up. Called when a flight materialises; a glyphid that walked to the fight carries none. */
    public void setBombs(int count) {
        bombs = Math.max(0, count);
    }

    /** Whether this caste drops blasts rather than acid. The common flyer carries acid. */
    public boolean dropsExplosives() {
        return false;
    }

    public void setFlightTarget(@Nullable Vec3 target, boolean landing) {
        this.flightTarget = target;
        this.landing = landing;
    }

    /** One tick of flight, replacing the walking physics entirely. */
    protected void fly() {
        Vec3 target = flightTarget;
        if (target == null) {
            target = position();
        }

        Vec3 desired = landing
                ? GlyphidFlight.descentVelocity(position(), target)
                : GlyphidFlight.desiredVelocity(position(), target, floorHeight(target), cruiseSpeed());

        Multirotor.Step step = Multirotor.step(getDeltaMovement(), attitude, desired, GlyphidFlight.WINGS, 1.0);
        attitude = step.attitude();
        publishAttitude();

        setDeltaMovement(step.velocity());
        move(MoverType.SELF, getDeltaMovement());

        if (horizontalCollision && !landing) {
            setDeltaMovement(getDeltaMovement().add(0.0, GlyphidFlight.WINGS.maxClimbRate() * 0.5, 0.0));
            unstick();
        }
        // Nothing in the air is falling, and a glyphid that flew down a cliff should not land hurt.
        resetFallDistance();

        // Face the way it is going, so the lean reads as banking into a turn rather than sliding.
        double speedSq = getDeltaMovement().horizontalDistanceSqr();
        if (speedSq > 1.0E-4) {
            setYRot((float) (Mth.atan2(getDeltaMovement().z, getDeltaMovement().x) * (180.0 / Math.PI)) - 90.0F);
            yBodyRot = getYRot();
        }
    }

    /**
     * Lift a glyphid that has ended up inside terrain back to open air: the one state flight cannot recover from,
     * since every axis is blocked.
     */
    private void unstick() {
        if (level().noCollision(this)) {
            return;
        }
        int surface = surfaceAt(getBlockX(), getBlockZ());
        setPos(getX(), surface + GlyphidFlight.CLEARANCE, getZ());
        setDeltaMovement(0.0, 0.0, 0.0);
        attitude = FlightAttitude.LEVEL;
    }

    /** How fast this caste cruises. Scaled by body size so a bigger bug is not simply a slower one. */
    public double cruiseSpeed() {
        return GlyphidFlight.CRUISE_SPEED / Math.max(0.5, getGlyphidScale());
    }

    /** The ground to hold clearance above: the highest of what is under and ahead, so it climbs early. */
    protected int floorHeight(Vec3 target) {
        int highest = surfaceAt(getBlockX(), getBlockZ());
        double dx = target.x - getX();
        double dz = target.z - getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) {
            return highest;
        }

        double reach = Math.min(distance, GlyphidFlight.LOOKAHEAD);
        for (int i = 1; i <= GlyphidFlight.LOOKAHEAD_SAMPLES; i++) {
            double step = reach * i / GlyphidFlight.LOOKAHEAD_SAMPLES;
            int x = Mth.floor(getX() + dx / distance * step);
            int z = Mth.floor(getZ() + dz / distance * step);
            highest = Math.max(highest, surfaceAt(x, z));
        }
        return highest;
    }

    /**
     * @return the surface height of a column, or this glyphid's own altitude where the chunk is not loaded,
     *      which keeps it holding station rather than diving at terrain nobody has generated.
     */
    protected int surfaceAt(int x, int z) {
        if (!level().hasChunk(x >> 4, z >> 4)) {
            return getBlockY();
        }
        return level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private void publishAttitude() {
        float yaw = (float) ((getYRot() + 90.0F) * (Math.PI / 180.0));
        entityData.set(DW_ROLL, quantise(attitude.roll(yaw)));
        entityData.set(DW_PITCH, quantise(attitude.pitch(yaw)));
    }

    private static byte quantise(double radians) {
        return (byte) Math.max(-127, Math.min(127, Math.round(radians * TILT_QUANTUM)));
    }

    /**
     * @return lean about the nose axis, radians, as the client sees it.
     */
    public float getRoll() {
        return entityData.get(DW_ROLL) / TILT_QUANTUM;
    }

    /**
     * @return lean about the wing axis, radians: positive is nose down.
     */
    public float getPitch() {
        return entityData.get(DW_PITCH) / TILT_QUANTUM;
    }

    // --- damage ---

    /** The damage threshold and resistance this bug presents, as {@code [threshold, resistance]}. */
    @Override
    public float[] currentDTDR(DamageSource damage) {
        if (damage.is(DamageTypeTags.BYPASSES_ARMOR)) return new float[]{0F, 0F};

        GlyphidStats.StatBundle stats = getStats();
        float threshold = stats.thresholdMultForArmor() * getGlyphidArmor() / 5F;

        if (damage.is(WFDamageTypes.LASER)) return new float[]{threshold * 0.5F, stats.resistanceMult() * 0.5F};
        if (damage.is(WFDamageTypes.ELECTRIC)) return new float[]{threshold * 0.25F, stats.resistanceMult() * 0.25F};
        if (isFireDamage(damage)) return new float[]{0F, stats.resistanceMult() * 0.2F};
        if (isExplosionDamage(damage)) return new float[]{threshold * 0.5F, stats.resistanceMult() * 0.35F};

        return new float[]{threshold, stats.resistanceMult()};
    }

    /** A landed hit may knock a plate off, lowering the threshold {@link #currentDTDR} reports from here on. */
    public void onDamageDealt(DamageSource damage, float amount) {
        if (isArmorBroken(amount)) breakOffArmor();
    }

    public static boolean isFireDamage(DamageSource source) {
        return source.is(DamageTypeTags.IS_FIRE) || source.is(WFDamageTypes.FIRE);
    }

    public static boolean isExplosionDamage(DamageSource source) {
        return source.is(DamageTypeTags.IS_EXPLOSION) || source.is(WFDamageTypes.EXPLOSIVE);
    }

    /** Marks a block a glyphid threw, so the swarm's own siege engineering cannot cut it down. */
    public static final String RUBBLE_TAG = "wflib_glyphid_rubble";

    @Override
    public boolean hurt(DamageSource source, float amount) {
        Entity attacker = source.getEntity();
        if (attacker instanceof EntityGlyphid || (attacker != null && attacker.getTags().contains(RUBBLE_TAG))) {
            return false;
        }

        boolean landed = GlyphidStats.getStats().handleAttack(this, source, amount);
        // On the raw amount, not what got through. Server-side only: the client mirrors the bitmask.
        if (landed && !level().isClientSide) {
            onDamageDealt(source, amount);
        }
        return landed;
    }

    /** The normal outcome of a hit, for {@link GlyphidStats} implementations to finish on. */
    public boolean attackSuperclass(DamageSource source, float amount) {
        return super.hurt(source, amount);
    }

    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.6, 2), 100);
    }

    /** Knock a random surviving plate off. */
    public void breakOffArmor() {
        byte armorValue = armor();
        List<Integer> indices = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        Collections.shuffle(indices);

        for (int i : indices) {
            byte bit = (byte) (1 << i);
            if ((armorValue & bit) > 0) {
                armorValue &= ~bit;
                armorValue = (byte) (armorValue & FULL_ARMOR);
                entityData.set(DW_ARMOR, armorValue);
                playSound(SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, 1.0F, 1.25F);
                break;
            }
        }
    }

    /**
     * @return how many of the five plates are still on.
     */
    public int getGlyphidArmor() {
        return Integer.bitCount(armor() & FULL_ARMOR);
    }

    /** Put a plate pattern back, for a body rebuilt from a {@code SimGlyphid}. Not a gameplay setter. */
    public void setArmor(byte plates) {
        entityData.set(DW_ARMOR, plates);
    }

    public byte armor() {
        return entityData.get(DW_ARMOR);
    }

    public byte subtype() {
        return entityData.get(DW_SUBTYPE);
    }

    public void setSubtype(byte subtype) {
        entityData.set(DW_SUBTYPE, subtype);
        applyEntityAttributes();
    }

    // --- behaviour ---

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        if (!hasHome) {
            homeX = (int) getX();
            homeY = (int) getY();
            homeZ = (int) getZ();
            hasHome = true;
        }

        if (hasEffect(MobEffects.BLINDNESS)) {
            onBlinded();
        }

        if (isAirborne() && (getCurrentTask() != GlyphidTasks.TASK_FOLLOW || isInWater())) {
            setAirborne(false);
        }

        if (getCurrentTask() == GlyphidTasks.TASK_FOLLOW) {
            if (isAtDestination() && !hasWaypoint) {
                setCurrentTask(GlyphidTasks.TASK_IDLE, null);
            }
        } else if (getCurrentTask() == GlyphidTasks.TASK_DIG
                && tickCount % GlyphidTasks.DIG_EXPLOSION_INTERVAL_TICKS == 0
                && isAtDestination()) {
            dig();
        }

        setBesideClimbableBlock(horizontalCollision);

        if (tickCount % 100 == 0) {
            swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Take a bite out of the dig site, then go back to whatever this bug was doing before. */
    protected void dig() {
        digAt(taskX, taskY + 2, taskZ);
        setCurrentTask(previousTask, previousWaypoint);
    }

    /** Chew one bite, leaving orders alone, so something blocked on its way can bite through. */
    public void digAt(int x, int y, int z) {
        swing(InteractionHand.MAIN_HAND);

        long t = SwarmProfiler.begin();
        long pathBefore = SwarmProfiler.accrued(SwarmProfiler.Phase.PATH);
        ExplosionAEF blast = new ExplosionAEF(level(), x, y, z, blastSize, this);
        blast.setBlockAllocator(new BlockAllocatorGlyphidDig(getStats().digCeiling(), EntityGlyphid::isSpawnerBlock));
        blast.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
        blast.setEntityProcessor(null);
        blast.setPlayerProcessor(null);
        blast.explode();
        SwarmProfiler.endExcluding(SwarmProfiler.Phase.DIG, t, SwarmProfiler.Phase.PATH, pathBefore);
    }

    /**
     * What this glyphid would attack if it looked right now: players first and furthest, then anything alive and
     * worth biting: livestock and villagers included, since a colony that walked past a farm would not read as an
     * infestation.
     */
    public @Nullable LivingEntity findTargetCandidate() {
        if (hasEffect(MobEffects.BLINDNESS)) return null;

        double radius = useExtendedTargeting() ? 128D : 16D;

        LivingEntity assigned = assignedTarget(radius);
        if (assigned != null) {
            return assigned;
        }

        Player player = level().getNearestPlayer(getX(), getY(), getZ(), radius, false);
        if (player != null) {
            return player;
        }
        return nearestPrey(Math.min(radius, PREY_RANGE));
    }

    /**
     * @return the player this glyphid's squad was pointed at, if they are alive and in range.
     */
    private @Nullable LivingEntity assignedTarget(double radius) {
        if (objective == null || objective.player() == null || !(level() instanceof ServerLevel server)) {
            return null;
        }
        if (!(server.getEntity(objective.player()) instanceof LivingEntity assigned)) {
            return null;
        }
        return assigned.isAlive() && distanceToSqr(assigned) <= radius * radius ? assigned : null;
    }

    /**
     * @return the nearest non-player thing worth attacking, or null.
     */
    protected @Nullable LivingEntity nearestPrey(double radius) {
        AABB box = getBoundingBox().inflate(radius);

        Iterable<LivingEntity> candidates = PreyTracker.worthScanning(level())
                ? PreyTracker.prey(level())
                : level().getEntitiesOfClass(LivingEntity.class, box, EntityGlyphid::isPrey);

        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            if (!candidate.getBoundingBox().intersects(box) || !isPrey(candidate)) {
                continue;
            }
            double distSq = candidate.distanceToSqr(this);
            if (distSq < bestSq) {
                bestSq = distSq;
                best = candidate;
            }
        }
        return best;
    }

    public static boolean isPrey(LivingEntity entity) {
        return entity.isAlive()
                && entity.attackable()
                && !(entity instanceof EntityGlyphid)
                && !(entity instanceof Monster);
    }

    /** Whether this bug hunts across the map. Config only; there is no pollution system to switch it on. */
    public boolean useExtendedTargeting() {
        return WFConfig.GLYPHID_EXTENDED_TARGETING.get();
    }

    public boolean canDig() {
        return WFConfig.GLYPHID_DIG.get();
    }

    /** Blind glyphids stop hunting; the big castes lash out at the light source that did it. */
    public void onBlinded() {
        setTarget(null);
        getNavigation().stop();

        if (getGlyphidScale() < 1.25 || tickCount % 20 != 0) {
            return;
        }

        for (int i = 0; i < 16; i++) {
            float angle = (float) Math.toRadians(360D / 16 * i);
            Vec3 rot = new Vec3(0, 0, 4).yRot(angle);
            Vec3 from = new Vec3(getX(), getY() + 1, getZ());
            Vec3 to = new Vec3(getX() + rot.x, getY() + 1, getZ() + rot.z);

            ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, CollisionContext.empty());
            BlockHitResult hit = level().clip(context);

            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = hit.getBlockPos();
                Block block = level().getBlockState(pos).getBlock();

                if (isLanternBlock(block)) {
                    setYRot(360F / 16 * i);
                    swing(InteractionHand.MAIN_HAND);
                    level().destroyBlock(pos, false, this);
                }
            }
        }
    }

    public boolean isLanternBlock(Block block) {
        return block == Blocks.LANTERN || block == Blocks.SOUL_LANTERN;
    }

    /** Blocks the colony will not chew through, whatever their resistance: its own mound. */
    public static boolean isSpawnerBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof GlyphidSpawnerBlock || block instanceof GlyphidNestBlock;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return getTarget() == null && getCurrentTask() == GlyphidTasks.TASK_IDLE && tickCount > 100;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);

        int looting = 0;
        if (damageSource.getEntity() instanceof LivingEntity attacker) {
            Holder<Enchantment> lootingEnchant = level.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.LOOTING);
            looting = EnchantmentHelper.getEnchantmentLevel(lootingEnchant, attacker);
        }

        dropGlyphidLoot(level, looting);
    }

    /** Caste-specific drops. Empty until the glyphid item set lands with the hive port. */
    protected void dropGlyphidLoot(ServerLevel level, int looting) {
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        swing(InteractionHand.MAIN_HAND);

        if (subtype() == TYPE_INFECTED && target instanceof LivingEntity livingTarget) {
            livingTarget.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 2));
            livingTarget.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0));
        }

        return super.doHurtTarget(target);
    }

    // --- brain ---

    /** Speed every walk is issued at. On the body: a plan says where to go, the body says how fast. */
    public double aiSpeed() {
        return 1.0D;
    }

    @Override
    public int carrierId() {
        return getId();
    }

    @Override
    public boolean carrierAlive() {
        return isAlive();
    }

    @Override
    public GlyphidMind mind() {
        return mind;
    }

    @Override
    public double carrierX() {
        return getX();
    }

    @Override
    public double carrierY() {
        return getY();
    }

    @Override
    public double carrierZ() {
        return getZ();
    }

    @Override
    public double carrierWidth() {
        return getBbWidth();
    }

    @Override
    public boolean carrierPushable() {
        return isAlive() && !isPassenger() && !isAirborne() && !anchored;
    }

    @Override
    public void carrierPush(double dx, double dz) {
        push(dx, 0.0, dz);
    }

    /** How near the slot centre a glyphid has to get before it sits down. */
    private static final double ANCHOR_REACH = 0.5;
    /** Ticks to reach a claimed slot before giving it back, so one that cannot get there blocks nothing. */
    private static final int ANCHOR_WALK_TICKS = 100;
    /** How far below the deck a recruit has to be for its walk to count as a fall rather than a detour. */
    private static final double ANCHOR_FALL = 4.0;

    public @Nullable GlyphidBridge bridge() {
        return bridge;
    }

    public boolean isAnchored() {
        return anchored;
    }

    /** Whether this glyphid is doing bridge work, either walking out to a slot or holding one. */
    public boolean hasBridgeSlot() {
        return bridgeSlot != null;
    }

    /** Take a slot offered by {@link GlyphidBridges#recruit}. */
    public void takeBridgeSlot(GlyphidBridge bridge, GlyphidBridge.Slot slot) {
        this.bridge = bridge;
        this.bridgeSlot = slot;
        this.bridgeClaimedAt = tickCount;
        GlyphidBody.clearCracks(this, mind);
        mind.stopChewing();
        getNavigation().stop();
    }

    /** Stop being part of a bridge, whether seated or still walking out. */
    public void dropBridgeSlot() {
        if (anchored) {
            setNoGravity(false);
            anchored = false;
        }
        bridge = null;
        bridgeSlot = null;
    }

    /**
     * One tick of being part of a bridge: walk out to the slot, then hold it.
     *
     * @return true while this glyphid is on bridge duty and the brain should be left alone
     */
    public boolean tickBridgeSlot(ServerLevel level) {
        GlyphidBridge span = bridge;
        GlyphidBridge.Slot slot = bridgeSlot;
        if (span == null || slot == null) {
            return false;
        }
        if (slot.occupantId() != getId()) {
            // The claim timed out and somebody nearer took the slot.
            dropBridgeSlot();
            return false;
        }

        double x = slot.x + 0.5;
        double z = slot.z + 0.5;
        double y = span.deckY - getBbHeight();

        if (anchored) {
            if (distanceToSqr(x, y, z) > 1.0E-6) {
                setPos(x, y, z);
            }
            setDeltaMovement(Vec3.ZERO);
            return true;
        }

        int[] approach = span.approach(slot);
        double dx = approach[0] + 0.5 - getX();
        double dz = approach[1] + 0.5 - getZ();
        if (dx * dx + dz * dz <= ANCHOR_REACH * ANCHOR_REACH) {
            anchored = true;
            getNavigation().stop();
            setNoGravity(true);
            setPos(x, y, z);
            setDeltaMovement(Vec3.ZERO);
            GlyphidBridges.seat(level, span, slot, getBoundingBox());
            return true;
        }
        if (tickCount - bridgeClaimedAt > ANCHOR_WALK_TICKS || getY() < span.deckY - ANCHOR_FALL) {
            GlyphidBridges.release(level, this);
            return false;
        }
        getMoveControl().setWantedPosition(approach[0] + 0.5, span.deckY, approach[1] + 0.5, aiSpeed());
        return true;
    }

    /** An anchor is a floor. Nothing else about a glyphid is solid, and nothing else here returns true. */
    @Override
    public boolean canBeCollidedWith() {
        return anchored;
    }

    @Override
    public boolean isPushable() {
        return !anchored && super.isPushable();
    }

    /**
     * A glyphid killed mid-bite leaves its cracks on the block, and the client only expires an abandoned overlay
     * after twenty seconds.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide) {
            GlyphidBody.clearCracks(this, mind);
            if (bridgeSlot != null && level() instanceof ServerLevel server) {
                // Takes the rest of the span with it: the deck past a hole cannot be reached.
                GlyphidBridges.release(server, this);
            }
        }
        super.remove(reason);
    }

    @Override
    public GlyphidSnapshot snapshot(ServerLevel level) {
        return GlyphidBody.snapshot(this);
    }

    @Override
    public void apply(ServerLevel level, GlyphidPlan plan) {
        GlyphidBody.apply(this, plan);
    }

    // --- colony tasks ---

    public int getCurrentTask() {
        return currentTask;
    }

    public @Nullable GlyphidWaypoint getWaypoint() {
        return taskWaypoint;
    }

    public void setCurrentTask(int task, @Nullable GlyphidWaypoint waypoint) {
        this.currentTask = task;
        this.taskWaypoint = waypoint;
        this.hasWaypoint = waypoint != null;

        if (taskWaypoint != null) {
            taskX = (int) taskWaypoint.getX();
            taskY = (int) taskWaypoint.getY();
            taskZ = (int) taskWaypoint.getZ();

            if (taskWaypoint.highPriority) {
                setTarget(null);
                getNavigation().stop();
            }
        }
        carryOutTask();
    }

    public void carryOutTask() {
        switch (getCurrentTask()) {
            case GlyphidTasks.TASK_RETREAT_FOR_REINFORCEMENTS -> {
                if (taskWaypoint != null) {
                    communicate(GlyphidTasks.TASK_FOLLOW, taskWaypoint);
                    setCurrentTask(GlyphidTasks.TASK_FOLLOW, taskWaypoint);
                }
            }
            case GlyphidTasks.TASK_INITIATE_RETREAT -> {
                if (level() instanceof ServerLevel serverLevel && taskWaypoint == null) {
                    initiateRetreat(serverLevel);
                }
            }
            default -> {
            }
        }
    }

    /** Drop a waypoint on home carrying a second one back here, so reinforcements know where the fight is. */
    private void initiateRetreat(ServerLevel serverLevel) {
        EntityType<GlyphidWaypoint> type = ModEntities.GLYPHID_WAYPOINT.get();

        GlyphidWaypoint additional = new GlyphidWaypoint(type, serverLevel);
        additional.moveTo(getX(), getY(), getZ(), 0F, 0F);

        GlyphidWaypoint home = new GlyphidWaypoint(type, serverLevel);
        home.setWaypointType(GlyphidTasks.TASK_RETREAT_FOR_REINFORCEMENTS);
        home.setAdditionalWaypoint(additional);
        home.setHighPriority();
        home.moveTo(homeX, homeY, homeZ, 0F, 0F);
        serverLevel.addFreshEntity(home);

        this.taskWaypoint = home;
        communicate(GlyphidTasks.TASK_FOLLOW, home);
        setCurrentTask(GlyphidTasks.TASK_FOLLOW, taskWaypoint);
    }

    /** Pass an order to every non-scout bug in the waypoint's radius. Scouts carry their own orders. */
    public void communicate(int task, @Nullable GlyphidWaypoint waypoint) {
        int radius = waypoint != null ? waypoint.radius : 4;
        AABB bb = new AABB(getX(), getY(), getZ(), getX(), getY(), getZ()).inflate(radius);

        List<Entity> bugs = level().getEntities(this, bb);
        for (Entity e : bugs) {
            if (e instanceof EntityGlyphid bug && !bug.isScoutType() && bug.getCurrentTask() != task) {
                bug.setCurrentTask(task, waypoint);
            }
        }
    }

    /** Pull the destination height onto real terrain once its column is loaded. */
    public void resolveTaskHeight() {
        if (!level().hasChunk(taskX >> 4, taskZ >> 4)) {
            return;
        }
        taskY = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, taskX, taskZ);
    }

    public boolean isAtDestination() {
        long thresholdSq = hasWaypoint && taskWaypoint != null
                ? (long) taskWaypoint.radius * taskWaypoint.radius
                : GlyphidTasks.DEFAULT_DESTINATION_RADIUS_SQ;
        double dx = taskX - getX();
        double dy = taskY - getY();
        double dz = taskZ - getZ();
        return (dx * dx + dy * dy + dz * dz) <= (double) thresholdSq;
    }

    public boolean expandHive() {
        return false;
    }

    public boolean isScoutType() {
        return false;
    }

    public boolean isNuclearType() {
        return false;
    }

    public boolean doesInfectedSpawnMaggots() {
        return true;
    }

    // --- movement quirks ---

    @Override
    protected void updateSwingTime() {
        int duration = swingDuration();

        if (swinging) {
            swingTime++;
            if (swingTime >= duration) {
                swingTime = 0;
                swinging = false;
            }
        } else {
            swingTime = 0;
        }

        attackAnim = (float) swingTime / (float) duration;
    }

    public int swingDuration() {
        return 15;
    }

    /** Glyphids are not slowed by cobwebs or soul sand: a swarm bogged down in terrain stops being a swarm. */
    @Override
    public void makeStuckInBlock(BlockState state, Vec3 motionMultiplier) {
    }

    @Override
    public int getMaxAirSupply() {
        return MAX_AIR;
    }

    /** Climbing is off in the air: vanilla clamps a climber's motion, so a brushed wall would drop it. */
    @Override
    public boolean onClimbable() {
        return !isAirborne() && isBesideClimbableBlock();
    }

    public boolean isBesideClimbableBlock() {
        return entityData.get(DW_WALL);
    }

    public void setBesideClimbableBlock(boolean climbable) {
        if (climbable == climbableFlag) {
            return;
        }
        climbableFlag = climbable;
        entityData.set(DW_WALL, climbable);
    }

    /**
     * Written through a mirror, so the common unchanged case costs a boolean compare instead of a trip through the
     * data table.
     */
    @Override
    public void setAggressive(boolean aggressive) {
        if (aggressive == aggressiveFlag) {
            return;
        }
        aggressiveFlag = aggressive;
        super.setAggressive(aggressive);
    }

    // --- persistence ---

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putByte("armor", armor());
        compound.putByte("subtype", subtype());
        // Only the wings are saved, not whether they were in use: a glyphid reloads on the ground.
        compound.putBoolean("canFly", canFly());
        compound.putInt("bombs", bombs);

        compound.putBoolean("hasHome", hasHome);
        compound.putBoolean("garrison", garrison);
        compound.putInt("homeX", homeX);
        compound.putInt("homeY", homeY);
        compound.putInt("homeZ", homeZ);

        compound.putBoolean("hasWaypoint", hasWaypoint);
        compound.putInt("taskX", taskX);
        compound.putInt("taskY", taskY);
        compound.putInt("taskZ", taskZ);

        compound.putBoolean("hasRally", hasRally);
        compound.putInt("rallyX", rallyX);
        compound.putInt("rallyY", rallyY);
        compound.putInt("rallyZ", rallyZ);

        compound.putInt("task", currentTask);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        entityData.set(DW_ARMOR, compound.contains("armor") ? compound.getByte("armor") : FULL_ARMOR);
        entityData.set(DW_SUBTYPE, compound.getByte("subtype"));
        setCanFly(compound.getBoolean("canFly"));
        setAirborne(false);
        bombs = compound.getInt("bombs");

        hasHome = compound.getBoolean("hasHome");
        garrison = compound.getBoolean("garrison");
        homeX = compound.getInt("homeX");
        homeY = compound.getInt("homeY");
        homeZ = compound.getInt("homeZ");

        hasWaypoint = compound.getBoolean("hasWaypoint");
        taskX = compound.getInt("taskX");
        taskY = compound.getInt("taskY");
        taskZ = compound.getInt("taskZ");

        hasRally = compound.getBoolean("hasRally");
        rallyX = compound.getInt("rallyX");
        rallyY = compound.getInt("rallyY");
        rallyZ = compound.getInt("rallyZ");

        currentTask = compound.getInt("task");
        applyEntityAttributes();
    }
}
