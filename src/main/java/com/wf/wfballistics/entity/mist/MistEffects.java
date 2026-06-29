package com.wf.wfballistics.entity.mist;

import com.wf.wfballistics.fluid.WFFluids;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lookup that decides what a fluid does as mist. Two ways to register, mirroring the "registry or tags"
 * choice:
 * <ul>
 *   <li>{@link #register(Fluid, MistEffect)} — bind a specific fluid (used for the built-in gases).</li>
 *   <li>{@link #register(TagKey, MistEffect)} — bind a whole {@code #namespace:tag} of fluids, so packs and
 *       other mods can opt their fluids into a behaviour without touching code.</li>
 * </ul>
 *
 * <p>{@link #get} resolves exact-fluid bindings first, then falls back to tag bindings in registration order.
 * Call {@link #bootstrap()} once during common setup (it runs on both sides so the client can tint mist).
 */
public final class MistEffects {

    private static final Map<Fluid, MistEffect> BY_FLUID = new HashMap<>();
    private static final List<Map.Entry<TagKey<Fluid>, MistEffect>> BY_TAG = new ArrayList<>();
    private static boolean bootstrapped = false;

    private MistEffects() { }

    public static void register(Fluid fluid, MistEffect effect) {
        BY_FLUID.put(fluid, effect);
    }

    public static void register(TagKey<Fluid> tag, MistEffect effect) {
        BY_TAG.add(Map.entry(tag, effect));
    }

    /** @return the effect for this fluid, or {@code null} if the fluid is inert as mist */
    @Nullable
    public static MistEffect get(@Nullable Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) return null;
        MistEffect direct = BY_FLUID.get(fluid);
        if (direct != null) return direct;
        for (Map.Entry<TagKey<Fluid>, MistEffect> entry : BY_TAG) {
            if (fluid.defaultFluidState().is(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    /** Registers the built-in chemical agents. Idempotent. */
    public static void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        register(WFFluids.PHOSGENE.get(), new PhosgeneMistEffect());
        register(WFFluids.MUSTARD_GAS.get(), new MustardGasMistEffect());
    }
}
