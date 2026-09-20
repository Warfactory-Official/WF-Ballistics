package com.wf.wflib.attitude;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Multirotor attitude: the airframe stays level and only yaws to face where it is going. */
public final class CopterAttitude implements MissileAttitude {

    public static final CopterAttitude INSTANCE = new CopterAttitude();

    private CopterAttitude() {
    }

    @Override
    public Quaternionf orientation(Vector3f heading) {
        return orientation(heading, new Quaternionf());
    }

    /**
     * Holding the airframe level and yawing it to face the heading <em>is</em> a rotation about {@code +Y}, so that
     * is what this builds.
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
