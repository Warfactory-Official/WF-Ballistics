package com.wf.wfballistics.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches {@code LivingEntity.lastHurt}, the other half of vanilla's invulnerability window. */
@Mixin(LivingEntity.class)
public interface AccessorLivingEntity {

    @Accessor("lastHurt")
    float wfLastHurt();

    @Accessor("lastHurt")
    void wfSetLastHurt(float lastHurt);
}
