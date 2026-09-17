package com.wf.wfballistics.drone.cam;

/**
 * The optics and the datalink, as numbers.
 *
 * @param fovDeg vertical field of view at zoom 1. 70 is roughly a GoPro; narrower reads as a spotting
 *      scope and is what {@link #maxZoom} buys
 * @param maxZoom how far the optic can stop down. Zoom is applied to the field of view, so a 4x zoom is
 *      a 4x narrower FOV and shows the same pixel count over a quarter the arc
 * @param pitchMin gimbal limits in degrees, in the convention the value is finally spent in: this is
 *      handed to {@code Camera.setRotation} unchanged, so <b>positive is looking down</b>.
 *      Every head here reaches +90, because straight down at the ground is most of what a
 *      camera on an aircraft is for; what varies is how far <em>up</em> it can look. These
 *      two were the other way round for a while: the sign was read backwards, so a survey
 *      drone could stare at the sky and only tilt 45 degrees toward the thing it was sent
 *      to photograph
 * @param yawRange degrees either side of the mount's rest heading the gimbal can turn. 180 means no
 *      limit, and is what everything airborne uses. A camera bolted to a wall cannot see
 *      through it, so a fixed installation gets an arc instead, and the arc is the point:
 *      it is what makes where you mount a camera a decision rather than a formality
 * @param slewRate degrees per tick the gimbal can move. The single most important number for feel: a
 *      gimbal that snaps is a free look, one that slews is a camera you have to lead with
 * @param downlinkRange blocks at which the picture is gone. Link quality falls off across this and drives the
 *      shader artifacts directly, so range is visible before it is fatal. A kilometre and up:
 *      the datalink is deliberately not the binding constraint on how far a drone can be
 *      flown. What binds first, and on the client rather than the server, is whether the
 *      observer's machine holds terrain out there at all; see
 *      {@code CameraFeedCache#hasTerrain}
 * @param modeMask bit per {@link CameraMode} ordinal. A head that is not fitted cannot be selected, and
 *      the server is the one that knows
 * @param feDraw idle draw of the camera itself, before {@link CameraMode#drawMultiplier()}. Only ever
 *      billed to a source that has somewhere to bill it to; a mains-fed camera ignores it
 */
public record CameraSpec(float fovDeg, float maxZoom, float pitchMin, float pitchMax, float yawRange,
                         float slewRate, double downlinkRange, int modeMask, int feDraw) {

    /** Everything airborne. A drone can rotate, so limiting its gimbal in yaw would limit nothing. */
    public static final float FREE_YAW = 180.0f;

    /** Every drone carries this much: a fixed wide-angle optical head on a two-axis gimbal. */
    public static final CameraSpec STANDARD = new CameraSpec(
            70.0f, 4.0f, -30.0f, 90.0f, FREE_YAW, 5.0f, 1000.0, mask(CameraMode.OPTICAL), 2);

    /** The fitted-out head. */
    public static final CameraSpec RECON = new CameraSpec(
            60.0f, 8.0f, -45.0f, 90.0f, FREE_YAW, 9.0f, 1600.0,
            mask(CameraMode.OPTICAL, CameraMode.THERMAL, CameraMode.LOWLIGHT), 6);

    /** A camera on a wall, and a deliberately different thing from a drone's. */
    public static final CameraSpec SECURITY = new CameraSpec(
            75.0f, 6.0f, -45.0f, 90.0f, 110.0f, 12.0f, 2000.0,
            mask(CameraMode.OPTICAL, CameraMode.LOWLIGHT), 0);

    private static int mask(CameraMode... modes) {
        int out = 0;
        for (CameraMode mode : modes) {
            out |= 1 << mode.ordinal();
        }
        return out;
    }

    public boolean fitted(CameraMode mode) {
        return (this.modeMask & (1 << mode.ordinal())) != 0;
    }

    /** @return true if this gimbal can turn all the way round, and yaw needs no clamping at all. */
    public boolean freeYaw() {
        return this.yawRange >= FREE_YAW;
    }

    /**
     * @return the next fitted mode after {@code from}, wrapping. Returns {@code from} if it is the only one,
     *      so cycling on a bare optical head is a no-op rather than a mode the drone does not have.
     */
    public CameraMode cycle(CameraMode from) {
        for (int i = 1; i <= CameraMode.VALUES.length; i++) {
            CameraMode candidate = CameraMode.byOrdinal(from.ordinal() + i);
            if (fitted(candidate)) {
                return candidate;
            }
        }
        return from;
    }

    /** Link quality at a distance, as the fraction of the picture that survives. */
    public float linkAt(double distance) {
        if (distance <= 0.0) {
            return 1.0f;
        }
        double f = distance / this.downlinkRange;
        if (f >= 1.0) {
            return 0.0f;
        }
        return (float) (1.0 - f * f);
    }

    /**
     * @return vertical field of view at this zoom, in degrees.
     */
    public float fovAt(float zoom) {
        return this.fovDeg / Math.max(1.0f, Math.min(this.maxZoom, zoom));
    }

    public int drawFor(CameraMode mode) {
        return Math.max(1, Math.round(this.feDraw * mode.drawMultiplier()));
    }
}
