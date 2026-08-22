package com.wf.wfballistics.entity.glyphid;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorGlyphidDig;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.damage.WFDamageTypes;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import com.wf.wfballistics.drone.flight.Multirotor;
import com.wf.wfballistics.entity.glyphid.ai.GlyphidFlightGoal;
import com.wf.wfballistics.entity.glyphid.ai.GlyphidTargetGoal;
import com.wf.wfballistics.entity.glyphid.flight.GlyphidFlight;
import com.wf.wfballistics.entity.glyphid.ai.GlyphidTaskMoveGoal;
import com.wf.wfballistics.entity.glyphid.ai.GlyphidWanderGoal;
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
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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

/**
 * The base glyphid: an armoured, wall-climbing, terrain-chewing swarm mob.
 *
 * <p>Its armour is five plates held in a bitmask. Each surviving plate raises the damage threshold the bug
 * turns away, and a hard enough hit knocks one off, so a bug degrades from "bring a bigger gun" to "trivial"
 * as it is worn down rather than simply losing health.
 *
 * <p>On top of the usual target-and-chase it runs a colony task, which is what makes a group of them behave
 * like an infestation: bugs pass orders to neighbours through {@link #communicate}, retreat to fetch
 * reinforcements, and chew through anything between them and their waypoint.
 *
 * <p>This is the faithful port and it runs on stock vanilla AI: one goal stack and one A* per bug. That is
 * deliberate. It is the baseline the swarm work is measured against, which is why the tick is instrumented
 * for {@link SwarmProfiler} at every level the cost could hide in.
 */
