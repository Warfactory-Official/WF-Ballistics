package com.wf.wflib.entity;

import com.wf.wflib.api.PreciseHitbox;
import com.wf.wflib.util.OBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

/** Entity whose hit/collision shape is its {@link OBB}s, not the vanilla AABB. */
public interface OBBEntity extends PreciseHitbox {

    List<OBB> getOBBs();

    default void updateOBBs() {
    }

    /** No OBBs => vanilla AABB behaviour. */
    default boolean enableAABB() {
        return this.getOBBs().isEmpty();
    }

    /** F3+B draws the boxes through WFLib's generic overlay; false when the entity draws its own debug. */
    default boolean drawsDebugOBBs() {
        return true;
    }

    /** Nearest box entry; boxes inflated by 2 x pick radius (fast crossers need slack). */
    @Nullable
    @Override
    default Vec3 clip(Vec3 from, Vec3 to) {
        float inflate = ((Entity) this).getPickRadius() * 2.0f;
        double best = Double.MAX_VALUE;
        for (OBB obb : getOBBs()) {
            OBB box = inflate > 0 ? obb.inflate(inflate) : obb;
            Vector3f[] axes = box.getAxes();
            double t = box.clipFraction(from.x, from.y, from.z, to.x, to.y, to.z, axes);
            if (t >= 0 && t < best) {
                best = t;
            }
        }
        return best == Double.MAX_VALUE ? null : from.add(to.subtract(from).scale(best));
    }
}
