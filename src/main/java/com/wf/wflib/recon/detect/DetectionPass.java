package com.wf.wflib.recon.detect;

import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.propagate.Propagator;
import com.wf.wflib.recon.snapshot.ReconTerrain;
import com.wf.wflib.recon.snapshot.SensorSnapshot;
import com.wf.wflib.recon.snapshot.TargetSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * One sensor's look: run the band's model over every target it might see, then fuse whatever it cannot tell apart.
 */
public final class DetectionPass {

    private DetectionPass() {
    }

    /**
     * @return the plots this sensor formed, already merged at its own resolution.
     */
    public static List<Plot> run(SensorSnapshot sensor, List<TargetSnapshot> targets,
                                 ReconTerrain terrain, Propagator propagator, long gameTime) {
        WorldThread.assertOff("recon detection pass");
        double linkScale = sensor.linkErrorScale();
        List<Plot> raw = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Plot plot = propagator.detect(sensor, targets.get(i), terrain, gameTime);
            if (plot != null) {
                raw.add(plot.degraded(linkScale));
            }
        }
        return merge(raw);
    }

    /** Fuse plots that fall inside one resolution cell. */
    private static List<Plot> merge(List<Plot> plots) {
        if (plots.size() < 2) {
            return plots;
        }
        List<Plot> out = new ArrayList<>(plots.size());
        for (int i = 0; i < plots.size(); i++) {
            Plot plot = plots.get(i);
            int into = -1;
            for (int j = 0; j < out.size(); j++) {
                Plot existing = out.get(j);
                double cell = Math.max(existing.errorRadius(), plot.errorRadius());
                double dx = existing.x() - plot.x();
                double dy = existing.y() - plot.y();
                double dz = existing.z() - plot.z();
                if (dx * dx + dy * dy + dz * dz <= cell * cell) {
                    into = j;
                    break;
                }
            }
            if (into < 0) {
                out.add(plot);
                continue;
            }
            out.set(into, fuse(out.get(into), plot));
        }
        return out;
    }

    /** Two returns into one. */
    private static Plot fuse(Plot a, Plot b) {
        int count = a.merged() + b.merged();
        double wa = (double) a.merged() / count;
        double wb = 1.0 - wa;
        ContactClass hint = a.hint() == b.hint() ? a.hint() : ContactClass.UNKNOWN;
        Plot strong = a.strength() >= b.strength() ? a : b;
        return new Plot(strong.band(), a.bandMask() | b.bandMask(),
                a.x() * wa + b.x() * wb,
                a.y() * wa + b.y() * wb,
                a.z() * wa + b.z() * wb,
                strong.bearingX(), strong.bearingZ(),
                Math.min(a.crossError(), b.crossError()),
                Math.min(a.rangeError(), b.rangeError()),
                Math.max(a.strength(), b.strength()),
                hint, count, a.friendly() && b.friendly(),
                Math.max(a.hops(), b.hops()), a.gameTime());
    }
}
