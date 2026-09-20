package com.wf.wflib.client.cam;

import com.wf.wflib.drone.cam.CameraMode;
import com.wf.wflib.network.CameraControlPacket;
import com.wf.wflib.network.WFNetwork;

/** The client's end of one operator's control of one camera. */
public final class CameraLink {

    private static int feedId;
    private static boolean active;
    private static float yaw;
    private static float pitch;
    private static float zoom = 1.0f;
    private static CameraMode mode = CameraMode.OPTICAL;
    private static int sinceRenew;
    private static boolean dirty;

    private CameraLink() {
    }

    /**
     * Take control of a feed, seeding the operator's intent from wherever the gimbal currently is so that opening a
     * screen does not swing the camera.
     */
    public static void open(int id) {
        feedId = id;
        active = true;
        sinceRenew = CameraControlPacket.RENEW_INTERVAL;
        var feed = CameraFeedCache.feed(id);
        if (feed != null) {
            yaw = feed.yaw();
            pitch = feed.pitch();
            mode = feed.modeValue();
        }
        dirty = true;
    }

    public static void close() {
        if (!active) {
            return;
        }
        active = false;
        WFNetwork.sendToServer(new CameraControlPacket(feedId, yaw, pitch, zoom,
                (byte) mode.ordinal(), false));
    }

    public static boolean active() {
        return active;
    }

    public static int feedId() {
        return feedId;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static float zoom() {
        return zoom;
    }

    public static CameraMode mode() {
        return mode;
    }

    /** Pan. Pitch is clamped here as well as on the server, so the display does not show an angle it will never get. */
    public static void pan(double dYaw, double dPitch) {
        yaw = (float) (yaw + dYaw);
        pitch = (float) Math.max(-90.0, Math.min(90.0, pitch + dPitch));
        dirty = true;
    }

    /** Zoom. */
    public static void zoomBy(double steps) {
        var feed = CameraFeedCache.feed(feedId);
        float max = feed == null ? 8.0f : Math.max(1.0f, feed.maxZoom());
        zoom = (float) Math.max(1.0, Math.min(max, zoom * Math.pow(1.2, steps)));
        dirty = true;
    }

    /** Ask for the next fitted mode. */
    public static void cycleMode() {
        var feed = CameraFeedCache.feed(feedId);
        for (int i = 1; i <= CameraMode.VALUES.length; i++) {
            CameraMode candidate = CameraMode.byOrdinal(mode.ordinal() + i);
            if (feed == null || feed.fitted(candidate)) {
                mode = candidate;
                dirty = true;
                return;
            }
        }
    }

    /**
     * Send if anything moved, and otherwise keep the subscription alive. Called from the client tick.
     */
    public static void tick() {
        if (!active) {
            return;
        }
        if (!dirty && --sinceRenew > 0) {
            return;
        }
        dirty = false;
        sinceRenew = CameraControlPacket.RENEW_INTERVAL;
        WFNetwork.sendToServer(new CameraControlPacket(feedId, yaw, pitch, zoom,
                (byte) mode.ordinal(), true));
    }
}
