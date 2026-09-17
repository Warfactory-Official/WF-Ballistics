package com.wf.wfballistics.recon.track;

import com.wf.wfballistics.recon.alert.Alert;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** One network's whole belief about the world at one instant. */
public final class TrackPicture {

    public static final TrackPicture EMPTY = new TrackPicture(List.of(), List.of(), 0L);

    private final List<Track> tracks;
    private final List<Alert> alerts;
    private final long gameTime;

    public TrackPicture(List<Track> tracks, List<Alert> alerts, long gameTime) {
        this.tracks = tracks;
        this.alerts = alerts;
        this.gameTime = gameTime;
    }

    public List<Track> tracks() {
        return tracks;
    }

    public List<Alert> alerts() {
        return alerts;
    }

    public long gameTime() {
        return gameTime;
    }

    public Optional<Track> byId(TrackId id) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id().equals(id)) {
                return Optional.of(tracks.get(i));
            }
        }
        return Optional.empty();
    }

    /**
     * @return the closest track to a point that passes the filter, within range, or null.
     */
    @Nullable
    public Track nearest(double x, double y, double z, double range, Predicate<Track> filter) {
        Track best = null;
        double bestSq = range * range;
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            double distSq = track.distanceSqTo(x, y, z);
            if (distSq <= bestSq && filter.test(track)) {
                bestSq = distSq;
                best = track;
            }
        }
        return best;
    }
}
