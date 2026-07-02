package com.wf.wfballistics.entity;

import com.wf.wfballistics.util.OBB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Marks an entity that carries one or more oriented bounding boxes ({@link OBB}) used for precise,
 * rotation-aware hit detection in place of the coarse vanilla AABB.
 *
 */
public interface OBBEntity {

    List<OBB> getOBBs();

    /**
     * @return true when the entity has no OBBs and should fall back to vanilla AABB behavior.
     */
    default boolean enableAABB() {
        return this.getOBBs().isEmpty();
    }

    /**
     * @return true if any of this entity's OBBs (offset by {@code vec3}) overlaps the block at {@code pos}.
     */
    default boolean isInObb(BlockPos pos, Vec3 vec3) {
        List<OBB> obbList = this.getOBBs();
        AABB aabb1 = new AABB(pos).inflate(0.3, 0.6, 0.3);
        for (OBB obb : obbList) {
            OBB moved = obb.move(vec3);
            if (OBB.isColliding(moved, aabb1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if any of this entity's OBBs (offset by {@code vec3}) overlaps the given entity,
     *         using OBB-vs-OBB when the other entity is also an {@link OBBEntity}.
     */
    default boolean isInObb(Entity entity, Vec3 vec3) {
        List<OBB> obbList = this.getOBBs();
        for (OBB obb : obbList) {
            OBB moved = obb.move(vec3);
            if (entity instanceof OBBEntity other && !other.enableAABB()) {
                for (OBB obb2 : other.getOBBs()) {
                    if (OBB.isColliding(moved, obb2)) {
                        return true;
                    }
                }
            } else {
                if (OBB.isColliding(moved, entity.getBoundingBox())) {
                    return true;
                }
            }
        }
        return false;
    }
}
