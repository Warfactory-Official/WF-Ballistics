package com.wf.wfballistics.recon.propagate;

import com.wf.wfballistics.recon.Band;
import com.wf.wfballistics.recon.ContactClass;
import com.wf.wfballistics.recon.SensorSpec;
import com.wf.wfballistics.recon.Signature;
import com.wf.wfballistics.recon.detect.Plot;
import com.wf.wfballistics.recon.env.Atmosphere;
import com.wf.wfballistics.recon.snapshot.ReconTerrain;
import com.wf.wfballistics.recon.snapshot.SensorSnapshot;
import com.wf.wfballistics.recon.snapshot.TargetSnapshot;
import org.jetbrains.annotations.Nullable;

/** The radar model, in four equations. */
public final class RadarPropagator implements Propagator {

    public static final RadarPropagator INSTANCE = new RadarPropagator();

    /**
     * Blocks above the surface within which a slow target is lost in ground clutter.
     */
    public static final double CLUTTER_MARGIN = 6.0;
    /** Blocks the terrain must rise above the sight line before it masks. */
    public static final double LOS_MARGIN = 2.0;
    /** Signal-to-noise at which the sensor will commit to a classification. */
    public static final double CLASSIFY_SNR = 16.0;

    /** Fraction of reach lost in full rain, and again in full thunder. */
    public static final double RAIN_ATTEN = 0.20;
    public static final double THUNDER_ATTEN = 0.10;
    /** Extra reach from anomalous propagation on a still, humid night. */
    public static final double DUCT_GAIN = 0.25;
    /** How much rain raises the clutter floor. */
    public static final double PRECIP_CLUTTER = 1.0;

    private RadarPropagator() {
    }

    /**
     * The band's whole response to the weather, as one multiplier on effective detection range.
     *
     * @param humidity the biome downfall where the set is standing, 0 to 1. Ducting needs moisture, so a
     *      desert night is clear and still and lays no duct at all, while a swamp night does.
     */
    public static double environment(Atmosphere atmos, double humidity) {
        double wet = Math.max(0.05, 1.0 - RAIN_ATTEN * atmos.rain() - THUNDER_ATTEN * atmos.thunder());
        double duct = 1.0 + DUCT_GAIN * Math.max(0.0, -atmos.solar()) * (1.0 - atmos.rain())
                * Math.max(0.0, Math.min(1.0, humidity));
        return wet * duct;
    }

    @Override
    public Band band() {
        return Band.RADAR;
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
        double snr = look.snr();
        double crossError = spec.beamWidth() * range;
        double rangeError = spec.rangeError();

        long seed = mix(sensor.sensorId() * 0x9E3779B97F4A7C15L ^ target.sourceId(), gameTime);
        double crossJitter = gaussian(seed) * crossError * 0.5;
        double rangeJitter = gaussian(seed ^ 0x5DEECE66DL) * rangeError * 0.5;
        double horizontal = Math.sqrt(ux * ux + uz * uz);
        double bx = 1.0;
        double bz = 0.0;
        if (horizontal > 1.0E-6) {
            bx = ux / horizontal;
            bz = uz / horizontal;
        }
        double px = -bz;
        double pz = bx;

        ContactClass hint = snr >= CLASSIFY_SNR ? target.kind() : ContactClass.UNKNOWN;
        boolean friendly = target.iffCode() != 0L && target.iffCode() == spec.netId();
        return Plot.single(Band.RADAR,
                target.x() + px * crossJitter + bx * rangeJitter,
                target.y() + uy * rangeJitter,
                target.z() + pz * crossJitter + bz * rangeJitter,
                bx, bz, crossError, rangeError, snr, hint, friendly, sensor.hops(), gameTime);
    }

    /** Everything the model works out about one target, including the parts a plot throws away. */
    public record Look(double range, double detectionRange, float rcs, double radial, double snr,
                       double environment, double clutterMargin,
                       boolean buried, boolean masked, boolean notched) {

        public boolean detected() {
            return !buried && rcs > 0.0f && range <= detectionRange && !masked && !notched;
        }

        /**
         * @return why this was not detected, or "detected".
         */
        public String reason() {
            if (buried) {
                return "buried";
            }
            if (rcs <= 0.0f) {
                return "no signature";
            }
            if (range > detectionRange) {
                return String.format(java.util.Locale.ROOT, "out of range (%.0f > %.0f, weather x%.2f)",
                        range, detectionRange, environment);
            }
            if (masked) {
                return "masked by terrain";
            }
            if (notched) {
                return String.format(java.util.Locale.ROOT,
                        "in the notch (radial %.3f b/t, clutter floor %.1f blk)", radial, clutterMargin);
            }
            return "detected";
        }
    }

    /**
     * The model itself. Pure, and the only place the four equations live.
     */
    public Look look(SensorSnapshot sensor, TargetSnapshot target, ReconTerrain terrain) {
        SensorSpec spec = sensor.spec();
        Atmosphere atmos = sensor.atmos();
        float rcs = target.presentedTo(sensor).radarRcs();
        double environment = atmos.correct(
                environment(atmos, terrain.downfallAt(sensor.x(), sensor.z())));
        double detectionRange = spec.baseRange() * Math.pow(Math.max(0.0f, rcs), 0.25) * environment;
        double range = Math.max(1.0E-4,
                Math.sqrt(target.distanceSqTo(sensor.x(), sensor.eyeY(), sensor.z())));

        boolean masked = !terrain.lineOfSight(sensor.x(), sensor.eyeY(), sensor.z(),
                target.x(), target.y(), target.z(), LOS_MARGIN);

        double ux = (target.x() - sensor.x()) / range;
        double uy = (target.y() - sensor.eyeY()) / range;
        double uz = (target.z() - sensor.z()) / range;
        double radial = target.vx() * ux + target.vy() * uy + target.vz() * uz;
        double clutterMargin = CLUTTER_MARGIN
                * (1.0 + PRECIP_CLUTTER * atmos.rain() * (1.0 - atmos.share()));
        boolean notched = Math.abs(radial) < spec.notchThreshold()
                && terrain.nearClutter(target.x(), target.y(), target.z(), clutterMargin);

        return new Look(range, detectionRange, rcs, radial, Math.pow(detectionRange / range, 4.0),
                environment, clutterMargin, target.buried(), masked, notched);
    }

    private static long mix(long a, long b) {
        long z = a + b * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Two uniforms averaged into a rough bell, in roughly [-1, 1]. */
    private static double gaussian(long seed) {
        long a = mix(seed, 0x2545F4914F6CDD1DL);
        long b = mix(seed, 0x8EBC6AF09C88C6E3L);
        double ua = (a >>> 11) * 0x1.0p-53;
        double ub = (b >>> 11) * 0x1.0p-53;
        return (ua + ub) - 1.0;
    }
}
