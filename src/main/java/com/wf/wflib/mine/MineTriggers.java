package com.wf.wflib.mine;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of the {@link MineTrigger}s a mine can be fused with, keyed by a stable {@link ResourceLocation} exactly
 * as {@link com.wf.wflib.warhead.WarheadRegistry} keys warheads, so what a buried mine is watching for
 * survives save/load, which a lambda on its own cannot.
 */
public final class MineTriggers {

    /** Anything at all that comes close enough. */
    public static final MineTrigger ANY = MineTrigger.standard(MineTargets.ANY);
    /** Anything alive: the default, and what a mine is normally for. */
    public static final MineTrigger LIVING = MineTrigger.standard(MineTargets.LIVING);
    /** Players only, spectators excluded. */
    public static final MineTrigger PLAYERS = MineTrigger.standard(MineTargets.PLAYERS);
    /** Mobs only, so a minefield laid against a swarm ignores the people who laid it. */
    public static final MineTrigger MOBS = MineTrigger.standard(MineTargets.MOBS);
    /** The swarm specifically. */
    public static final MineTrigger GLYPHIDS = MineTrigger.standard(MineTargets.GLYPHIDS);
    /** Vehicles and anything bigger than a person: what an anti-armour mine is fused for. */
    public static final MineTrigger HEAVY = MineTrigger.standard(MineTargets.HEAVY);

    private static final ResourceLocation DEFAULT_ID = rl("living");
    private static final Map<ResourceLocation, MineTrigger> TRIGGERS = new LinkedHashMap<>();

    static {
        register(rl("any"), ANY);
        register(DEFAULT_ID, LIVING);
        register(rl("players"), PLAYERS);
        register(rl("mobs"), MOBS);
        register(rl("glyphids"), GLYPHIDS);
        register(rl("heavy"), HEAVY);
    }

    private MineTriggers() {
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT_ID;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null ? parsed : DEFAULT_ID;
    }

    public static void register(ResourceLocation id, MineTrigger trigger) {
        TRIGGERS.put(id, trigger);
    }

    public static MineTrigger get(ResourceLocation id) {
        return TRIGGERS.getOrDefault(id, LIVING);
    }

    public static boolean exists(ResourceLocation id) {
        return TRIGGERS.containsKey(id);
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(TRIGGERS.keySet());
    }

    public static ResourceLocation defaultId() {
        return DEFAULT_ID;
    }
}
