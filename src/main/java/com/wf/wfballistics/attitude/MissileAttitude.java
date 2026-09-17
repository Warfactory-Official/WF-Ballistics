package com.wf.wfballistics.attitude;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** How a flying model orients itself to its heading: its "attitude". */
public interface MissileAttitude {

    /**
     * @param heading the (normalized) direction of travel in world space. Implementations must not modify
     *      it: callers pass their own live heading vector rather than a defensive copy
     * @return the target orientation mapping model space (nose = +Y) to world space
     */
    Quaternionf orientation(Vector3f heading);

    /** The same rotation written into {@code dest} rather than a fresh quaternion. */
    default Quaternionf orientation(Vector3f heading, Quaternionf dest) {
        return dest.set(orientation(heading));
    }
}
