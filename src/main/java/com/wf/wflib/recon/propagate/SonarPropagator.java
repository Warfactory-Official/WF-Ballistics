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

/**
 * The sonar model: a very good bearing, no range at all until you ping, and nothing outside the water.
 *
 * <p>The exact mirror of {@link SeismicPropagator}, which has a good range and no bearing. Both reach
 * {@code CrossFix} as an error ellipse and it intersects them with the same equations, so two hydrophones on
 * a wide baseline fix a target for the same reason three geophones do.
 */
public final class SonarPropagator implements Propagator {

    public static final SonarPropagator INSTANCE = new SonarPropagator();

    /** Blocks the seabed must rise above the path before it stops it. */
    public static final double BOTTOM_MARGIN = 1.0;
    /**
     * Target strength of a hull with no noise of its own, per square root of its radar cross-section. This is
     * what makes a ping worth its cost: passive hears only what is making noise, active sees anything with a
     * hull.
     */
    public static final double ECHO_HULL = 0.5;
    /** Fraction of the range that is range error when listening: a bearing, and almost nothing else. */
    public static final double PASSIVE_RANGE_FRACTION = 0.8;
    /** Blocks of range error on a ping, constant with range: a pulse time. */
    public static final double ACTIVE_RANGE_ERROR = 1.5;
    /** Signal-to-noise at which this band will commit to a classification. */
    public static final double CLASSIFY_SNR = 8.0;

    /** Blocks of water the band is calibrated against. Below this both boundaries are in the way. */
    public static final double GOOD_DEPTH = 24.0;
    public static final double MIN_DEPTH_FACTOR = 0.25;
    /** Blocks below the surface the thermocline sits at, and what listening across it costs. */
    public static final double LAYER_DEPTH = 18.0;
    public static final double LAYER_LOSS = 0.35;
    /** How much rain and thunder on the surface raise the noise floor. */
    public static final double RAIN_NOISE = 1.0;
    public static final double THUNDER_NOISE = 0.6;

    private SonarPropagator() {
    }

    /**
     * The band's whole response to the weather, as one multiplier on effective detection range. Rain on the
     * surface is noise, and this band has nothing else to feel: it is under the weather rather than in it.
     */
    public static double environment(Atmosphere atmos) {
        return 1.0 / (1.0 + RAIN_NOISE * atmos.rain() + THUNDER_NOISE * atmos.thunder());
    }

    @Override
    public Band band() {
        return Band.SONAR;
    }

    @Override
    @Nullable
    public Plot detect(SensorSnapshot sensor, TargetSnapshot target, ReconTerrain terrain, long gameTime) {
        Look look = look(sensor, target, terrain);
        if (!look.detected()) {
            return null;
        }
        ContactClass hint = look.snr() >= CLASSIFY_SNR ? target.kind() : ContactClass.UNKNOWN;
        boolean friendly = target.iffCode() != 0L && target.iffCode() == sensor.spec().netId();
        return plot(sensor, target.x(), target.y(), target.z(), look, hint, friendly,
                sensor.sensorId() * 0x9E3779B97F4A7C15L ^ target.sourceId(), gameTime);
    }

    /**
     * One set's return, placed and blurred the way this band places everything: tight across the bearing,
     * and either hopeless or exact along it depending on whether the set pinged.
     *
     * @param identity anything stable that names what is being listened to. It seeds the jitter, so the same
     *      set listening to the same thing on the same tick reports the same position twice.
     */
    public Plot plot(SensorSnapshot sensor, double tx, double ty, double tz, Look look,
                     ContactClass hint, boolean friendly, long identity, long gameTime) {
        SensorSpec spec = sensor.spec();
        double range = look.range();
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

        double crossError = spec.beamWidth() * range;
        double rangeError = look.active()
                ? Math.max(ACTIVE_RANGE_ERROR, spec.rangeError())
                : PASSIVE_RANGE_FRACTION * range;

        long seed = mix(identity, gameTime);
        double crossJitter = gaussian(seed) * crossError * 0.5;
        double rangeJitter = gaussian(seed ^ 0x5DEECE66DL) * rangeError * 0.5;

        return Plot.single(Band.SONAR,
                tx + px * crossJitter + bx * rangeJitter,
                ty + rangeJitter * 0.25,
                tz + pz * crossJitter + bz * rangeJitter,
                bx, bz, crossError, rangeError, look.snr(), hint, friendly, sensor.hops(), gameTime);
    }

