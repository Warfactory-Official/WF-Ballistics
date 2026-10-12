package com.wf.wflib.attitude;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Winged-drone attitude: nose (model {@code +Y}) follows heading (pitches to climb/dive), airframe
 * held upright via dorsal-side alignment to world-up (wings level, belly down) instead of a missile's
 * free roll.
 */
public final class LevelDroneAttitude implements MissileAttitude {

    public static final LevelDroneAttitude INSTANCE = new LevelDroneAttitude();

    private static final Vector3f MODEL_UP = new Vector3f(0.0f, 0.0f, -1.0f);
    private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    private LevelDroneAttitude() {
    }

    @Override
    public Quaternionf orientation(Vector3f heading) {
        Vector3f forward = new Vector3f(heading);
        float align = WORLD_UP.dot(forward);
        Vector3f up;
        if (Math.abs(align) > 0.999f) {
            up = new Vector3f(0.0f, 0.0f, 1.0f).sub(new Vector3f(forward).mul(forward.z));
            if (up.lengthSquared() < 1.0e-6f) {
                up.set(1.0f, 0.0f, 0.0f);
            }
            up.normalize();
        } else {
            up = new Vector3f(WORLD_UP).sub(new Vector3f(forward).mul(align)).normalize();
        }

        Vector3f colY = new Vector3f(forward);
        Vector3f colZ = new Vector3f(up).negate();          // model +Z -> -up (=> -Z -> up)
        Vector3f colX = new Vector3f(colY).cross(colZ);      // colY x colZ = -(forward x up)
        Matrix3f basis = new Matrix3f(colX, colY, colZ);
        return new Quaternionf().setFromNormalized(basis);
    }
}
