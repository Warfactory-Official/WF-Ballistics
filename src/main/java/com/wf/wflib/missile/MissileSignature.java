package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileEntity.Phase;
import com.wf.wflib.sim.MissileSimConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** What air defence sees (radar cross-section) and how well the missile dodges it. */
public final class MissileSignature {

    private final MissileEntity missile;

    float rcs = 1.0f;
    float evasion;
    /** Evasion sprints also jink off course (lateral break away from the interceptor). */
    boolean evasiveManeuver;

    public MissileSignature(MissileEntity missile) {
        this.missile = missile;
    }

    /** Radar cross-section; reference 1.0. */
    public float getRcs() {
        return this.rcs;
    }

    /** Display/commands only: detection is {@link #detectableAt}'s curve, not this flag. */
    public boolean isStealth() {
        return this.rcs < MissileSimConfig.STEALTH_RCS_THRESHOLD;
    }

    public float getEvasion() {
        return this.evasion;
    }

    public boolean isEvasiveManeuver() {
        return this.evasiveManeuver;
    }

    /** Base evasion x {@link MissileSimConfig#DIVE_EVASION_MULTIPLIER} in the ATTACK dive, capped at 1. */
    public float effectiveEvasion() {
        if (this.evasion <= 0.0f) {
            return 0.0f;
        }
        double e = this.missile.flight().getPhase() == Phase.ATTACK
                ? this.evasion * MissileSimConfig.DIVE_EVASION_MULTIPLIER : this.evasion;
        return (float) Math.min(1.0, e);
    }

    /** Detection range scales with rcs^(1/4). */
    public boolean detectableAt(double distSq, double baseRange) {
        if (this.rcs <= 0.0f) {
            return false;
        }
        double detection = baseRange * Math.sqrt(Math.sqrt(this.rcs));
        return distSq <= detection * detection;
    }

    /** @return true if the missile sprinted clear: the incoming hit becomes a miss. */
    public boolean evade(double interceptorSpeed, Vec3 interceptorPos) {
        if (this.evasion <= 0.0f || this.missile.motor().getFuel() < MissileMotor.BOOST_FUEL_COST
                || !(this.missile.level() instanceof ServerLevel sl)) {
            return false;
        }
        this.missile.motor().startBoost(this.evasiveManeuver ? this.jink(sl.random, interceptorPos) : null);
        double boostedSpeed = Math.max(this.missile.getDeltaMovement().length(),
                this.missile.flight().getCruiseSpeed() * MissileMotor.BOOST_SPEED_MULT);
        double speedFactor = Math.min(1.0, boostedSpeed / Math.max(1.0E-3, interceptorSpeed));
        return sl.random.nextFloat() < (float) (this.effectiveEvasion() * speedFactor);
    }

    /** Break direction in the plane normal to the heading, away from the interceptor, +-35 deg jitter. */
    private Vec3 jink(RandomSource rand, Vec3 interceptorPos) {
        Vec3 v = this.missile.getDeltaMovement();
        double vlen = v.length();
        Vec3 vhat = vlen > 1.0E-6 ? v.scale(1.0 / vlen) : new Vec3(0.0, 1.0, 0.0);
        Vec3 ref = Math.abs(vhat.y) < 0.95 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        Vec3 right = vhat.cross(ref).normalize();
        Vec3 up = right.cross(vhat).normalize();
        Vec3 away = this.missile.position().subtract(interceptorPos);
        double ar = away.dot(right);
        double au = away.dot(up);
        double angle = (ar * ar + au * au) > 1.0E-8 ? Math.atan2(au, ar) : rand.nextDouble() * Math.PI * 2.0;
        angle += rand.nextGaussian() * 0.6;
        return right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle))).normalize();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Rcs", this.rcs);
        tag.putFloat("Evasion", this.evasion);
        tag.putBoolean("EvasiveManeuver", this.evasiveManeuver);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.rcs = tag.getFloat("Rcs");
        this.evasion = tag.getFloat("Evasion");
        this.evasiveManeuver = tag.getBoolean("EvasiveManeuver");
    }
}
