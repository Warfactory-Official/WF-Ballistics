package com.wf.wfballistics;

import com.wf.wfballistics.util.ObjBounds;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the missile models a {@link MissileEntity} can render with, keyed by a short stable id
 * (persisted/synced on the entity so the model is chosen at runtime, HBM-style, rather than compiled in).
 *
 * <p>Server-safe: it only holds {@link ResourceLocation}s and reads model geometry off the jar via
 * {@link ObjBounds}, so the flight code can look up a model's length without touching client render classes.
 * The client separately bakes a {@code PartialModel} for each of these ids (see {@code ModModels}).
 */
public final class MissileModels {

    /** Id used when a requested one is unknown or unset. */
    public static final String DEFAULT = "v2";

    private static final Map<String, ResourceLocation> BY_ID = new LinkedHashMap<>();
    private static final Map<String, Double> LENGTHS = new ConcurrentHashMap<>();

    static {
        reg("abm", "missile_abm");
        reg("atlas", "missile_atlas");
        reg("booster", "missilebooster");
        reg("carrier", "missilecarrier");
        reg("huge", "missile_huge");
        reg("micro", "missile_micro");
        reg("neon", "missileneon");
        reg("shuttle", "missile_shuttle");
        reg("stealth", "missile_stealth");
        reg("strong", "missile_strong");
        reg("taint", "missiletaint");
        reg("thermo", "missilethermo");
        reg("v2", "missile_v2");
    }

    private MissileModels() { }

    private static void reg(String id, String modelName) {
        BY_ID.put(id, new ResourceLocation(WFBallistics.MODID, "entity/missiles/" + modelName));
    }

    /** @return true if the id maps to a known model. */
    public static boolean exists(String id) {
        return BY_ID.containsKey(id);
    }

    /** @return the model json location for the id, falling back to {@link #DEFAULT}. */
    public static ResourceLocation model(String id) {
        return BY_ID.getOrDefault(id, BY_ID.get(DEFAULT));
    }

    /** @return all registered ids, in registration order. */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }

    /**
     * @return the longest axis of the model's mesh, in model units (cached). Reads the model json + obj
     *         off the jar, so it works server-side. Falls back to 1.0 if the assets can't be read.
     */
    public static double length(String id) {
        return LENGTHS.computeIfAbsent(id, i -> {
            try {
                double len = ObjBounds.longestAxisFromModel(model(i));
                return len > 1.0E-3 ? len : 1.0;
            } catch (Throwable t) {
                return 1.0;
            }
        });
    }
}
