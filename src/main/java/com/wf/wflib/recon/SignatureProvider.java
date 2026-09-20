package com.wf.wflib.recon;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** How an entity's signature is worked out, for the entity source only. */
public interface SignatureProvider {

    /**
     * Higher runs first. Use this to put a specific provider ahead of a general one.
     */
    int priority();

    /**
     * @return this entity's signature, or null to mean "not mine, try the next".
     */
    @Nullable
    Signature of(Entity entity);
}
