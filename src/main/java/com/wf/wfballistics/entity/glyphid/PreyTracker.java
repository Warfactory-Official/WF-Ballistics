package com.wf.wfballistics.entity.glyphid;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A per-level index of everything that is not a monster, which is everything a glyphid might want to bite. */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PreyTracker {

    /**
     * Above this many candidates the level-wide scan stops being cheaper and the caller goes back to the box query.
     */
    public static final int SCAN_LIMIT = 512;

    private static final Map<Level, Set<LivingEntity>> BY_LEVEL = new ConcurrentHashMap<>();

    private PreyTracker() {
    }

    public static Set<LivingEntity> prey(Level level) {
        return BY_LEVEL.getOrDefault(level, Set.of());
    }

    /**
     * @return true when scanning the index is the cheaper way to answer "what is near me".
     */
    public static boolean worthScanning(Level level) {
        return prey(level).size() <= SCAN_LIMIT;
    }

    private static boolean indexed(Object entity) {
        return entity instanceof LivingEntity && !(entity instanceof Monster);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (indexed(event.getEntity()) && !event.getLevel().isClientSide) {
            BY_LEVEL.computeIfAbsent(event.getLevel(), k -> ConcurrentHashMap.newKeySet())
                    .add((LivingEntity) event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!indexed(event.getEntity()) || event.getLevel().isClientSide) {
            return;
        }
        Set<LivingEntity> set = BY_LEVEL.get(event.getLevel());
        if (set != null) {
            set.remove(event.getEntity());
            if (set.isEmpty()) {
                BY_LEVEL.remove(event.getLevel());
            }
        }
    }
}
