package com.wf.wflib.client.cam;

import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.drone.cam.GimbalMath;

import java.util.HashMap;
import java.util.Map;

/** Where a camera is pointed <em>right now</em>, as opposed to where it was when the server last said. */
public final class FeedGimbal {

    /** Movement below this between two publishes is the gimbal being stationary, not creeping. */
    private static final float STALL = 0.05f;
    /** Nanoseconds in a tick. The slew rate is quoted per tick, and real time is what we have. */
    private static final double TICK_NANOS = 5.0e7;
    /** Top speed of the zoom ramp, in degrees of field of view per tick. */
    private static final float FOV_RATE = 12.0f;

    private static final Map<Integer, Sim> SIMS = new HashMap<>();

    private FeedGimbal() {
    }

    /** A fresh sample from the server. Called from {@code CameraFeedCache.accept}. */
    static void accept(CameraFeed feed) {
        Sim sim = SIMS.get(feed.feedId());
        if (sim == null) {
            SIMS.put(feed.feedId(), new Sim(feed));
        } else {
            sim.reconcile(feed);
        }
    }

    static void forget(int feedId) {
        SIMS.remove(feedId);
    }

    public static void clear() {
        SIMS.clear();
    }

    public static float yaw(CameraFeed feed) {
        return advance(feed).yaw;
    }

    public static float pitch(CameraFeed feed) {
        return advance(feed).pitch;
    }

    /** @return the field of view to draw with, ramping toward what the operator asked for. */
    public static float fov(CameraFeed feed) {
        return advance(feed).fov;
    }

    /**
     * Idempotent within a frame in the only way that matters: the second call in the same frame advances by a few
     * microseconds of slew, which is nothing.
     */
    private static Sim advance(CameraFeed feed) {
        Sim sim = SIMS.computeIfAbsent(feed.feedId(), id -> new Sim(feed));
        sim.advance(feed);
        return sim;
    }

    static float wrap(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    private static final class Sim {

        private float yaw;
        private float pitch;
        private float fov;
        private float publishedYaw;
        private float publishedPitch;
        private long nanos = System.nanoTime();

        /** The angle the server stopped at while still being asked to move, and which way it was asked. */
        private boolean walled;
        private float wallYaw;
        private float wallSign;
        /** The same for pitch, which has its own limits and hits them more often than yaw does. */
        private boolean walledPitch;
        private float wallPitch;
        private float wallPitchSign;

        Sim(CameraFeed feed) {
            this.yaw = feed.yaw();
            this.pitch = feed.pitch();
            this.fov = feed.fov();
            this.publishedYaw = feed.yaw();
            this.publishedPitch = feed.pitch();
        }

        void reconcile(CameraFeed feed) {
            boolean movedYaw = Math.abs(wrap(feed.yaw() - this.publishedYaw)) > STALL;
            boolean movedPitch = Math.abs(feed.pitch() - this.publishedPitch) > STALL;
            this.publishedYaw = feed.yaw();
            this.publishedPitch = feed.pitch();

            if (movedYaw) {
                this.walled = false;
            } else {
                this.yaw = feed.yaw();
                float wanted = wrap(targetYaw(feed) - feed.yaw());
                this.walled = Math.abs(wanted) > STALL;
                this.wallYaw = feed.yaw();
                this.wallSign = Math.signum(wanted);
            }
            if (movedPitch) {
                this.walledPitch = false;
            } else {
                this.pitch = feed.pitch();
                float wanted = targetPitch(feed) - feed.pitch();
                this.walledPitch = Math.abs(wanted) > STALL;
                this.wallPitch = feed.pitch();
                this.wallPitchSign = Math.signum(wanted);
            }
        }

        void advance(CameraFeed feed) {
            long now = System.nanoTime();
            float ticks = (float) ((now - this.nanos) / TICK_NANOS);
            this.nanos = now;
            if (ticks <= 0.0f) {
                return;
            }
            float step = Math.min(ticks, 2.0f);
            float rate = Math.max(0.0f, feed.slewRate());

            float next = wrap(this.yaw + GimbalMath.approach(wrap(targetYaw(feed) - this.yaw), step, rate));
            if (this.walled && Math.signum(wrap(next - this.wallYaw)) == this.wallSign) {
                next = this.wallYaw;
            }
            this.yaw = next;

            float nextPitch = this.pitch + GimbalMath.approach(targetPitch(feed) - this.pitch, step, rate);
            if (this.walledPitch && Math.signum(nextPitch - this.wallPitch) == this.wallPitchSign) {
                nextPitch = this.wallPitch;
            }
            this.pitch = nextPitch;

            this.fov += GimbalMath.approach(targetFov(feed) - this.fov, step, FOV_RATE);
        }

        /**
         * @return where this camera is being asked to look. The operator's own command for the feed they are
         *      driving (instant, local, and the whole reason this is not just interpolation), and the last
         *      published angle for every other feed, which is the best answer available for one you do not aim.
         */
        private static float targetYaw(CameraFeed feed) {
            return driving(feed) ? CameraLink.yaw() : feed.yaw();
        }

        private static float targetPitch(CameraFeed feed) {
            return driving(feed) ? CameraLink.pitch() : feed.pitch();
        }

        /**
         * @return the field of view being asked for. For the operator's own feed that is worked out from
         *      their scroll wheel and the optic the feed describes, so it is current; for anyone else's it is
         *      whatever the last packet said. Clamped exactly as the server clamps it, because a target the
         *      server will never agree to is one this would ramp to and then sit at, wrong, forever.
         */
        private static float targetFov(CameraFeed feed) {
            if (!driving(feed)) {
                return feed.fov();
            }
            float zoom = Math.max(1.0f, Math.min(feed.maxZoom(), CameraLink.zoom()));
            return feed.fovDeg() / zoom;
        }

        private static boolean driving(CameraFeed feed) {
            return CameraLink.active() && CameraLink.feedId() == feed.feedId();
        }
    }
}
