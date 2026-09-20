package com.wf.wflib.entity.glyphid;

import com.wf.wflib.WFLib;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An O(1) per-level set of live glyphids, maintained from join/leave events, mirroring {@code DroneTracker} and
 * {@code OBBEntityTracker}.
 */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GlyphidTracker {

    private static final Map<Level, Set<EntityGlyphid>> BY_LEVEL = new ConcurrentHashMap<>();

    private GlyphidTracker() {
    }

    public static Set<EntityGlyphid> glyphids(Level level) {
        return BY_LEVEL.getOrDefault(level, Set.of());
    }

    public static int count(Level level) {
        return glyphids(level).size();
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof EntityGlyphid glyphid && !event.getLevel().isClientSide) {
            BY_LEVEL.computeIfAbsent(event.getLevel(), k -> ConcurrentHashMap.newKeySet()).add(glyphid);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof EntityGlyphid glyphid) || event.getLevel().isClientSide) {
            return;
        }
        Set<EntityGlyphid> set = BY_LEVEL.get(event.getLevel());
        if (set != null) {
            set.remove(glyphid);
            if (set.isEmpty()) {
                BY_LEVEL.remove(event.getLevel());
            }
        }
    }
}
