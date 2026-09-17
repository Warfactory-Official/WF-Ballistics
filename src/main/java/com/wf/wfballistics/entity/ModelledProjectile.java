package com.wf.wfballistics.entity;

import net.minecraft.resources.ResourceLocation;

/** A projectile that carries the id of the model it is drawn with, chosen at runtime rather than compiled in. */
public interface ModelledProjectile {

    ResourceLocation getModelId();
}
