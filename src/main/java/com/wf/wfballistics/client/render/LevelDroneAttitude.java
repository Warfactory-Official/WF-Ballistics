package com.wf.wfballistics.client.render;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Winged-drone attitude: the nose (model {@code +Y}) follows the heading (so the drone pitches to climb/dive),
 * but the airframe is held upright — its dorsal (top) side is kept pointing at world-up, so the wings stay
 * level and the belly stays down instead of the free roll a missile takes. Banking into turns is layered on
 * top by the visual.
 *
 * <p>By the drone reorient convention (nose reoriented to {@code +Y}) the model's up/dorsal axis is
 * {@code -Z}. If a drone renders inverted, flip {@link #MODEL_UP}.
 */
public final class LevelDroneAttitude implements MissileAttitude {

    public static final LevelDroneAttitude INSTANCE = new LevelDroneAttitude();

    /**
     * The model's dorsal ("up") axis in local space, held toward world-up (see class doc).
     */
    private static final Vector3f MODEL_UP = new Vector3f(0.0f, 0.0f, -1.0f);
    private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    private LevelDroneAttitude() {
    }

    @Override
    public Quaternionf orientation(Vector3f heading) {
        Vector3f forward = new Vector3f(heading); // already normalized
        // World-up made perpendicular to the heading = the direction the model's dorsal should point.
        float align = WORLD_UP.dot(forward);
        Vector3f up;
        if (Math.abs(align) > 0.999f) {
            // Near-vertical (steep climb/dive): world-up gives no usable roll reference, so pick a stable
            // horizontal one. Roll is visually irrelevant while pointing straight up/down anyway.
            up = new Vector3f(0.0f, 0.0f, 1.0f).sub(new Vector3f(forward).mul(forward.z));
            if (up.lengthSquared() < 1.0e-6f) {
                up.set(1.0f, 0.0f, 0.0f);
            }
            up.normalize();
        } else {
            up = new Vector3f(WORLD_UP).sub(new Vector3f(forward).mul(align)).normalize();
        }

        // Build the rotation whose columns are the world images of the model's basis vectors, chosen so that
        // model +Y -> forward and model -Z -> up (a right-handed frame): colX = colY x colZ.
        Vector3f colY = new Vector3f(forward);
        Vector3f colZ = new Vector3f(up).negate();          // model +Z -> -up  (=> -Z -> up)
        Vector3f colX = new Vector3f(colY).cross(colZ);      // colY x colZ = -(forward x up)
        Matrix3f basis = new Matrix3f(colX, colY, colZ);
        return new Quaternionf().setFromNormalized(basis);
    }
}
