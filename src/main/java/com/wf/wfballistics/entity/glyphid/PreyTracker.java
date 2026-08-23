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

/**
 * A per-level index of everything that is not a monster, which is everything a glyphid might want to bite.
 *
 * <p>Exists because the swarm was searching for its dinner among itself. {@link EntityGlyphid#nearestPrey}
 * asked the level for every {@link LivingEntity} within twenty-four blocks and then threw the glyphids away
 * in Java — so in a swarm of two thousand bodies packed a block apart, every bug fetched and rejected its
 * neighbours, every tick. Measured with JFR: 0.72 ms/tick inside {@code AABB.intersects} under
 * {@code EntitySection.getEntities}, 0.32 ms inside {@code isPrey} and 0.31 ms in the {@code getHealth}
 * beneath it — 3.9% of the swarm's entire tick spent establishing that a glyphid is not food.
 *
 * <p>The membership rule is the <em>static</em> half of {@link EntityGlyphid#isPrey}: a thing's class does
 * not change, so class tests belong in the index and the liveness tests do not. {@code EntityGlyphid}
 * extends {@code Monster}, so excluding monsters excludes the swarm.
 *
 * <p>Mirrors {@link GlyphidTracker}, which is the same idea pointed the other way.
 */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PreyTracker {

    /**
     * Above this many candidates the level-wide scan stops being the cheaper question to ask, and the
     * caller should go back to the box query. Both paths return the same answer, so this is a performance
     * fallback and never a behavioural one: a world with more loaded animals than the swarm has bugs is
     * one where vanilla's spatial index is the better index.
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
