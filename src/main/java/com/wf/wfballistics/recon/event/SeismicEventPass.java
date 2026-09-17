package com.wf.wfballistics.recon.event;

import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.recon.Band;
import com.wf.wfballistics.recon.ContactClass;
import com.wf.wfballistics.recon.detect.Plot;
import com.wf.wfballistics.recon.fuse.CrossFix;
import com.wf.wfballistics.recon.propagate.SeismicPropagator;
import com.wf.wfballistics.recon.snapshot.ReconTerrain;
import com.wf.wfballistics.recon.snapshot.SensorSnapshot;

import java.util.ArrayList;
import java.util.List;

/** Every geophone on a network reading its trace, and what the network concludes from all of them at once. */
public final class SeismicEventPass {

    /** Blocks past which a station does not do the arithmetic at all. */
    private static final double MAX_REACH_SQ = SeismicEvents.MAX_REACH * SeismicEvents.MAX_REACH;

    private SeismicEventPass() {
    }

    /** One station's ear, as everything the pass needs and nothing else. */
    public record Ear(SensorSnapshot sensor, ReconTerrain terrain) {
    }

    /**
     * @param ears the seismic sensors sweeping this pass, already routed and weather-corrected
     * @param blasts every blast still readable in this dimension
     * @return one fix per event anybody heard, in the order the events happened
     */
    public static List<SeismicFix> run(List<Ear> ears, List<BlastEvent> blasts, long gameTime) {
        WorldThread.assertOff("recon seismic event pass");
        if (ears.isEmpty() || blasts.isEmpty()) {
            return List.of();
        }
        List<SeismicFix> out = new ArrayList<>();
        for (int b = 0; b < blasts.size(); b++) {
            SeismicFix fix = fix(ears, blasts.get(b), gameTime);
            if (fix != null) {
                out.add(fix);
            }
        }
        return List.copyOf(out);
    }

    /**
     * @return what the net makes of one blast, or null if nothing heard it.
     */
    private static SeismicFix fix(List<Ear> ears, BlastEvent blast, long gameTime) {
        List<Plot> plots = new ArrayList<>(ears.size());
        SeismicPropagator.Look best = null;
        SensorSnapshot bestEar = null;

        for (int i = 0; i < ears.size(); i++) {
            SensorSnapshot sensor = ears.get(i).sensor();
            if (sensor.spec().band() != Band.SEISMIC
                    || sensor.distanceSqTo(blast.x(), blast.y(), blast.z()) > MAX_REACH_SQ) {
                continue;
            }
            SeismicPropagator.Look look = SeismicPropagator.INSTANCE.hear(sensor, ears.get(i).terrain(),
                    blast.x(), blast.y(), blast.z(), blast.energy());
            if (look.snr() < 1.0) {
                continue;
            }
            plots.add(SeismicPropagator.INSTANCE.plot(sensor, blast.x(), blast.y(), blast.z(), look,
                            ContactClass.UNKNOWN,
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
        double energy = SeismicPropagator.energyAt(best, range);
        double high = SeismicPropagator.energyAt(best, range + error);
        double low = SeismicPropagator.energyAt(best, Math.max(1.0, range - error));
        return new SeismicFix(blast.id(), fused.x(), fused.y(), fused.z(), error,
                energy, Math.max(high - energy, energy - low), plots.size(), Band.SEISMIC,
                blast.subsurface(), gameTime);
    }
}
