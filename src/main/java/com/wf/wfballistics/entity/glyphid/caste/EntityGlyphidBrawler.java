package com.wf.wfballistics.entity.glyphid.caste;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidBallistics;
import com.wf.wfballistics.entity.glyphid.GlyphidStats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Closes the distance by throwing itself. */
public class EntityGlyphidBrawler extends EntityGlyphid {

    /** Ticks between leaps, plus a random tail so a rank of brawlers does not jump in unison. */
    private static final int LEAP_INTERVAL = 80;
    private static final int LEAP_JITTER = 30;
    /** Do not leap further than this; past it the arc is so high the brawler spends the flight being shot at. */
    private static final double LEAP_RANGE = 20.0;
    private static final double MIN_LEAP = 3.0;
    /** Muzzle speed of the brawler itself, and the gravity it falls under. */
    private static final double LEAP_SPEED = 1.5;
    private static final double LEAP_GRAVITY = 0.05;
    private static final int LEAP_LEAD = 20;
    /** Fall damage a brawler shrugs off, so it does not kill itself landing. */
    private static final float FALL_TOLERANCE = 10.0F;

    private int leapCooldown;
    private @Nullable LivingEntity trackedTarget;
    private double trackedX;
    private double trackedY;
    private double trackedZ;

    public EntityGlyphidBrawler(EntityType<? extends EntityGlyphidBrawler> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        GlyphidStats.StatBundle stats = GlyphidStats.getStats().getBrawler();
        return EntityGlyphid.createAttributes()
                .add(Attributes.MAX_HEALTH, stats.health())
                .add(Attributes.MOVEMENT_SPEED, stats.movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, stats.damage());
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getBrawler();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        LivingEntity target = getTarget();
        if (target == null || !isAlive()) {
            trackedTarget = null;
            return;
        }

        if (tickCount % GlyphidBallistics.TRACK_INTERVAL == 0) {
            trackedTarget = target;
            trackedX = target.getX();
            trackedY = target.getY();
            trackedZ = target.getZ();
        }

        if (--leapCooldown <= 0) {
            leap(target);
            leapCooldown = LEAP_INTERVAL + random.nextInt(LEAP_JITTER);
        }
    }

    /**
     * A leap is a launch solution applied to {@code setDeltaMovement}, so the same arc the bombardier throws acid
     * along is the one the brawler rides.
     */
    private void leap(LivingEntity target) {
        double distance = distanceTo(target);
        if (distance > LEAP_RANGE || distance < MIN_LEAP || !onGround()) {
            return;
        }

        Vec3 aim = GlyphidBallistics.lead(target, trackedX, trackedY, trackedZ,
                trackedTarget == target, LEAP_LEAD);
        Vec3 launch = GlyphidBallistics.solve(position(), aim, LEAP_SPEED, LEAP_GRAVITY, false);
        if (launch == null) {
            return;
        }

        setDeltaMovement(launch);
        // Marked so the jump is sent to clients this tick rather than being smoothed into a walk.
        hasImpulse = true;
        setYRot((float) (Math.atan2(launch.x, launch.z) * (180.0 / Math.PI)));
        yBodyRot = getYRot();
    }

    /** A caste that lands on people from twenty blocks up cannot be killed by doing so. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.FALL) && amount <= FALL_TOLERANCE) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.25, 2), 100);
    }
}
