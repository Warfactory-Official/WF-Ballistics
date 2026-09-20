package com.wf.wflib.recon.propagate;

import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.SensorSpec;
import com.wf.wflib.recon.detect.Plot;
import com.wf.wflib.recon.env.Atmosphere;
import com.wf.wflib.recon.snapshot.ReconTerrain;
import com.wf.wflib.recon.snapshot.SensorSnapshot;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import org.jetbrains.annotations.Nullable;

/** The thermal model: a very good bearing, a very bad range, and nothing to jam. */
public final class ThermalPropagator implements Propagator {

    public static final ThermalPropagator INSTANCE = new ThermalPropagator();

    /** Fraction of the range that is range error. */
    public static final double RANGE_FRACTION = 0.25;
    /** Line-of-sight margin, in blocks. */
    public static final double LOS_MARGIN = 0.0;
    /** Signal-to-noise at which this band will classify. */
    public static final double CLASSIFY_SNR = 4.0;

    /** Skin temperature of the reference emitter, in degrees. */
    public static final double SKIN_C = 90.0;
    /** Ambient the band is calibrated against: plains, dry, level with the horizon. */
    public static final double REF_C = 15.0;
    /** Floor on contrast. */
    public static final double MIN_CONTRAST = 0.05;
    /** Fraction of reach lost in full rain, and again in full thunder. */
    public static final double RAIN_ATTEN = 0.45;
    public static final double THUNDER_ATTEN = 0.25;

    private ThermalPropagator() {
    }

    /**
     * @return how much of the reference contrast this ground offers, where 1.0 is {@link #REF_C}.
     */
    public static double contrast(double ambientC) {
        return Math.max(MIN_CONTRAST, (SKIN_C - ambientC) / (SKIN_C - REF_C));
    }

    /** The band's whole response to the weather, as one multiplier on effective detection range. */
    public static double environment(Atmosphere atmos, double ambientC) {
        return Math.sqrt(contrast(ambientC))
                * Math.max(0.05, 1.0 - RAIN_ATTEN * atmos.rain() - THUNDER_ATTEN * atmos.thunder());
    }

    @Override
    public Band band() {
        return Band.THERMAL;
    }

    @Override
    @Nullable
    public Plot detect(SensorSnapshot sensor, TargetSnapshot target, ReconTerrain terrain, long gameTime) {
        Look look = look(sensor, target, terrain);
        if (!look.detected()) {
            return null;
        }
        SensorSpec spec = sensor.spec();
        double range = look.range();
        double ux = (target.x() - sensor.x()) / range;
        double uy = (target.y() - sensor.eyeY()) / range;
        double uz = (target.z() - sensor.z()) / range;

        double horizontal = Math.sqrt(ux * ux + uz * uz);
        double bx = 1.0;
        double bz = 0.0;
        if (horizontal > 1.0E-6) {
            bx = ux / horizontal;
            bz = uz / horizontal;
        }
        double px = -bz;
        double pz = bx;

        double crossError = spec.beamWidth() * range;
        double rangeError = RANGE_FRACTION * range;
        long seed = mix(sensor.sensorId() * 0x9E3779B97F4A7C15L ^ target.sourceId(), gameTime);
        double crossJitter = gaussian(seed) * crossError * 0.5;
        double rangeJitter = gaussian(seed ^ 0x5DEECE66DL) * rangeError * 0.5;

        ContactClass hint = look.snr() >= CLASSIFY_SNR ? target.kind() : ContactClass.UNKNOWN;
        return Plot.single(Band.THERMAL,
                target.x() + px * crossJitter + bx * rangeJitter,
                target.y() + uy * rangeJitter,
                target.z() + pz * crossJitter + bz * rangeJitter,
                bx, bz, crossError, rangeError, look.snr(), hint, false, sensor.hops(), gameTime);
    }

    /**
     * Everything the model works out about one target, including the parts a plot throws away.
     *
     * @param ambientC the ground temperature behind the target, which is where contrast is set.
     * @param environment the weather's net multiplier on reach, after correction. Carried so a readout can say
     *      <em>why</em> the range moved rather than only that it did.
     */
    public record Look(double range, double detectionRange, float thermal, double snr,
                       double ambientC, double environment, boolean buried, boolean masked) {

        public boolean detected() {
            return !buried && thermal > 0.0f && range <= detectionRange && !masked;
        }

        public String reason() {
            if (buried) {
                return "buried";
            }
            if (thermal <= 0.0f) {
                return "cold";
            }
            if (range > detectionRange) {
                return String.format(java.util.Locale.ROOT,
                        "out of range (%.0f > %.0f, ambient %.0fC, weather x%.2f)",
                        range, detectionRange, ambientC, environment);
            }
            if (masked) {
                return "masked by terrain";
            }
            return "detected";
        }
    }

    /**
     * The model itself. Pure, and the only place the equations live.
     */
    public Look look(SensorSnapshot sensor, TargetSnapshot target, ReconTerrain terrain) {
        SensorSpec spec = sensor.spec();
        Atmosphere atmos = sensor.atmos();
        float thermal = target.presentedTo(sensor).thermal();
        double ambientC = atmos.ambientC(terrain.baseTempAt(target.x(), target.z()),
                terrain.downfallAt(target.x(), target.z()));
        double environment = atmos.correct(environment(atmos, ambientC));
        double detectionRange = spec.baseRange() * Math.sqrt(Math.max(0.0f, thermal)) * environment;
        double range = Math.max(1.0E-4,
                Math.sqrt(target.distanceSqTo(sensor.x(), sensor.eyeY(), sensor.z())));
        boolean masked = !terrain.lineOfSight(sensor.x(), sensor.eyeY(), sensor.z(),
                target.x(), target.y(), target.z(), LOS_MARGIN);
        return new Look(range, detectionRange, thermal, Math.pow(detectionRange / range, 2.0),
                ambientC, environment, target.buried(), masked);
    }

    private static long mix(long a, long b) {
        long z = a + b * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double gaussian(long seed) {
        long a = mix(seed, 0x2545F4914F6CDD1DL);
        long b = mix(seed, 0x8EBC6AF09C88C6E3L);
        double ua = (a >>> 11) * 0x1.0p-53;
        double ub = (b >>> 11) * 0x1.0p-53;
        return (ua + ub) - 1.0;
    }
}
