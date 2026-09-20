package com.wf.wflib.recon.event;

import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.detect.Plot;
import com.wf.wflib.recon.fuse.CrossFix;
import com.wf.wflib.recon.propagate.SonarPropagator;
import com.wf.wflib.recon.snapshot.SensorSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Every hydrophone on a network reading its trace. The mirror of {@link SeismicEventPass}, and the reason a
 * depth charge is worth firing blind: an underwater detonation is the loudest thing this band ever hears, and
 * it is heard by sets that can localise it across a bearing rather than only around a circle.
 */
public final class SonarEventPass {

    private static final double MAX_REACH_SQ = SeismicEvents.MAX_REACH * SeismicEvents.MAX_REACH;

    private SonarEventPass() {
    }

    /**
     * @param ears every sensor sweeping this pass, of any band. Filtered here rather than by the caller, the
     *      way the seismic pass does it.
     * @return one fix per event anybody heard, in the order the events happened
     */
    public static List<SeismicFix> run(List<SeismicEventPass.Ear> ears, List<BlastEvent> blasts,
                                       long gameTime) {
        WorldThread.assertOff("recon sonar event pass");
        if (ears.isEmpty() || blasts.isEmpty()) {
            return List.of();
        }
        List<SeismicFix> out = new ArrayList<>();
        for (int b = 0; b < blasts.size(); b++) {
            BlastEvent blast = blasts.get(b);
            if (blast.waterEnergy() <= 0.0f) {
                continue;
            }
            SeismicFix fix = fix(ears, blast, gameTime);
            if (fix != null) {
                out.add(fix);
            }
        }
        return List.copyOf(out);
    }

    /**
     * @return what the net makes of one blast, or null if nothing heard it.
     */
    private static SeismicFix fix(List<SeismicEventPass.Ear> ears, BlastEvent blast, long gameTime) {
        List<Plot> plots = new ArrayList<>(ears.size());
        SonarPropagator.Look best = null;
        SensorSnapshot bestEar = null;

        for (int i = 0; i < ears.size(); i++) {
            SensorSnapshot sensor = ears.get(i).sensor();
            if (sensor.spec().band() != Band.SONAR
                    || sensor.distanceSqTo(blast.x(), blast.y(), blast.z()) > MAX_REACH_SQ) {
                continue;
            }
            SonarPropagator.Look look = SonarPropagator.INSTANCE.hear(sensor, ears.get(i).terrain(),
                    blast.x(), blast.y(), blast.z(), blast.waterEnergy());
            if (!look.detected()) {
                continue;
            }
            plots.add(SonarPropagator.INSTANCE.plot(sensor, blast.x(), blast.y(), blast.z(), look,
                            ContactClass.UNKNOWN, false,
                            sensor.sensorId() * 0x9E3779B97F4A7C15L ^ blast.id(), gameTime)
                    .degraded(sensor.linkErrorScale()));
            if (best == null || look.snr() > best.snr()) {
                best = look;
                bestEar = sensor;
            }
        }
        if (plots.isEmpty()) {
            return null;
        }

        Plot fused = CrossFix.intersect(plots);
        double error = fused.errorRadius();
        double range = Math.sqrt(bestEar.distanceSqTo(fused.x(), fused.y(), fused.z()));
        double energy = SonarPropagator.energyAt(bestEar, best, range);
        double high = SonarPropagator.energyAt(bestEar, best, range + error);
        double low = SonarPropagator.energyAt(bestEar, best, Math.max(1.0, range - error));
        return new SeismicFix(blast.id(), fused.x(), fused.y(), fused.z(), error,
                energy, Math.max(high - energy, energy - low), plots.size(), Band.SONAR,
                blast.subsurface(), gameTime);
    }
}
