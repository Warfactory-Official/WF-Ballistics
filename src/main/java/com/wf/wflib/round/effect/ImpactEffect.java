package com.wf.wflib.round.effect;

import com.wf.wflib.api.Threat;
import net.minecraft.core.RegistryAccess;

/** What a round does on its trigger, beyond its own damage and warhead. Server thread. */
@FunctionalInterface
public interface ImpactEffect {

    void apply(ImpactContext ctx);

    /** {@link ImpactTrigger#HIT} only, before the strike event: the threat the target resolves. */
    default Threat threat(ImpactContext ctx, Threat threat) {
        return threat;
    }

    /** Server start, per bound preset: missing registry/warhead refs => {@link IllegalStateException}. */
    default void check(RegistryAccess registries) {
    }
}
