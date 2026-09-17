package com.wf.wfballistics.recon.fuse;

import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.recon.Band;
import com.wf.wfballistics.recon.ContactClass;
import com.wf.wfballistics.recon.detect.Plot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Several probes' looks at one target, intersected into one fix. */
public final class CrossFix {

    /**
     * Blocks added to the association gate, so two looks at a stationary target with tiny errors still find each
     * other.
     */
    private static final double MIN_GATE = 4.0;
    /** Floor on any measurement's standard deviation, in blocks. */
    private static final double MIN_SIGMA = 0.25;

    private CrossFix() {
    }

    /**
     * @param plots every plot every sensor formed this pass, already merged within each sensor
     * @return one plot per believed target, each the intersection of everything that saw it
     */
    public static List<Plot> fuse(List<Plot> plots) {
        WorldThread.assertOff("recon cross fix");
        if (plots.size() < 2) {
            return plots;
        }
        List<Plot> ordered = new ArrayList<>(plots);
        ordered.sort(Comparator.comparingDouble(Plot::strength).reversed());

        List<List<Plot>> clusters = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Plot plot = ordered.get(i);
            List<Plot> into = null;
            for (int j = 0; j < clusters.size(); j++) {
                if (associates(clusters.get(j).get(0), plot)) {
                    into = clusters.get(j);
                    break;
                }
            }
            if (into == null) {
                List<Plot> fresh = new ArrayList<>(2);
                fresh.add(plot);
                clusters.add(fresh);
            } else {
                into.add(plot);
            }
        }

        List<Plot> out = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            List<Plot> cluster = clusters.get(i);
            out.add(cluster.size() == 1 ? cluster.get(0) : solve(cluster));
        }
        return out;
    }

    /**
     * @return true if these two could be looks at the same thing. Deliberately generous: a missed association
     *      costs a duplicate track, which the tracker then has to carry and a turret may shoot at twice, whereas a
     *      wrong one costs a fix pulled between two targets that the next sweep corrects.
     */
    private static boolean associates(Plot a, Plot b) {
        double gate = a.errorRadius() + b.errorRadius() + MIN_GATE;
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= gate * gate;
    }

    /**
     * Intersect looks that are already known to be at one thing.
     *
     * @param cluster one or more looks at the same thing, in any order
     * @return the intersection, or the single look unchanged. A single look is not an error: one geophone is
     *      a legitimate (and deliberately poor) fix, and dressing it up as anything else would hide the exact
     *      thing an array is built to fix.
     */
    public static Plot intersect(List<Plot> cluster) {
        return cluster.size() == 1 ? cluster.get(0) : solve(cluster);
    }

    /**
     * The weighted least-squares fix over a cluster of two or more looks.
     */
    private static Plot solve(List<Plot> cluster) {
        double h00 = 0.0;
        double h01 = 0.0;
        double h11 = 0.0;
        double b0 = 0.0;
        double b1 = 0.0;
        double yWeight = 0.0;
        double ySum = 0.0;

        for (int i = 0; i < cluster.size(); i++) {
            Plot plot = cluster.get(i);
            double ux = plot.bearingX();
            double uz = plot.bearingZ();
            double px = -uz;
            double pz = ux;
            double wr = 1.0 / sq(Math.max(MIN_SIGMA, plot.rangeError()));
            double wc = 1.0 / sq(Math.max(MIN_SIGMA, plot.crossError()));

            h00 += ux * ux * wr + px * px * wc;
            h01 += ux * uz * wr + px * pz * wc;
            h11 += uz * uz * wr + pz * pz * wc;

            double alongM = plot.x() * ux + plot.z() * uz;
            double acrossM = plot.x() * px + plot.z() * pz;
            b0 += ux * alongM * wr + px * acrossM * wc;
            b1 += uz * alongM * wr + pz * acrossM * wc;

            double wy = 1.0 / sq(Math.max(MIN_SIGMA, plot.errorRadius()));
            yWeight += wy;
            ySum += plot.y() * wy;
        }

        Plot strongest = cluster.get(0);
        for (int i = 1; i < cluster.size(); i++) {
            if (cluster.get(i).strength() > strongest.strength()) {
                strongest = cluster.get(i);
            }
        }
        double det = h00 * h11 - h01 * h01;
        if (!(det > 1.0E-9)) {
            return rewrap(strongest, cluster, strongest.x(), strongest.y(), strongest.z(),
                    strongest.crossError(), strongest.rangeError());
        }

        double fx = (h11 * b0 - h01 * b1) / det;
        double fz = (h00 * b1 - h01 * b0) / det;
        double fy = ySum / yWeight;

        // Covariance is H inverse; its eigenvalues are the squared semi-axes of the error ellipse.
        double c00 = h11 / det;
        double c01 = -h01 / det;
        double c11 = h00 / det;
        double mean = (c00 + c11) * 0.5;
        double diff = Math.sqrt(sq((c00 - c11) * 0.5) + c01 * c01);
        double major = Math.sqrt(Math.max(0.0, mean + diff));
        double minor = Math.sqrt(Math.max(0.0, mean - diff));

        double best = Double.MAX_VALUE;
        for (int i = 0; i < cluster.size(); i++) {
            best = Math.min(best, cluster.get(i).errorRadius());
        }
        double cross = Math.min(major, best);
        double range = Math.min(minor, cross);
        return rewrap(strongest, cluster, fx, fy, fz, cross, range);
    }

    /** Rebuild one plot from a cluster's consensus. */
    private static Plot rewrap(Plot strongest, List<Plot> cluster,
                               double x, double y, double z, double crossError, double rangeError) {
        int mask = 0;
        int merged = 0;
        int hops = 0;
        double strength = 0.0;
        boolean friendly = false;
        Band band = strongest.band();
        ContactClass hint = ContactClass.UNKNOWN;
        for (int i = 0; i < cluster.size(); i++) {
            Plot plot = cluster.get(i);
            mask |= plot.bandMask();
            merged = Math.max(merged, plot.merged());
            hops = Math.max(hops, plot.hops());
            friendly |= plot.friendly();
            if (plot.strength() > strength) {
                strength = plot.strength();
                band = plot.band();
            }
            if (hint == ContactClass.UNKNOWN) {
                hint = plot.hint();
            }
        }
        return new Plot(band, mask, x, y, z, strongest.bearingX(), strongest.bearingZ(),
                crossError, rangeError, strength, hint, merged, friendly, hops, strongest.gameTime());
    }

    private static double sq(double v) {
        return v * v;
    }
}