public class EntityGlyphid extends Monster {

    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "textures/entity/glyphid.png");

    public static final byte TYPE_NORMAL = 0;
    public static final byte TYPE_INFECTED = 1;
    public static final byte TYPE_RADIOACTIVE = 2;

    /**
     * All five plates intact.
     */
    public static final byte FULL_ARMOR = 0b11111;

    private static final EntityDataAccessor<Boolean> DW_WALL =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> DW_ARMOR =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DW_SUBTYPE =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);

    /**
     * Wings, packed: bit 0 is "has them", bit 1 is "using them right now".
     *
     * <p>Flight is a modifier rather than a caste on purpose. A winged glyphid is a normal glyphid that can
     * also fly — it still walks, digs, climbs, takes orders and loses armour plates the same way — so making
     * it a subclass would fork every one of those behaviours to gain nothing.
     */
    private static final EntityDataAccessor<Byte> DW_FLIGHT =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);
    /**
     * Lean about the nose axis, quantised for the wire exactly as {@code DroneEntity} does it. Only ever
     * written while airborne, so a walking glyphid never sends either of these.
     */
    private static final EntityDataAccessor<Byte> DW_ROLL =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DW_PITCH =
            SynchedEntityData.defineId(EntityGlyphid.class, EntityDataSerializers.BYTE);

    public static final byte FLIGHT_CAPABLE = 0b01;
    public static final byte FLIGHT_AIRBORNE = 0b10;

    /**
     * Radians per unit of the quantised lean, matching {@code DroneEntity.TILT_QUANTUM}.
     */
    public static final float TILT_QUANTUM = 90.0F;

    public boolean hasHome = false;
    public int homeX;
    public int homeY;
    public int homeZ;

    public int taskX;
    public int taskY;
    public int taskZ;

    protected int currentTask = GlyphidTasks.TASK_IDLE;
    protected int previousTask;
    protected @Nullable GlyphidWaypoint previousWaypoint;
    protected boolean hasWaypoint = false;
    protected @Nullable GlyphidWaypoint taskWaypoint = null;

    public int blastSize = blastSize(getGlyphidScale());
    public int blastResToDig = blastResToDig(getGlyphidScale());

    /**
     * Where the flight goal wants this glyphid to be. Null while walking.
     */
    protected @Nullable Vec3 flightTarget;
    /**
     * True while descending onto {@link #flightTarget} rather than cruising toward it.
     */
    protected boolean landing;
    /**
     * Current lean and thrust. Server-side truth; the quantised copy on the wire is what the client draws.
     */
    protected FlightAttitude attitude = FlightAttitude.LEVEL;

    public EntityGlyphid(EntityType<? extends EntityGlyphid> type, Level level) {
        super(type, level);
        applyEntityAttributes();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, GlyphidStats.getStats().getGrunt().health())
                .add(Attributes.MOVEMENT_SPEED, GlyphidStats.getStats().getGrunt().movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, GlyphidStats.getStats().getGrunt().damage());
    }

    /**
     * How wide a bite this caste takes, and how tough a block it can chew. Derived from body scale so a
     * bigger caste digs faster without every subclass restating the numbers.
     */
    public static int blastSize(double scale) {
        return Math.min((int) (3 * scale) / 2, 5);
    }

    public static int blastResToDig(double scale) {
        return Math.min((int) (50 * (scale * 2)), 150);
    }

    public ResourceLocation getSkin() {
        return TEXTURE;
    }

    public double getGlyphidScale() {
        return 1.0D;
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
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0D, true));
        // Flight outranks walking to the same place, and falls back to it for anything without wings.
        goalSelector.addGoal(4, new GlyphidFlightGoal(this));
        // Same priority as wandering, and mutually exclusive with it: one runs under orders, the other only
        // when idle.
        goalSelector.addGoal(4, new GlyphidTaskMoveGoal(this, 1.0D));
        goalSelector.addGoal(4, new GlyphidWanderGoal(this, 1.0D));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new GlyphidTargetGoal(this));
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    // --- profiling hooks ---
    // Placed at every level the swarm cost could hide in: the whole tick, the AI layer inside it, and the
    // two things that are quadratic in swarm density. Each is one branch when profiling is off.

    @Override
    public void tick() {
        long t = SwarmProfiler.begin();
        super.tick();
        SwarmProfiler.end(SwarmProfiler.Phase.TICK, t);
    }

    // The AI bucket cannot be an override: Mob#serverAiStep is final. It is taken in MixinMob instead.

    @Override
    protected void pushEntities() {
        long t = SwarmProfiler.begin();
        super.pushEntities();
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

    /**
     * Take off or land.
     *
     * <p>Gravity is handed to the flight model while airborne, because {@link com.wf.wfballistics.drone.flight.Multirotor}
     * already subtracts it: leaving vanilla's on as well would apply it twice and the glyphid would never get
     * off the ground.
     */
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

    public void setFlightTarget(@Nullable Vec3 target, boolean landing) {
        this.flightTarget = target;
        this.landing = landing;
    }

    /**
     * One tick of flight, replacing the walking physics entirely.
     */
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

        // Flown into something. The terrain lookahead handles hills, but not an overhang or a wall that rises
        // faster than the sample ahead of it, so anything that actually hits climbs its way out rather than
        // grinding against it -- which is what a bug would do anyway.
        if (horizontalCollision && !landing) {
            setDeltaMovement(getDeltaMovement().add(0.0, GlyphidFlight.WINGS.maxClimbRate() * 0.5, 0.0));
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
     * How fast this caste cruises. Scaled by body size so a bigger bug is not simply a slower one.
     */
    public double cruiseSpeed() {
        return GlyphidFlight.CRUISE_SPEED / Math.max(0.5, getGlyphidScale());
    }

    /**
     * The ground a flying glyphid holds its clearance above: the highest of what is under it and what is
     * ahead of it, so it climbs before a hill rather than into it.
     */
    protected int floorHeight(Vec3 target) {
        int here = surfaceAt(getBlockX(), getBlockZ());
        double dx = target.x - getX();
        double dz = target.z - getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) {
            return here;
        }
        double reach = Math.min(distance, GlyphidFlight.LOOKAHEAD);
        int aheadX = Mth.floor(getX() + dx / distance * reach);
        int aheadZ = Mth.floor(getZ() + dz / distance * reach);
        return Math.max(here, surfaceAt(aheadX, aheadZ));
    }

    /**
     * @return the surface height of a column, or this glyphid's own altitude where the chunk is not loaded --
     * which keeps it holding station rather than diving at terrain nobody has generated.
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

    /**
     * The damage threshold and resistance this bug currently presents, as {@code [threshold, resistance]}.
     * Varies with live armour state, so it cannot be expressed as a static resistance profile.
     */
    public float[] getCurrentDTDR(DamageSource damage) {
        if (damage.is(DamageTypeTags.BYPASSES_ARMOR)) return new float[]{0F, 0F};

        GlyphidStats.StatBundle stats = getStats();
        float threshold = stats.thresholdMultForArmor() * getGlyphidArmor() / 5F;

        if (damage.is(WFDamageTypes.LASER)) return new float[]{threshold * 0.5F, stats.resistanceMult() * 0.5F};
        if (damage.is(WFDamageTypes.ELECTRIC)) return new float[]{threshold * 0.25F, stats.resistanceMult() * 0.25F};
        if (isFireDamage(damage)) return new float[]{0F, stats.resistanceMult() * 0.2F};
        if (isExplosionDamage(damage)) return new float[]{threshold * 0.5F, stats.resistanceMult() * 0.35F};

        return new float[]{threshold, stats.resistanceMult()};
    }

    public void onDamageDealt(DamageSource damage, float amount) {
        if (isArmorBroken(amount)) breakOffArmor();
    }

    public static boolean isFireDamage(DamageSource source) {
        return source.is(DamageTypeTags.IS_FIRE) || source.is(WFDamageTypes.FIRE);
    }

    public static boolean isExplosionDamage(DamageSource source) {
        return source.is(DamageTypeTags.IS_EXPLOSION) || source.is(WFDamageTypes.EXPLOSIVE);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Glyphids never hurt each other, so a swarm packed shoulder to shoulder doesn't cut itself down
        // with its own splash.
        if (source.getEntity() instanceof EntityGlyphid) return false;
        return GlyphidStats.getStats().handleAttack(this, source, amount);
    }

    /**
     * The normal outcome of a hit, for {@link GlyphidStats} implementations to finish on.
     */
    public boolean attackSuperclass(DamageSource source, float amount) {
        return super.hurt(source, amount);
    }

    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.6, 2), 100);
    }

    /**
     * Knock a random surviving plate off.
     */
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

        // Wings are for getting somewhere. Anything else -- orders cancelled, target acquired, dropped into
        // water -- comes down, so a stale airborne flag can never leave one hovering with its gravity off.
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

    /**
     * Take a bite out of the dig site, then go back to whatever this bug was doing before.
     */
    protected void dig() {
        digAt(taskX, taskY + 2, taskZ);
        setCurrentTask(previousTask, previousWaypoint);
    }

    /**
     * Chew one bite out of a spot, leaving this bug's orders alone. Separate from {@link #dig} so something
     * blocked on its way somewhere can bite through without pretending to be on a dig task.
     */
    public void digAt(int x, int y, int z) {
        swing(InteractionHand.MAIN_HAND);

        long t = SwarmProfiler.begin();
        long pathBefore = SwarmProfiler.accrued(SwarmProfiler.Phase.PATH);
        ExplosionAEF blast = new ExplosionAEF(level(), x, y, z, blastSize, this);
        blast.setBlockAllocator(new BlockAllocatorGlyphidDig(blastResToDig, EntityGlyphid::isSpawnerBlock));
        blast.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
        blast.setEntityProcessor(null);
        blast.setPlayerProcessor(null);
        blast.explode();
        // Breaking a block makes ServerLevel synchronously recompute the path of every mob routed through it,
        // so part of a dig is really other glyphids pathfinding. Charged to the search, not to the bite.
        SwarmProfiler.endExcluding(SwarmProfiler.Phase.DIG, t, SwarmProfiler.Phase.PATH, pathBefore);
    }

    public @Nullable Player findTargetCandidate() {
        if (hasEffect(MobEffects.BLINDNESS)) return null;
        double radius = useExtendedTargeting() ? 128D : 16D;
        return level().getNearestPlayer(getX(), getY(), getZ(), radius, false);
    }

    /**
     * Whether this bug hunts across the map rather than only what wanders near it.
     *
     * <p>Upstream also switches this on where industrial pollution is heavy. There is no pollution system
     * here, so it is config only.
     */
    public boolean useExtendedTargeting() {
        return WFConfig.GLYPHID_EXTENDED_TARGETING.get();
    }

    public boolean canDig() {
        return WFConfig.GLYPHID_DIG.get();
    }

    /**
     * Blind glyphids stop hunting; the big castes lash out at the light source that did it.
     */
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

    /**
     * Blocks the colony will not chew through, whatever their resistance.
     *
     * <p>Currently nothing: the glyphid spawner blocks this guards are part of the hive port and have not
     * landed yet.
     */
    public static boolean isSpawnerBlock(BlockState state) {
        return false;
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

    /**
     * Caste-specific drops. Empty until the glyphid item set lands with the hive port.
     */
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

    /**
     * Drop a waypoint on home carrying a second one back here, so the bugs this one fetches know where the
     * fight was.
     */
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

    /**
     * Pass an order to every non-scout bug within the waypoint's radius. Scouts are skipped: they carry
     * their own orders and following the crowd would defeat the point of scouting.
     */
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

    /**
     * Pull the destination height onto real terrain once its column is loaded.
     *
     * <p>Shared by both movement goals, and load-bearing for either. A glyphid is given a placeholder height
     * when it materialises, because the base it is heading for is usually still unloaded from where it lands.
     * Left stale, the arrival test can never pass: walkers mill around on top of their own target and flyers
     * hover over it forever.
     */
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

    /**
     * Glyphids are not slowed by cobwebs or soul sand: a swarm bogged down in terrain stops being a swarm.
     */
    @Override
    public void makeStuckInBlock(BlockState state, Vec3 motionMultiplier) {
    }

    /**
     * Climbing is suppressed in the air: vanilla clamps a climbing entity's motion to a crawl in every axis,
     * so a glyphid that brushed a wall mid-flight would drop out of the sky.
     */
    @Override
    public boolean onClimbable() {
        return !isAirborne() && isBesideClimbableBlock();
    }

    public boolean isBesideClimbableBlock() {
        return entityData.get(DW_WALL);
    }

    public void setBesideClimbableBlock(boolean climbable) {
        entityData.set(DW_WALL, climbable);
    }

    // --- persistence ---

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putByte("armor", armor());
        compound.putByte("subtype", subtype());
        // Only the wings are saved, not whether they were in use: a glyphid reloads on the ground.
        compound.putBoolean("canFly", canFly());

        compound.putBoolean("hasHome", hasHome);
        compound.putInt("homeX", homeX);
        compound.putInt("homeY", homeY);
        compound.putInt("homeZ", homeZ);

        compound.putBoolean("hasWaypoint", hasWaypoint);
        compound.putInt("taskX", taskX);
        compound.putInt("taskY", taskY);
        compound.putInt("taskZ", taskZ);

        compound.putInt("task", currentTask);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        entityData.set(DW_ARMOR, compound.contains("armor") ? compound.getByte("armor") : FULL_ARMOR);
        entityData.set(DW_SUBTYPE, compound.getByte("subtype"));
        setCanFly(compound.getBoolean("canFly"));
        setAirborne(false);

        hasHome = compound.getBoolean("hasHome");
        homeX = compound.getInt("homeX");
        homeY = compound.getInt("homeY");
        homeZ = compound.getInt("homeZ");

        hasWaypoint = compound.getBoolean("hasWaypoint");
        taskX = compound.getInt("taskX");
        taskY = compound.getInt("taskY");
        taskZ = compound.getInt("taskZ");

        currentTask = compound.getInt("task");
        applyEntityAttributes();
    }
}
