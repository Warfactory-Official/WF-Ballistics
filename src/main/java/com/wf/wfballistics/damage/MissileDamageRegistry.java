package com.wf.wfballistics.damage;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of named {@link MissileDamageResponse}s, keyed by {@link ResourceLocation}. Mirrors
 * {@link com.wf.wfballistics.warhead.WarheadRegistry}: a missile stores the id (so the choice round-trips through
 * NBT) and resolves the lambda here at load / hit time. Addons register their own during mod construction with
 * {@link #register}; the built-ins below are always available.
 */
public final class MissileDamageRegistry {

    /** Pass-through: the missile takes damage exactly as dealt (vanilla behaviour, the default). */
    public static final MissileDamageResponse STANDARD = (missile, source, amount) -> amount;

    /** Immune to everything except explosion damage — the canonical "hardened, blast-only" airframe. */
    public static final MissileDamageResponse EXPLOSION_ONLY = (missile, source, amount) ->
            source.is(DamageTypeTags.IS_EXPLOSION) ? amount : 0.0f;

    /** Takes no damage from any source in flight (still detonates on impact / when it reaches its target). */
    public static final MissileDamageResponse INVULNERABLE = (missile, source, amount) -> 0.0f;

    private static final ResourceLocation DEFAULT_ID = rl("standard");
    private static final Map<ResourceLocation, MissileDamageResponse> RESPONSES = new LinkedHashMap<>();

    static {
        register(DEFAULT_ID, STANDARD);
        register(rl("explosion_only"), EXPLOSION_ONLY);
        register(rl("invulnerable"), INVULNERABLE);
    }

    private MissileDamageRegistry() {
    }

    public static ResourceLocation rl(String path) {
        return new ResourceLocation(WFBallistics.MODID, path);
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT_ID;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null ? parsed : DEFAULT_ID;
    }

    public static void register(ResourceLocation id, MissileDamageResponse response) {
        RESPONSES.put(id, response);
    }

    public static MissileDamageResponse get(ResourceLocation id) {
        return RESPONSES.getOrDefault(id, STANDARD);
    }

    public static boolean exists(ResourceLocation id) {
        return RESPONSES.containsKey(id);
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(RESPONSES.keySet());
    }

    public static ResourceLocation defaultId() {
        return DEFAULT_ID;
    }
}
