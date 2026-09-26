package com.wf.wflib.drone.cam;

/**
 * One camera's state, as the wire carries it.
 *
 * @param feedId the drone's entity id. Transient by design: a feed is a live thing, and a monitor that
 *      outlives its drone should say so rather than quietly bind to whatever reused the id
 * @param kind {@link #DRONE}, {@link #FIXED} or {@link #GUIDED}; picks the OSD
 * @param yaw absolute gimbal heading in degrees, already clamped and slewed by the server
 * @param fov vertical field of view in degrees, after zoom
 * @param fovDeg the optic's field of view at 1x, and {@code maxZoom} how far it stops down. The pair is
 *      here for the same reason {@code slewRate} is: with them the client can work out the field
 *      of view its own scroll wheel is asking for and ramp to it immediately, instead of waiting
 *      a fifth of a second to be told and then jumping. They also let it clamp the zoom it
 *      displays to what the optic can actually do
 * @param slewRate degrees per tick this gimbal can turn. On the wire purely so the client can run the same
 *      slew locally between publishes: the angle here is sampled five times a second, and a
 *      picture that only changes five times a second reads as a broken camera however high the
 *      framerate is. See {@code FeedGimbal}
 * @param battery 0..1, drawn on the OSD and the reason a feed eventually stops
 * @param link 0..1 downlink quality; drives macroblocking and noise strength in the post chain, so the
 *      picture degrading is the same event as the datalink degrading rather than a separate effect
 * @param gameTime the tick this state was sampled at; the client renders nothing older than a second or two
 */
public record CameraFeed(int feedId, byte kind, double x, double y, double z,
                         float yaw, float pitch, float fov, float fovDeg, float maxZoom, float slewRate,
                         byte mode, byte modeMask, float battery, float link,
                         float speed, float altitude, long gameTime) {

    public static final byte DRONE = 0;
    public static final byte FIXED = 1;
    /** TV round: battery = fuel; burnt out != blind. */
    public static final byte GUIDED = 2;
    /** Link at or below => signal-loss card; a TV round stops obeying its operator. */
    public static final float USABLE_LINK = 0.05f;

    /** Ticks a client keeps drawing a feed after the last update before declaring signal loss. */
    public static final int STALE_TICKS = 40;

    public CameraMode modeValue() {
        return CameraMode.byOrdinal(this.mode);
    }

    public boolean fitted(CameraMode candidate) {
        return (this.modeMask & (1 << candidate.ordinal())) != 0;
    }

    /**
     * @return true if the link is good enough to draw at all. Below this the operator gets a signal-loss card
     *      rather than a picture made almost entirely of compression artifacts, because the latter is inadequate
     *      feedback dressed up as a feature: it looks like the mod is broken.
     */
    public boolean usable() {
        return this.link > USABLE_LINK && (this.battery > 0.0f || this.kind == GUIDED);
    }
}
