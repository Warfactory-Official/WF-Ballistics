package com.wf.wflib.entity;

import com.wf.wflib.WFLib;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OBBEntityTracker {

    private static final Map<Level, Set<Entity>> BY_LEVEL = new ConcurrentHashMap<>();

    private OBBEntityTracker() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof OBBEntity) {
            BY_LEVEL.computeIfAbsent(event.getLevel(), k -> ConcurrentHashMap.newKeySet()).add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof OBBEntity) {
            Set<Entity> set = BY_LEVEL.get(event.getLevel());
            if (set != null) {
                set.remove(event.getEntity());
                if (set.isEmpty()) {
                    BY_LEVEL.remove(event.getLevel());
                }
            }
        }
    }

    public static boolean hasAny(Level level) {
        Set<Entity> set = BY_LEVEL.get(level);
        return set != null && !set.isEmpty();
    }

    public static Set<Entity> get(Level level) {
        Set<Entity> set = BY_LEVEL.get(level);
        return set != null ? set : Set.of();
    }
}
