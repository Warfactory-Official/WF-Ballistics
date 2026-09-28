package com.wf.wflib.client.cam;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/** A camera that can be put anywhere. */
public final class FeedCamera extends Camera {

    /**
     * Point this camera at the world from an arbitrary place.
     *
     * @param anchor the entity the camera belongs to: the drone when the client is tracking it. Never null;
     *      vanilla dereferences it during setup and during fog and fluid checks
     */
    public void place(BlockGetter level, Entity anchor, Vec3 pos, float yaw, float pitch, float roll,
                      float partialTick) {
        this.setup(level, anchor, false, false, partialTick);
        this.setPosition(pos);
        this.setRotation(yaw, pitch, roll);
    }
}
