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

/** The seismic model: range without bearing, through rock, from things that are moving it. */
public final class SeismicPropagator implements Propagator {

    public static final SeismicPropagator INSTANCE = new SeismicPropagator();

    /** Blocks above the surface a target may be and still couple into the ground. */
    public static final double COUPLING_MARGIN = 3.0;
    /** Blocks per tick below which a target is not shifting enough rock to hear. */
    public static final double MIN_ACTIVITY = 0.004;
    /** Blocks of range error, constant. */
    public static final double RANGE_ERROR = 12.0;
    /**
     * Blocks over which material absorption compounds. Matches the design's {@code R/16}.
     */
    private static final double ABSORB_SCALE = 16.0;
    /** Blocks between material samples along the path. */
    private static final int PATH_STEP = 16;
    /** Signal-to-noise at which this band will commit to a classification. */
    public static final double CLASSIFY_SNR = 32.0;

    /** How much rain and thunder raise the noise floor. */
    public static final double RAIN_NOISE = 0.7;
    public static final double THUNDER_NOISE = 0.5;
    /** What freezing does to a material's excess absorption. */
    public static final double FROZEN_COUPLING = 0.6;
    /** What saturating it does. The reverse, and rain does both this and the noise floor. */
    public static final double WET_COUPLING = 0.5;
    /** Ambient below which ground is treated as frozen, in degrees. */
    public static final double FREEZING_C = 0.0;

    private SeismicPropagator() {
    }

    /** The band's response to the weather, as one multiplier on effective detection range. */
    public static double environment(Atmosphere atmos) {
        return 1.0 / (1.0 + RAIN_NOISE * atmos.rain() + THUNDER_NOISE * atmos.thunder());
    }

    @Override
    public Band band() {
        return Band.SEISMIC;
    }

    @Override
    @Nullable
    public Plot detect(SensorSnapshot sensor, TargetSnapshot target, ReconTerrain terrain, long gameTime) {
        Look look = look(sensor, target, terrain);
        if (!look.detected()) {
            return null;
        }
        ContactClass hint = look.snr() >= CLASSIFY_SNR ? target.kind() : ContactClass.UNKNOWN;
        return plot(sensor, target.x(), target.y(), target.z(), look, hint,
                sensor.sensorId() * 0x9E3779B97F4A7C15L ^ target.sourceId(), gameTime);
    }

    /**
     * One station's return, placed and blurred the way this band places everything.
     *
     * @param identity anything stable that names what is being looked at. It seeds the jitter, so the same
     *      station looking at the same thing on the same tick reports the same position twice
     *      rather than a fresh error each time it is asked.
     */
    public Plot plot(SensorSnapshot sensor, double tx, double ty, double tz, Look look,
                     ContactClass hint, long identity, long gameTime) {
        double range = look.range();
        double snr = look.snr();

        double dx = tx - sensor.x();
        double dz = tz - sensor.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double bx = 1.0;
        double bz = 0.0;
        if (horizontal > 1.0E-6) {
            bx = dx / horizontal;
            bz = dz / horizontal;
        }
        double px = -bz;
        double pz = bx;

        double crossError = range;
        double rangeError = RANGE_ERROR * (1.0 + 1.0 / snr);

        long seed = mix(identity, gameTime);
        double crossJitter = gaussian(seed) * crossError * 0.5;
        double rangeJitter = gaussian(seed ^ 0x5DEECE66DL) * rangeError * 0.5;

        return Plot.single(Band.SEISMIC,
                tx + px * crossJitter + bx * rangeJitter,
                ty + rangeJitter * 0.25,
                tz + pz * crossJitter + bz * rangeJitter,
                bx, bz, crossError, rangeError, snr, hint, false, sensor.hops(), gameTime);
    }

