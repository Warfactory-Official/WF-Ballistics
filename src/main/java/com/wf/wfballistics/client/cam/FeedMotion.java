package com.wf.wfballistics.client.cam;

import com.wf.wfballistics.drone.cam.CameraFeed;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Where a camera <em>is</em>, between the five times a second it says so. */
public final class FeedMotion {

    /** Nanoseconds in a tick. Samples are stamped in server ticks; the walk between them runs in real time. */
    private static final double TICK_NANOS = 5.0e7;
    /**
     * Ticks of headroom added to each walk, so an interval's worth of movement is spread over slightly longer than
     * an interval.
     */
    private static final int SLACK_TICKS = 1;
    /** Longest gap between two samples still treated as flight. */
    private static final int MAX_WALK_TICKS = 8;
    /** Blocks per tick above which a jump is a relocation rather than flight. */
    private static final double TELEPORT_SPEED = 8.0;

    private static final Map<Integer, Walk> WALKS = new HashMap<>();

    private FeedMotion() {
    }

    /** A fresh sample from the server. Called from {@code CameraFeedCache.accept}, beside the gimbal's. */
    static void accept(CameraFeed feed) {
        Walk walk = WALKS.get(feed.feedId());
        if (walk == null) {
            WALKS.put(feed.feedId(), new Walk(feed));
        } else {
            walk.accept(feed);
        }
    }

    /** @return where to put the lens right now. The position the whole feed pass is built around. */
    public static Vec3 position(CameraFeed feed) {
        return WALKS.computeIfAbsent(feed.feedId(), id -> new Walk(feed)).position();
    }

    static void forget(int feedId) {
        WALKS.remove(feedId);
    }

    public static void clear() {
        WALKS.clear();
    }

    /** One camera's walk from the last place it was drawn to the last place it was reported. */
    private static final class Walk {

        private double fromX;
        private double fromY;
        private double fromZ;
        private double toX;
        private double toY;
        private double toZ;
        /** Game time of {@link #toX}, so the next sample can measure the interval it should be walked over. */
        private long sampledAt;
        private long startNanos;
        private double durationNanos;

        Walk(CameraFeed feed) {
            snapTo(feed);
        }

        void accept(CameraFeed feed) {
            long elapsed = feed.gameTime() - this.sampledAt;
            if (elapsed <= 0) {
                return;
            }
            double moved = Math.sqrt(sqr(feed.x() - this.toX) + sqr(feed.y() - this.toY)
                    + sqr(feed.z() - this.toZ));
            if (elapsed > MAX_WALK_TICKS || moved > TELEPORT_SPEED * elapsed) {
                snapTo(feed);
                return;
            }
            Vec3 here = position();
            this.fromX = here.x;
            this.fromY = here.y;
            this.fromZ = here.z;
            this.toX = feed.x();
            this.toY = feed.y();
            this.toZ = feed.z();
            this.sampledAt = feed.gameTime();
            this.startNanos = System.nanoTime();
            this.durationNanos = (elapsed + SLACK_TICKS) * TICK_NANOS;
        }

        Vec3 position() {
            double t = this.durationNanos <= 0.0
                    ? 1.0
                    : (System.nanoTime() - this.startNanos) / this.durationNanos;
            t = Mth.clamp(t, 0.0, 1.0);
            return new Vec3(Mth.lerp(t, this.fromX, this.toX),
                    Mth.lerp(t, this.fromY, this.toY),
                    Mth.lerp(t, this.fromZ, this.toZ));
        }

        private void snapTo(CameraFeed feed) {
            this.fromX = this.toX = feed.x();
            this.fromY = this.toY = feed.y();
            this.fromZ = this.toZ = feed.z();
            this.sampledAt = feed.gameTime();
            this.startNanos = System.nanoTime();
            this.durationNanos = 0.0;
        }

        private static double sqr(double v) {
            return v * v;
        }
    }
}
