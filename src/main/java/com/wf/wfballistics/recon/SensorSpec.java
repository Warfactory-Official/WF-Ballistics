package com.wf.wfballistics.recon;

/**
 * Everything a sensor is, as data.
 *
 * @param netId which network this sensor feeds. Sensors sharing a net share one fused picture, which
 *      is the whole point: fifty turrets on one net stop being fifty sensors that happen to
 *      be near each other.
 * @param baseRange the range at which this detects a reference signature of {@code 1.0}. Everything else
 *      scales by the fourth root of its signature.
 * @param beamWidth radians. Cross-range error is this times the range, so it is the number that decides
 *      when two targets merge into one contact.
 * @param rangeError blocks, constant with range: pulse width, not beam width.
 * @param mastHeight blocks above the sensor's block position that it actually looks from. The one build
 *      decision that changes what a radar can see over.
 * @param sweepTicks ticks between looks. Latency is a real radar property, so a slow sweep is a real cost
 *      rather than a concession to the tick budget.
 * @param notchThreshold blocks per tick of radial velocity below which a target near clutter is lost.
 *      Calibrated against measured ground speeds rather than picked: a glyphid milling
 *      about does 0.046 b/tick and a marching one comfortably more, a player walks 0.21
 *      and a missile cruises at 1.2. A threshold at 0.02 puts a stationary target and a
 *      perfect crossing shot in the notch while leaving a swarm that is actually coming
 *      for you visible. Set it at 0.06 and every glyphid in the game disappears, which is
 *      how this number was found.
 * @param emitting whether this sensor is switched on and taking looks. {@link SensorHandle#sweepDue} gates
 *      on it, so this is the on-switch: a geophone that only listens still has it true.
 * @param radiating whether this set announces itself while it works, which {@link #emitting} deliberately
 *      does not say. Detection costs R^4 and being detected costs R^2, and that asymmetry is the
 *      whole EMCON game: a radar and a pinging sonar are both beacons, a geophone, an imager and
 *      a listening hydrophone are not.
 */
public record SensorSpec(long netId, Band band, SensorRole role,
                         double baseRange, double beamWidth, double rangeError,
                         double mastHeight, int sweepTicks, double notchThreshold,
                         boolean emitting, boolean radiating) {

    /**
     * A basic dish: wide beam, coarse ranging, a slow sweep. Good for warning, useless for shooting.
     */
    public static SensorSpec surveillanceRadar(long netId, double baseRange) {
        return new SensorSpec(netId, Band.RADAR, SensorRole.SURVEILLANCE, baseRange,
                0.05, 4.0, 1.0, 10, 0.02, true, true);
    }

    /**
     * A tracking set: narrow beam, tight ranging, a fast sweep, and correspondingly short-ranged.
     */
    public static SensorSpec fireControlRadar(long netId, double baseRange) {
        return new SensorSpec(netId, Band.RADAR, SensorRole.FIRE_CONTROL, baseRange,
                0.012, 1.0, 0.5, 2, 0.01, true, true);
    }

    /** A geophone: no bearing at all, a range that does not degrade with distance, and a slow ear. */
    public static SensorSpec seismicArray(long netId, double baseRange) {
        return new SensorSpec(netId, Band.SEISMIC, SensorRole.SURVEILLANCE, baseRange,
                0.0, 0.0, 0.0, 40, 0.0, true, false);
    }

    /** A thermal imager: a very narrow beam, a short reach, and a fast frame. */
    public static SensorSpec thermalImager(long netId, double baseRange) {
        return new SensorSpec(netId, Band.THERMAL, SensorRole.FIRE_CONTROL, baseRange,
                0.008, 0.0, 2.0, 10, 0.0, true, false);
    }

    /**
     * A hydrophone array, listening. The mirror of {@link #seismicArray}: an excellent bearing and no usable
     * range, where the geophone has a good range and no bearing at all. Cross it with a ping through
     * {@link #withRadiating} for the other half.
     *
     * @see com.wf.wfballistics.recon.propagate.SonarPropagator
     */
    public static SensorSpec sonarArray(long netId, double baseRange) {
        return new SensorSpec(netId, Band.SONAR, SensorRole.SURVEILLANCE, baseRange,
                0.02, 1.5, 0.0, 20, 0.0, true, false);
    }

    public SensorSpec withNet(long id) {
        return new SensorSpec(id, band, role, baseRange, beamWidth, rangeError,
                mastHeight, sweepTicks, notchThreshold, emitting, radiating);
    }

    public SensorSpec withMast(double height) {
        return new SensorSpec(netId, band, role, baseRange, beamWidth, rangeError,
                height, sweepTicks, notchThreshold, emitting, radiating);
    }

    public SensorSpec withSweep(int ticks) {
        return new SensorSpec(netId, band, role, baseRange, beamWidth, rangeError,
                mastHeight, Math.max(1, ticks), notchThreshold, emitting, radiating);
    }

    public SensorSpec withEmitting(boolean on) {
        return new SensorSpec(netId, band, role, baseRange, beamWidth, rangeError,
                mastHeight, sweepTicks, notchThreshold, on, radiating && on);
    }

    /** A set that is radiating is necessarily switched on, so this turns both on together. */
    public SensorSpec withRadiating(boolean on) {
        return new SensorSpec(netId, band, role, baseRange, beamWidth, rangeError,
                mastHeight, sweepTicks, notchThreshold, emitting || on, on);
    }
}