    /** Everything the model works out about one target, including the parts a plot throws away. */
    public record Look(double range, float energy, double amplitude, double snr, double absorption,
                       double environment, boolean airborne, boolean idle) {

        public boolean detected() {
            return !airborne && !idle && energy > 0.0f && snr >= 1.0;
        }

        public String reason() {
            if (energy <= 0.0f) {
                return "no seismic signature";
            }
            if (airborne) {
                return "airborne (not coupled to the ground)";
            }
            if (idle) {
                return "not moving enough rock";
            }
            if (snr < 1.0) {
                return String.format(java.util.Locale.ROOT,
                        "below the noise floor (amplitude %.4f, absorption %.3f/16blk, weather x%.2f)",
                        amplitude, absorption, environment);
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
        float energy = target.presentedTo(sensor).seismicEnergy();
        double range = Math.max(1.0E-4,
                Math.sqrt(target.distanceSqTo(sensor.x(), sensor.eyeY(), sensor.z())));

        boolean airborne = !target.buried()
                && !terrain.nearClutter(target.x(), target.y(), target.z(), COUPLING_MARGIN);
        boolean idle = target.speed() < MIN_ACTIVITY;

        double absorption = pathAbsorption(terrain, atmos, sensor.x(), sensor.z(), target.x(), target.z());
        double amplitude = energy / (range * Math.pow(absorption, range / ABSORB_SCALE));
        double environment = atmos.correct(environment(atmos));
        double floor = 1.0 / Math.max(1.0E-4, spec.baseRange() * environment);
        return new Look(range, energy, amplitude, amplitude / floor, absorption, environment, airborne, idle);
    }

    /**
     * What one station hears of a blast: the same equations, minus the two gates that only make sense for something
     * that persists.
     */
    public Look hear(SensorSnapshot sensor, ReconTerrain terrain,
                     double x, double y, double z, double energy) {
        Atmosphere atmos = sensor.atmos();
        double range = Math.max(1.0E-4, Math.sqrt(sensor.distanceSqTo(x, y, z)));
        double environment = atmos.correct(environment(atmos));
        double floor = 1.0 / Math.max(1.0E-4, sensor.spec().baseRange() * environment);

        double lossless = energy / range;
        if (lossless < floor) {
            return new Look(range, (float) energy, lossless, lossless / floor, 1.0, environment, false, false);
        }
        double absorption = pathAbsorption(terrain, atmos, sensor.x(), sensor.z(), x, z);
        double amplitude = energy / (range * Math.pow(absorption, range / ABSORB_SCALE));
        return new Look(range, (float) energy, amplitude, amplitude / floor, absorption, environment,
                false, false);
    }

    /**
     * The propagation law run backwards: what the source must have been, given what arrived and how far away the
     * network believes it was.
     *
     * @param range the network's own range estimate, which is emphatically not {@code look.range()}: that is
     *      the true distance, and using it here would leak a fact no station measured.
     */
    public static double energyAt(Look look, double range) {
        return look.amplitude() * range * Math.pow(look.absorption(), range / ABSORB_SCALE);
    }

    /** Mean absorption per {@link #ABSORB_SCALE} blocks along the path. */
    private static double pathAbsorption(ReconTerrain terrain, Atmosphere atmos,
                                         double x0, double z0, double x1, double z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / PATH_STEP);
        if (steps < 1) {
            return absorptionAt(terrain, atmos, x0, z0);
        }
        double sum = 0.0;
        for (int i = 0; i < steps; i++) {
            double t = (i + 0.5) / steps;
            sum += absorptionAt(terrain, atmos, x0 + dx * t, z0 + dz * t);
        }
        return sum / steps;
    }

    /**
     * @return absorption of the ground at one point on the path, in the state the weather has left it in.
     */
    private static double absorptionAt(ReconTerrain terrain, Atmosphere atmos, double x, double z) {
        double excess = absorptionOf(terrain.materialAt(x, z)) - 1.0;
        if (atmos.ambientC(terrain.baseTempAt(x, z), terrain.downfallAt(x, z)) < FREEZING_C) {
            excess *= FROZEN_COUPLING;
        }
        return 1.0 + excess * (1.0 + WET_COUPLING * atmos.rain());
    }

    /** Per-material absorption, as a factor compounding every {@link #ABSORB_SCALE} blocks. */
    private static double absorptionOf(byte material) {
        return switch (material) {
            case ReconTerrain.MAT_ROCK, ReconTerrain.MAT_METAL -> 1.005;
            case ReconTerrain.MAT_SOIL -> 1.03;
            case ReconTerrain.MAT_WOOD -> 1.05;
            case ReconTerrain.MAT_SAND -> 1.09;
            case ReconTerrain.MAT_WATER -> 1.14;
            default -> 1.01;
        };
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
