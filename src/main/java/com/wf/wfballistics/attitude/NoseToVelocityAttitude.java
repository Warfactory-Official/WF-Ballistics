package com.wf.wfballistics.attitude;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rocket/missile attitude: the nose (model {@code +Y}) points straight along the heading, via the minimal rotation
 * from {@code +Y} to the velocity.
 */
public final class NoseToVelocityAttitude implements MissileAttitude {

    public static final NoseToVelocityAttitude INSTANCE = new NoseToVelocityAttitude();

    private NoseToVelocityAttitude() {
    }

    @Override
    public Quaternionf orientation(Vector3f heading) {
        return orientation(heading, new Quaternionf());
    }

    @Override
    public Quaternionf orientation(Vector3f heading, Quaternionf dest) {
        // Component form: the vector overload would need a fresh +Y to hand it.
        return dest.rotationTo(0.0f, 1.0f, 0.0f, heading.x, heading.y, heading.z);
    }
}
