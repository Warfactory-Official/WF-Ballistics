package com.wf.wfballistics.attitude;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Multirotor attitude: the airframe stays level and only yaws to face where it is going. A quadcopter
 * translates by tilting a few degrees, not by pointing its nose down a climb like a missile does, so the
 * vertical component of the heading is dropped entirely here and the cosmetic tilt is layered on by the
 * visual.
 *
 * <p>Model convention for copters (differs from the missile nose = {@code +Y} convention): up is
 * {@code +Y} and the nose is {@code +Z}.
 */
public final class CopterAttitude implements MissileAttitude {

    public static final CopterAttitude INSTANCE = new CopterAttitude();

    private CopterAttitude() {
    }

    @Override
    public Quaternionf orientation(Vector3f heading) {
        return orientation(heading, new Quaternionf());
    }

    /**
     * Holding the airframe level and yawing it to face the heading <em>is</em> a rotation about {@code +Y},
     * so that is what this builds. It used to construct the basis {@code (up x forward, up, forward)} and
     * convert the matrix to a quaternion: the same rotation, but four vectors and a matrix every frame for
     * every copter on screen.
     *
     * <p>The half-angle is taken straight from the normalised heading rather than by recovering the yaw with
     * {@code atan2} and taking its sine and cosine again. Both are allocation-free; going back through the
     * angle is about a thousand times less accurate, because the exact {@code (sin, cos)} the caller already
     * had gets thrown away and reconstructed in single precision.
     */
    @Override
    public Quaternionf orientation(Vector3f heading, Quaternionf dest) {
        float lenSq = heading.x * heading.x + heading.z * heading.z;
        if (lenSq < 1.0e-8f) {
            // No horizontal heading to face: level and unturned, matching the old basis fallback of +Z.
            return dest.identity();
        }
        float inv = (float) (1.0 / Math.sqrt(lenSq));
        float sin = heading.x * inv;
        float cos = heading.z * inv;
        // q = (0, sin(yaw/2), 0, cos(yaw/2)). Whichever of the two half-angle identities has the larger
        // denominator is used, so neither ever divides by something near zero: cos(yaw/2) >= sqrt(1/2) while
        // the heading points forward, and |sin(yaw/2)| >= sqrt(1/2) once it points backward.
        float y;
        float w;
        if (cos >= 0.0f) {
            w = (float) Math.sqrt(0.5 * (1.0 + cos));
            y = sin / (2.0f * w);
        } else {
            y = (float) Math.sqrt(0.5 * (1.0 - cos));
            if (sin < 0.0f) {
                y = -y;
            }
            w = sin / (2.0f * y);
        }
        return dest.set(0.0f, y, 0.0f, w);
    }
}
