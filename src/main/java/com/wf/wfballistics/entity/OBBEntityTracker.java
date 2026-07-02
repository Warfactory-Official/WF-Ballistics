package com.wf.wfballistics.entity;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Mod.EventBusSubscriber(modid = WFBallistics.MODID)
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
