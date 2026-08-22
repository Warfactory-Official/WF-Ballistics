package com.wf.wfballistics.debug;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * A target that exists only to be hit.
 *
 * <p>Melee was benchmarked against cows, which measures the wrong thing three ways: the cows run their own
 * pathfinding and goals inside the same tick being attributed to the swarm, they wander unpredictably, and
 * they die — a window that starts with sixty targets and ends with four is not measuring a steady state. A
 * dummy runs no AI, cannot be killed, and moves only if told to, so what is left in the numbers is the swarm.
 *
 * <p>Movement is a deliberate dial rather than an accident, because it decides the answer: see {@link #drift}.
 *
 * <p>Counts what lands rather than absorbing it silently. A benchmark where the attackers are quietly failing
 * to reach anything reads exactly like a fast one.
 */
public class EntityDebugDummy extends Mob {

    /**
     * Radius of the circle a mobile dummy walks, in blocks. Zero for a dummy that holds still.
     *
     * <p>Whether the target moves is not a detail: vanilla's melee goal only repaths when its target has
     * shifted a block, so a swarm attacking something stationary pays almost nothing for pathfinding and one
     * attacking something that walks pays a third of its tick. A benchmark with only still targets reports
     * the wrong answer with a straight face.
     */
    private float drift;
    private double homeX;
    private double homeZ;

    private int hits;
    private float damageTaken;

    public EntityDebugDummy(EntityType<? extends EntityDebugDummy> type, Level level) {
        super(type, level);
        setNoAi(true);
        setPersistenceRequired();
        setInvulnerable(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1024.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                // Nothing looks for a dummy, so follow range only has to be small enough not to matter.
                .add(Attributes.FOLLOW_RANGE, 1.0);
    }

    /**
     * Send this dummy round a circle of {@code radius} blocks centred where it stands.
     *
     * <p>Moved by setting position rather than by walking it: a target that pathfinds would put its own
     * navigation cost inside the window meant to measure the swarm's, which is what made cows a bad target.
     */
    public void setDrift(float radius) {
        drift = radius;
        homeX = getX();
        homeZ = getZ();
    }

    @Override
    public void tick() {
        super.tick();
        if (drift <= 0.0F || level().isClientSide) {
            return;
        }
        // Staggered by id so sixty dummies do not orbit in lockstep. ~3 blocks/s at radius 8, near enough to
        // a walking player.
        double angle = tickCount * 0.02 + getId();
        double x = homeX + Math.cos(angle) * drift;
        double z = homeZ + Math.sin(angle) * drift;
        setPos(x, level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z), z);
    }

    public int hits() {
        return hits;
    }

    public float damageTaken() {
        return damageTaken;
    }

    public void resetCounters() {
        hits = 0;
        damageTaken = 0.0F;
    }

    /**
     * Records the hit and shrugs it off. Returning {@code true} matters: an attacker that is told its swing
     * missed may re-path, re-target or give up, and then the benchmark is measuring a swarm reacting to being
     * ignored rather than a swarm fighting.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // /kill and the void have to keep working. Swallowing those too made a stale set of dummies survive
        // a `kill @e` and silently hijack the next benchmark: the swarm found targets it was not supposed to
        // have, and a march test measured a brawl.
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, amount);
        }
        if (!level().isClientSide) {
            hits++;
            damageTaken += amount;
        }
        return true;
    }

    // Held in place: knockback would spread the dummies out mid-window and quietly change the geometry the
    // run is measuring.

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected boolean isImmobile() {
        return true;
    }

    @Override
    public void travel(Vec3 travelVector) {
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        PathNavigation navigation = super.createNavigation(level);
        navigation.stop();
        return navigation;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putFloat("drift", drift);
        compound.putInt("hits", hits);
        compound.putFloat("damageTaken", damageTaken);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        drift = compound.getFloat("drift");
        homeX = getX();
        homeZ = getZ();
        hits = compound.getInt("hits");
        damageTaken = compound.getFloat("damageTaken");
    }
}
