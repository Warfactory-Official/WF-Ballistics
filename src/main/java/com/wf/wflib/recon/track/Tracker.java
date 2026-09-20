package com.wf.wflib.recon.track;

import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.alert.Alert;
import com.wf.wflib.recon.alert.AlertTier;
import com.wf.wflib.recon.alert.EngagementAuthority;
import com.wf.wflib.recon.detect.Plot;
import com.wf.wflib.recon.propagate.RadarPropagator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plots in, tracks out. */
public final class Tracker {

    /** Position and velocity gains. */
    public static final double ALPHA = 0.45;
    public static final double BETA = 0.15;

    /**
     * Plots before a track is worth an alarm, and before its velocity is worth shooting on.
     */
    public static final int CONFIRM_HITS = 2;
    public static final int FIRM_HITS = 5;
    /** Ticks a track coasts unseen before it is dropped. */
    public static final int COAST_TICKS = 100;
    /** Association gate floor, in blocks. */
    public static final double MIN_GATE = 4.0;

    private final Map<Long, Working> tracks = new LinkedHashMap<>();
    private long nextId = 1L;

    /** Fold one sweep's plots into the picture. */
    public void update(List<Plot> plots, long gameTime) {
        List<Plot> ordered = new ArrayList<>(plots);
        ordered.sort(Comparator.comparingDouble(Plot::strength).reversed());

        List<Working> unclaimed = new ArrayList<>(tracks.values());
        for (int i = 0; i < ordered.size(); i++) {
            Plot plot = ordered.get(i);
            Working best = null;
            double bestSq = Double.MAX_VALUE;
            for (int j = 0; j < unclaimed.size(); j++) {
                Working candidate = unclaimed.get(j);
                double dt = Math.max(1, gameTime - candidate.lastSeen);
                double gate = plot.errorRadius() + candidate.errorRadius + MIN_GATE
                        + candidate.speed() * dt;
                double distSq = candidate.predictedDistanceSq(plot, gameTime);
                if (distSq <= gate * gate && distSq < bestSq) {
                    bestSq = distSq;
                    best = candidate;
                }
            }
            if (best == null) {
                Working fresh = new Working(nextId++, plot, gameTime);
                tracks.put(fresh.id, fresh);
            } else {
                unclaimed.remove(best);
                best.update(plot, gameTime);
            }
        }

        for (Iterator<Map.Entry<Long, Working>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            if (gameTime - it.next().getValue().lastSeen > COAST_TICKS) {
                it.remove();
            }
        }
    }

    /** Freeze the current belief into something the rest of the game can read. */
    public TrackPicture publish(long gameTime) {
        List<Track> out = new ArrayList<>(tracks.size());
        List<Alert> alerts = new ArrayList<>();
        for (Working working : tracks.values()) {
            Track track = working.freeze();
            out.add(track);
            EngagementAuthority authority = EngagementAuthority.of(track);
            if (authority != working.announced) {
                working.announced = authority;
                if (authority != EngagementAuthority.LOG_ONLY) {
                    alerts.add(new Alert(track.id(), tierFor(authority), authority,
                            track.x(), track.y(), track.z(), track.guess(), gameTime));
                }
            }
        }
        return new TrackPicture(List.copyOf(out), List.copyOf(alerts), gameTime);
    }

    public int size() {
        return tracks.size();
    }

    private static AlertTier tierFor(EngagementAuthority authority) {
        return authority == EngagementAuthority.WEAPONS_RELEASE ? AlertTier.CRITICAL : AlertTier.WARNING;
    }

    /**
     * A track being filtered. Not published: the world only ever sees {@link Track}.
     */
    private static final class Working {

        private final long id;
        private double x;
        private double y;
        private double z;
        private double vx;
        private double vy;
        private double vz;
        private double errorRadius;
        private int hits;
        private long lastSeen;
        private ContactClass guess;
        private float confidence;
        private int countEstimate;
        private IffState iff;
        private int bandMask;
        private int hops;
        private EngagementAuthority announced = EngagementAuthority.LOG_ONLY;

        private Working(long id, Plot seed, long gameTime) {
            this.id = id;
            this.x = seed.x();
            this.y = seed.y();
            this.z = seed.z();
            this.errorRadius = seed.errorRadius();
            this.hits = 1;
            this.lastSeen = gameTime;
            this.guess = seed.hint();
            this.confidence = certainty(seed);
            this.countEstimate = seed.merged();
            this.iff = seed.friendly() ? IffState.FRIENDLY : IffState.UNKNOWN;
            this.bandMask = seed.bandMask();
            this.hops = seed.hops();
        }

        private double speed() {
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        }

        private double predictedDistanceSq(Plot plot, long gameTime) {
            double dt = gameTime - lastSeen;
            double dx = x + vx * dt - plot.x();
            double dy = y + vy * dt - plot.y();
            double dz = z + vz * dt - plot.z();
            return dx * dx + dy * dy + dz * dz;
        }

        private void update(Plot plot, long gameTime) {
            double dt = Math.max(1.0, gameTime - lastSeen);
            double px = x + vx * dt;
            double py = y + vy * dt;
            double pz = z + vz * dt;
            double rx = plot.x() - px;
            double ry = plot.y() - py;
            double rz = plot.z() - pz;
            x = px + ALPHA * rx;
            y = py + ALPHA * ry;
            z = pz + ALPHA * rz;
            vx += BETA * rx / dt;
            vy += BETA * ry / dt;
            vz += BETA * rz / dt;

            errorRadius = plot.errorRadius();
            countEstimate = plot.merged();
            lastSeen = gameTime;
            hits++;
            hops = plot.hops();
            bandMask |= plot.bandMask();
            if (plot.hint() != ContactClass.UNKNOWN) {
                guess = plot.hint();
            }
            confidence += (certainty(plot) - confidence) * 0.4f;
            iff = plot.friendly() ? IffState.FRIENDLY : IffState.UNKNOWN;
        }

        private Track freeze() {
            TrackQuality earned = hits >= FIRM_HITS ? TrackQuality.FIRM
                    : hits >= CONFIRM_HITS ? TrackQuality.CONFIRMED : TrackQuality.TENTATIVE;
            TrackQuality quality = earned.cappedAt(TrackQuality.capFor(hops));
            return new Track(new TrackId(id), x, y, z, vx, vy, vz, errorRadius, quality,
                    guess, confidence, countEstimate, iff, bandMask, hops, lastSeen);
        }

        /**
         * How much of a classification this return can support: nothing at the edge of detection, everything once
         * the signal is well above what it took to see the target at all.
         */
        private static float certainty(Plot plot) {
            double scaled = (plot.strength() - 1.0) / (RadarPropagator.CLASSIFY_SNR - 1.0);
            return (float) Math.max(0.0, Math.min(1.0, scaled));
        }
    }
}
