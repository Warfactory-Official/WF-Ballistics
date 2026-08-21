package com.wf.wfballistics.drone;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.drone.ai.DroneAiScheduler;
import com.wf.wfballistics.drone.ai.DroneCarrier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps an O(1) per-level set of live drones so the scheduler doesn't have to sweep every entity in the
 * world each tick. Maintained from join/leave events, mirroring {@code SwarmManager} and
 * {@code OBBEntityTracker}.
 *
 * <p>Also registers itself as the scheduler's source of in-world drones; the off-world sim adds a second
 * source of its own.
 */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DroneTracker {

    private static final Map<Level, Set<DroneEntity>> BY_LEVEL = new ConcurrentHashMap<>();

    static {
        DroneAiScheduler.addSource(DroneTracker::collect);
    }

    private DroneTracker() {
    }

    /**
     * Touching this class runs its static initialiser, which registers the carrier source. Called from mod
     * setup so the scheduler knows about in-world drones before the first tick.
     */
    public static void bootstrap() {
    }

    public static Set<DroneEntity> drones(Level level) {
        return BY_LEVEL.getOrDefault(level, Set.of());
    }

    private static void collect(ServerLevel level, List<DroneCarrier> out) {
        out.addAll(drones(level));
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof DroneEntity drone && !drone.level().isClientSide) {
            BY_LEVEL.computeIfAbsent(drone.level(), k -> ConcurrentHashMap.newKeySet()).add(drone);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof DroneEntity drone) || event.getLevel().isClientSide) {
            return;
        }
        Set<DroneEntity> set = BY_LEVEL.get(event.getLevel());
        if (set != null) {
            set.remove(drone);
            if (set.isEmpty()) {
                BY_LEVEL.remove(event.getLevel());
            }
        }
    }
}
