package com.wf.wflib.recon.grid;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Where the hubs are, so a probe can work out which network it belongs to without scanning for one. */
public final class HubIndex {

    /** Ticks a hub may go without refreshing before it is forgotten. */
    private static final int HUB_TIMEOUT = 60;

    private static final Map<ResourceKey<Level>, Map<Long, Entry>> BY_LEVEL = new HashMap<>();

    private HubIndex() {
    }

    /**
     * Announce a hub. Called every tick from the hub itself, in the same idiom as sensor and node registration.
     */
    public static void register(ServerLevel level, BlockPos pos, long netId) {
        WorldThread.assertOn("recon hub index");
        BY_LEVEL.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .put(pos.asLong(), new Entry(pos.immutable(), netId, level.getGameTime()));
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        Map<Long, Entry> hubs = BY_LEVEL.get(level.dimension());
        if (hubs != null) {
            hubs.remove(pos.asLong());
        }
    }

    /**
     * @param within blocks to search. A caller should pass what it could plausibly reach through a full chain
     *      of relays, not its own single-hop range: the whole point of a chain is to belong to a hub
     *      you cannot see.
     * @return the net of the nearest hub in range, or {@code fallback} if there is none.
     */
    public static long netFor(ServerLevel level, BlockPos pos, double within, long fallback) {
        Map<Long, Entry> hubs = BY_LEVEL.get(level.dimension());
        if (hubs == null || hubs.isEmpty()) {
            return fallback;
        }
        long now = level.getGameTime();
        long best = fallback;
        double bestSq = within * within;
        for (Iterator<Map.Entry<Long, Entry>> it = hubs.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = it.next().getValue();
            if (now - entry.stamp > HUB_TIMEOUT) {
                it.remove();
                continue;
            }
            double distSq = entry.pos.distSqr(pos);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = entry.netId;
            }
        }
        return best;
    }

    /**
     * @return every hub currently announced in this dimension, for diagnostics.
     */
    public static List<BlockPos> hubs(ServerLevel level) {
        Map<Long, Entry> hubs = BY_LEVEL.get(level.dimension());
        if (hubs == null) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>(hubs.size());
        for (Entry entry : hubs.values()) {
            out.add(entry.pos);
        }
        return out;
    }

    public static void shutdown() {
        BY_LEVEL.clear();
    }

    private record Entry(BlockPos pos, long netId, long stamp) {
    }
}
