package com.wf.wflib.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * An entity ridden from afar: an operator is its passenger while their body stays at an anchor.
 * Server: {@code com.wf.wflib.stream.DetachedBodyStream} moves the operator's view and terrain onto it.
 * The host positions an anchored rider at the anchor and syncs anchors to clients.
 */
public interface DetachedBodyHost {

    boolean isDetachedBodyActive();

    @Nullable
    Vec3 getDetachedBodyAnchor(Entity operator);

    void setDetachedBodyAnchor(Entity operator, @Nullable Vec3 anchor);

    void clearDetachedBodyAnchors();

    Collection<Entity> getDetachedOperators();
}
