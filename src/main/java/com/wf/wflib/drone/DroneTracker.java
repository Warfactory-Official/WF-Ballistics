package com.wf.wflib.drone;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.ai.DroneAiScheduler;
import com.wf.wflib.drone.ai.DroneCarrier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-level live drone bodies, so the scheduler doesn't sweep every entity each tick. {@link DroneEntity} joins via
 * entity events; other bodies {@link #track} themselves.
 */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DroneTracker {

    private static final Map<Level, Set<DroneEntity>> BY_LEVEL = new ConcurrentHashMap<>();
    private static final Map<Level, Map<UUID, ControllableDrone>> BODIES = new ConcurrentHashMap<>();

    static {
        DroneAiScheduler.addSource(DroneTracker::collect);
    }

    private DroneTracker() {
    }

    /** Touching this class runs its static initialiser, which registers the carrier source. */
    public static void bootstrap() {
    }

    /** wflib's own bodies only. */
    public static Set<DroneEntity> drones(Level level) {
        return BY_LEVEL.getOrDefault(level, Set.of());
    }

    /** Every body the autonomy may fly, any mod. */
    public static Collection<ControllableDrone> bodies(Level level) {
        Map<UUID, ControllableDrone> map = BODIES.get(level);
        return map == null ? List.of() : map.values();
    }

    @Nullable
    public static ControllableDrone byId(Level level, UUID id) {
        Map<UUID, ControllableDrone> map = BODIES.get(level);
        return map == null ? null : map.get(id);
    }

    /** Server side, on level join. */
    public static void track(ControllableDrone body) {
        Entity e = body.asEntity();
        BODIES.computeIfAbsent(e.level(), k -> new ConcurrentHashMap<>()).put(e.getUUID(), body);
    }

    /** Server side, on level leave. */
    public static void untrack(ControllableDrone body) {
        Entity e = body.asEntity();
        DroneRecall.leaving(e);
        Map<UUID, ControllableDrone> map = BODIES.get(e.level());
        if (map != null) {
            map.remove(e.getUUID(), body);
            if (map.isEmpty()) {
                BODIES.remove(e.level());
            }
        }
    }

    private static void collect(ServerLevel level, List<DroneCarrier> out) {
        out.addAll(bodies(level));
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof DroneEntity drone && !drone.level().isClientSide) {
            BY_LEVEL.computeIfAbsent(drone.level(), k -> ConcurrentHashMap.newKeySet()).add(drone);
            track(drone);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof DroneEntity drone) || event.getLevel().isClientSide) {
            return;
        }
        untrack(drone);
        Set<DroneEntity> set = BY_LEVEL.get(event.getLevel());
        if (set != null) {
            set.remove(drone);
            if (set.isEmpty()) {
                BY_LEVEL.remove(event.getLevel());
            }
        }
    }
}
