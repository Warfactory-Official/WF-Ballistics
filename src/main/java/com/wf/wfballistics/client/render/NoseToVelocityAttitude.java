package com.wf.wfballistics.client.render;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rocket/missile attitude: the nose (model {@code +Y}) points straight along the heading, via the minimal
 * rotation from {@code +Y} to the velocity. Roll is unconstrained (the airframe is axially symmetric, so it
 * doesn't matter) — banking is layered on separately by the visual. This is the default attitude.
 */
public final class NoseToVelocityAttitude implements MissileAttitude {

    public static final NoseToVelocityAttitude INSTANCE = new NoseToVelocityAttitude();

    private NoseToVelocityAttitude() {
    }

    @Override
    public Quaternionf orientation(Vector3f heading) {
        return new Quaternionf().rotationTo(new Vector3f(0.0f, 1.0f, 0.0f), heading);
    }
}
