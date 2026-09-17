package com.wf.wfballistics.industry;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Finds bases in the industry registry, off-thread and only when something changed. */
public final class IndustryClusters {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceKey<Level>, State> BY_LEVEL = new HashMap<>();

    private IndustryClusters() {
    }

    private static final class State {
        long dirtySinceTick = -1L;
        CompletableFuture<List<IndustryCluster>> inFlight;
        List<IndustryCluster> current = List.of();
        long computedAtTick = -1L;
    }

    private static State state(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), k -> new State());
    }

    /** Note that the registry changed. */
    public static void markDirty(ServerLevel level) {
        State state = state(level);
        if (state.dirtySinceTick < 0) {
            state.dirtySinceTick = level.getGameTime();
        }
    }

    /**
     * @return the most recent scan. Never null; empty until the first one lands.
     */
    public static List<IndustryCluster> clusters(ServerLevel level) {
        return state(level).current;
    }

    /**
     * @return the nearest base to a position, or null if none is known.
     */
    public static IndustryCluster nearest(ServerLevel level, double x, double z) {
        IndustryCluster best = null;
        double bestSq = Double.MAX_VALUE;
        for (IndustryCluster cluster : clusters(level)) {
            double distSq = cluster.distanceSqTo(x, z);
            if (distSq < bestSq) {
                bestSq = distSq;
                best = cluster;
            }
        }
        return best;
    }

    /**
     * @return the base with the most provocation, which is the one a colony would pick a fight with.
     */
    public static IndustryCluster loudest(ServerLevel level) {
        IndustryCluster best = null;
        for (IndustryCluster cluster : clusters(level)) {
            if (best == null || cluster.value() > best.value()) {
                best = cluster;
            }
        }
        return best;
    }

    /**
     * Adopt a finished scan and start one if the world has gone quiet. Called once per level tick.
     */
    public static void tick(ServerLevel level) {
        State state = state(level);

        if (state.inFlight != null && state.inFlight.isDone()) {
            List<IndustryCluster> result = state.inFlight.getNow(null);
            state.inFlight = null;
            if (result != null) {
                state.current = result;
                state.computedAtTick = level.getGameTime();
            }
        }

        if (state.dirtySinceTick < 0 || state.inFlight != null) {
            return;
        }
        if (level.getGameTime() - state.dirtySinceTick < IndustryConfig.recomputeDelayTicks()) {
            return;
        }
        state.dirtySinceTick = -1L;
        dispatch(level, state);
    }

    /**
     * Snapshot the cell index on the server thread, then cluster it on a worker.
     */
    private static void dispatch(ServerLevel level, State state) {
        IndustryRegistry registry = IndustryRegistry.get(level);
        Long2IntMap cells = registry.cells();
        if (cells.isEmpty()) {
            state.current = List.of();
            return;
        }

        long[] keys = new long[cells.size()];
        int[] values = new int[cells.size()];
        int i = 0;
        for (Long2IntMap.Entry entry : cells.long2IntEntrySet()) {
            keys[i] = entry.getLongKey();
            values[i] = entry.getIntValue();
            i++;
        }
        int cellChunks = registry.cellChunks();
        int gap = IndustryConfig.clusterGapCells();
        int minValue = IndustryConfig.clusterMinValue();

        state.inFlight = CompletableFuture
                .supplyAsync(() -> floodFill(keys, values, cellChunks, gap, minValue))
                .exceptionally(error -> {
                    LOGGER.error("[wfballistics] industry cluster scan failed", error);
                    return null;
                });
    }

    /** Connected components over occupied cells, joining any two within {@code gap} empty cells of each other. */
    static List<IndustryCluster> floodFill(long[] keys, int[] values, int cellChunks, int gap, int minValue) {
        Long2IntOpenHashMap valueByCell = new Long2IntOpenHashMap(keys.length);
        for (int i = 0; i < keys.length; i++) {
            valueByCell.put(keys[i], values[i]);
        }

        int span = cellChunks * 16;
        int reach = gap + 1;
        LongOpenHashSet visited = new LongOpenHashSet(keys.length);
        List<IndustryCluster> out = new ArrayList<>();

        for (long seed : keys) {
            if (!visited.add(seed)) {
                continue;
            }

            LongArrayList queue = new LongArrayList();
            LongArrayList members = new LongArrayList();
            queue.add(seed);

            while (!queue.isEmpty()) {
                long cell = queue.removeLong(queue.size() - 1);
                members.add(cell);

                int cx = IndustryRegistry.cellX(cell);
                int cz = IndustryRegistry.cellZ(cell);
                for (int dx = -reach; dx <= reach; dx++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        long neighbour = IndustryRegistry.packCell(cx + dx, cz + dz);
                        if (valueByCell.containsKey(neighbour) && visited.add(neighbour)) {
                            queue.add(neighbour);
                        }
                    }
                }
            }

            IndustryCluster cluster = summarise(members, valueByCell, span, minValue);
            if (cluster != null) {
                out.add(cluster);
            }
        }

        out.sort((a, b) -> Integer.compare(b.value(), a.value()));
        return out;
    }

    /**
     * @return the cluster, or null if it did not carry enough provocation to count as a base.
     */
    private static IndustryCluster summarise(LongArrayList members, Long2IntOpenHashMap valueByCell,
                                             int span, int minValue) {
        long total = 0;
        long weightedX = 0;
        long weightedZ = 0;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (long cell : members) {
            int value = valueByCell.get(cell);
            int cellMinX = IndustryRegistry.cellX(cell) * span;
            int cellMinZ = IndustryRegistry.cellZ(cell) * span;
            int centerX = cellMinX + span / 2;
            int centerZ = cellMinZ + span / 2;

            total += value;
            weightedX += (long) centerX * value;
            weightedZ += (long) centerZ * value;

            minX = Math.min(minX, cellMinX);
            minZ = Math.min(minZ, cellMinZ);
            maxX = Math.max(maxX, cellMinX + span - 1);
            maxZ = Math.max(maxZ, cellMinZ + span - 1);
        }

        if (total < minValue) {
            return null;
        }
        return new IndustryCluster((int) (weightedX / total), (int) (weightedZ / total),
                minX, minZ, maxX, maxZ, (int) total, members.size());
    }

    /**
     * @return ticks since the last completed scan, or -1 if none has run.
     */
    public static long ageTicks(ServerLevel level) {
        State state = state(level);
        return state.computedAtTick < 0 ? -1L : level.getGameTime() - state.computedAtTick;
    }

    public static boolean scanning(ServerLevel level) {
        return state(level).inFlight != null;
    }

    public static void clear() {
        BY_LEVEL.clear();
    }
}
