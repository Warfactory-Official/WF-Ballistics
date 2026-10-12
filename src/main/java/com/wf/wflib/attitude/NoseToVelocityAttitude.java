package com.wf.wflib.attitude;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Rocket/missile attitude: nose (model {@code +Y}) points along heading, minimal rotation from {@code +Y}. */
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
        // Component form avoids allocating a fresh +Y vector for the vector overload.
        return dest.rotationTo(0.0f, 1.0f, 0.0f, heading.x, heading.y, heading.z);
    }
}