    /**
     * Everything the model works out about one target, including the parts a plot throws away.
     *
     * @param active whether the set pinged for this look. Decides the law, the target term and the ranging,
     *      so it is the first thing to read when a number here looks wrong.
     * @param strength what the set is working against: radiated noise when listening, and the larger of that
     *      and the hull's own echo when pinging.
     * @param self true when a set has been handed its own ping as a target. Rejected rather than filtered
     *      upstream, because the counter-detection source has no way to know which set is asking.
     */
    public record Look(boolean active, double range, double detectionRange, double strength, double snr,
                       double meanDepth, double depthFactor, double layer, double weather,
                       boolean dry, boolean blocked, boolean self) {

        public boolean detected() {
            return !dry && !blocked && !self && strength > 0.0 && snr >= 1.0;
        }

        /**
         * @return the weather, the depth and the thermocline as the one multiplier they amount to.
         */
        public double environment() {
            return depthFactor * layer * weather;
        }

        public String reason() {
            if (self) {
                return "its own ping";
            }
            if (dry) {
                return "not in the water";
            }
            if (blocked) {
                return "no water path (land, an island or a shoal in the way)";
            }
            if (strength <= 0.0) {
                return active ? "no echo" : "silent";
            }
            if (snr < 1.0) {
                return String.format(java.util.Locale.ROOT,
                        "out of range (%.0f > %.0f, %.0f blk of water x%.2f, layer x%.2f, weather x%.2f)",
                        range, detectionRange, meanDepth, depthFactor, layer, weather);
            }
            return "detected";
        }
    }

    /**
     * The model itself. Pure, and the only place the equations live.
     */
    public Look look(SensorSnapshot sensor, TargetSnapshot target, ReconTerrain terrain) {
        boolean active = sensor.spec().radiating();
        double noise = target.presentedTo(sensor).acoustic();
        // Hull size is physical, so the echo is read off the raw signature: a radar-absorbent coating is a
        // treatment for one band and must not shrink a sonar return.
        double strength = active
                ? Math.max(noise, ECHO_HULL * Math.sqrt(Math.max(0.0f, target.signature().radarRcs())))
                : noise;
        ReconTerrain.WaterPath path = terrain.waterPath(sensor.x(), sensor.eyeY(), sensor.z(),
                target.x(), target.y(), target.z(), BOTTOM_MARGIN);
        return look(sensor, terrain, target.x(), target.y(), target.z(), strength, active, path,
                !target.inWater(), target.sourceId() == sensor.sensorId());
    }

    /**
     * What one set hears of a blast: the same equations run passively, minus the gates that only make sense
     * for something that persists. An explosion is heard, never echoed, however the set is configured.
     */
    public Look hear(SensorSnapshot sensor, ReconTerrain terrain,
                     double x, double y, double z, double energy) {
        ReconTerrain.WaterPath path = terrain.waterPath(sensor.x(), sensor.eyeY(), sensor.z(),
                x, y, z, BOTTOM_MARGIN);
        return look(sensor, terrain, x, y, z, energy, false, path, false, false);
    }

    /**
     * The propagation law run backwards: what the source must have been, given what arrived and how far away
     * the network believes it was.
     *
     * @param range the network's own range estimate, emphatically not {@code look.range()}: that is the true
     *      distance, and using it here would leak a fact no set measured. It is also the reason a single
     *      hydrophone cannot state a yield — it has no range at all to divide by.
     */
    public static double energyAt(SensorSnapshot sensor, Look look, double range) {
        double reach = sensor.spec().baseRange() * look.environment();
        return look.snr() * range * range / Math.max(1.0E-6, reach * reach);
    }

    private Look look(SensorSnapshot sensor, ReconTerrain terrain, double tx, double ty, double tz,
                      double strength, boolean active, ReconTerrain.WaterPath path,
                      boolean dry, boolean self) {
        SensorSpec spec = sensor.spec();
        Atmosphere atmos = sensor.atmos();
        double range = Math.max(1.0E-4,
                Math.sqrt(sensor.distanceSqTo(tx, ty, tz)));
        double depthFactor = path.samples() == 0
                ? 1.0
                : Math.max(MIN_DEPTH_FACTOR, Math.min(1.0, path.meanDepth() / GOOD_DEPTH));
        double layer = crossesLayer(terrain, sensor, tx, ty, tz) ? LAYER_LOSS : 1.0;
        double weather = atmos.correct(environment(atmos));
        // Two-way and a fourth root when it pings, one-way and a square root when it only listens.
        double detectionRange = spec.baseRange() * Math.pow(Math.max(0.0, strength), active ? 0.25 : 0.5)
                * depthFactor * layer * weather;
        // Zero rather than the arithmetic, which at a range of nothing is a number with twenty-seven digits
        // in it. A set measures nothing off its own transmitter.
        double snr = self ? 0.0 : Math.pow(detectionRange / range, active ? 4.0 : 2.0);
        return new Look(active, range, detectionRange, strength, snr, path.meanDepth(), depthFactor, layer,
                weather, dry, path.blocked(), self);
    }

    /**
     * @return true if the set and the target are on opposite sides of the thermocline. Running depth is to a
     *      hydrophone what mast height is to a radar: the one placement decision that changes what it hears.
     */
    public static boolean crossesLayer(ReconTerrain terrain, SensorSnapshot sensor,
                                       double tx, double ty, double tz) {
        double here = terrain.heightAt(sensor.x(), sensor.z()) - sensor.eyeY();
        double there = terrain.heightAt(tx, tz) - ty;
        return (here > LAYER_DEPTH) != (there > LAYER_DEPTH);
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
