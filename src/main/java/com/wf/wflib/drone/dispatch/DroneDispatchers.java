package com.wf.wflib.drone.dispatch;

import com.wf.wflib.WFLib;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.telemetry.TelemetryLink;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loaded {@link DroneDispatcher}s per level. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class DroneDispatchers {

    private static final Map<Level, Set<DroneDispatcher>> LOADED = new ConcurrentHashMap<>();

    private DroneDispatchers() {
    }

    public static void add(ServerLevel level, DroneDispatcher dispatcher) {
        LOADED.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet()).add(dispatcher);
    }

    public static void remove(Level level, DroneDispatcher dispatcher) {
        Set<DroneDispatcher> set = LOADED.get(level);
        if (set != null) {
            set.remove(dispatcher);
        }
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        LOADED.remove((Level) event.getLevel());
    }

    /** Nearest in the player's level that {@link #permits} them; null = none. */
    @Nullable
    public static DroneDispatcher nearest(ServerPlayer player) {
        Set<DroneDispatcher> set = LOADED.get(player.serverLevel());
        if (set == null) {
            return null;
        }
        UUID side = ReconOwners.owningEntity(player);
        DroneDispatcher best = null;
        double bestSq = Double.MAX_VALUE;
        for (DroneDispatcher d : set) {
            double sq = d.dispatchPos().distToCenterSqr(player.position());
            if (sq < bestSq && permits(side, d)) {
                best = d;
                bestSq = sq;
            }
        }
        return best;
    }

    /** Unclaimed, same side or WarForge allies. */
    public static boolean permits(@Nullable UUID playerSide, DroneDispatcher dispatcher) {
        UUID own = dispatcher.side();
        return own == null || own.equals(playerSide) || TelemetryLink.allied(playerSide, own);
    }
}
