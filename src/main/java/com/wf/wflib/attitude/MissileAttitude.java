package com.wf.wflib.attitude;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Orientation toward heading for any flying model (missile, drone, copter), not just missiles. */
public interface MissileAttitude {

    /**
     * @param heading normalized direction of travel, world space. MUST NOT be modified: callers pass
     *                their own live vector, never a defensive copy
     * @return target orientation, model space (nose {@code = +Y}) to world space
     */
    Quaternionf orientation(Vector3f heading);

    default Quaternionf orientation(Vector3f heading, Quaternionf dest) {
        return dest.set(orientation(heading));
    }
}
