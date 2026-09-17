package com.wf.wfballistics.orbital;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where a satellite is, as a pure function of the world clock.
 *
 * @param epoch the tick {@code phase} is measured from. Stamped fresh on every manoeuvre.
 * @param altitude blocks above y=0. Sets resolution, link delay and how much ground a footprint covers, so
 *      it is the one launch decision that cannot be undone later.
 * @param trackX X of the reference point the ground track passes through at phase 0.5.
 * @param trackZ Z of the same point.
 * @param heading radians in the XZ plane, {@code x = cos, z = sin}. The direction of travel; drift is
 *      perpendicular to it.
 * @param phase where along the track the bird was at {@code epoch}, in revolutions. 0.5 puts it exactly
 *      over {@code (trackX, trackZ)}, which is what a launch from a point uses.
 * @param periodTicks ticks for one revolution. With {@link #TRACK_LENGTH} fixed, this is the ground speed.
 * @param driftPerPass blocks the track slides sideways each revolution. Zero would re-image one strip forever.
 */
public record OrbitElements(long epoch, double altitude, double trackX, double trackZ,
                            double heading, double phase, double periodTicks, double driftPerPass) {

    /** Blocks in one revolution of the ground track. */
    public static final double TRACK_LENGTH = 24000.0;

    public OrbitElements {
        periodTicks = Math.max(1.0, periodTicks);
    }

    /**
     * Low orbit: fast, close, best resolution, and overhead for only seconds at a time.
     */
    public static OrbitElements leo(long epoch, double x, double z, double heading) {
        return new OrbitElements(epoch, OrbitalConfig.LEO_ALTITUDE, x, z, heading, 0.5,
                OrbitalConfig.LEO_PERIOD, OrbitalConfig.LEO_DRIFT);
    }

    /**
     * Middle orbit: a longer look and a wider footprint, paid for in resolution and link delay.
     */
    public static OrbitElements meo(long epoch, double x, double z, double heading) {
        return new OrbitElements(epoch, OrbitalConfig.MEO_ALTITUDE, x, z, heading, 0.5,
                OrbitalConfig.MEO_PERIOD, OrbitalConfig.MEO_DRIFT);
    }

    /**
     * High orbit: a long dwell and an awkward intercept, at the worst resolution and the highest launch cost.
     */
    public static OrbitElements heo(long epoch, double x, double z, double heading) {
        return new OrbitElements(epoch, OrbitalConfig.HEO_ALTITUDE, x, z, heading, 0.5,
                OrbitalConfig.HEO_PERIOD, OrbitalConfig.HEO_DRIFT);
    }

    /** Where this bird is at a given tick. */
    public Vec3 at(long gameTime) {
        double revs = revolutions(gameTime);
        double pass = Math.floor(revs);
        double along = (revs - pass - 0.5) * TRACK_LENGTH;
        double side = pass * driftPerPass;
        double cos = Math.cos(heading);
        double sin = Math.sin(heading);
        return new Vec3(trackX + cos * along - sin * side, altitude, trackZ + sin * along + cos * side);
    }

    /**
     * @return revolutions completed since {@code epoch}, fractional. The one intermediate worth exposing:
     *      a caller wanting "how far through this pass" should not have to re-derive it and risk disagreeing.
     */
    public double revolutions(long gameTime) {
        return (gameTime - epoch) / periodTicks + phase;
    }

    /**
     * @return which pass the bird is on at this tick. Negative before the epoch, which is correct: the past
     *      is as readable as the future.
     */
    public long passAt(long gameTime) {
        return Mth.lfloor(revolutions(gameTime));
    }

    /**
     * @return blocks per tick along the ground track. Derived rather than stored so it cannot disagree with
     *      the period.
     */
    public double groundSpeed() {
        return TRACK_LENGTH / periodTicks;
    }

    /** Replace these elements with ones that put the bird over a new point, keeping altitude and period. */
    public OrbitElements manoeuvre(long now, double x, double z, double newHeading) {
        return new OrbitElements(now, altitude, x, z, newHeading, 0.5, periodTicks, driftPerPass);
    }

    /**
     * @return the same track re-stamped at {@code now} without moving the bird: used when only the epoch
     *      needs refreshing, such as resuming an orbit after a park.
     */
    public OrbitElements restamped(long now) {
        return new OrbitElements(now, altitude, trackX, trackZ, heading,
                revolutions(now), periodTicks, driftPerPass);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Epoch", epoch);
        tag.putDouble("Alt", altitude);
        tag.putDouble("X", trackX);
        tag.putDouble("Z", trackZ);
        tag.putDouble("Head", heading);
        tag.putDouble("Phase", phase);
        tag.putDouble("Period", periodTicks);
        tag.putDouble("Drift", driftPerPass);
        return tag;
    }

    public static OrbitElements load(CompoundTag tag) {
        return new OrbitElements(tag.getLong("Epoch"), tag.getDouble("Alt"), tag.getDouble("X"),
                tag.getDouble("Z"), tag.getDouble("Head"), tag.getDouble("Phase"),
                tag.getDouble("Period"), tag.getDouble("Drift"));
    }
}
