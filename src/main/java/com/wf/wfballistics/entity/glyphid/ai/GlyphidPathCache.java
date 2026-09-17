package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.debug.SwarmProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One A* per group of glyphids going the same way, instead of one per glyphid. */
public final class GlyphidPathCache {

    /** Quantisation of both search endpoints. */
    private static final int CELL = 4;
    private static final int TTL = 20;
    /** Cleared wholesale past this size: the cache is rebuilt every second, so the crude policy is right. */
    private static final int MAX_ENTRIES = 512;

    private record Key(ResourceKey<Level> dimension, int startX, int startY, int startZ,
                       int destX, int destY, int destZ) {
    }

    private record Entry(@Nullable Path path, int tick) {
    }

    private static final Map<Key, Entry> CACHE = new HashMap<>();

    private GlyphidPathCache() {
    }

    private static int cell(int value) {
        return Math.floorDiv(value, CELL);
    }

    private static Key key(Level level, BlockPos start, BlockPos destination) {
        return new Key(level.dimension(), cell(start.getX()), cell(start.getY()), cell(start.getZ()),
                cell(destination.getX()), cell(destination.getY()), cell(destination.getZ()));
    }

    /**
     * @return a private copy of a recent path for this route, or null if nobody has searched it lately
     */
    public static @Nullable Path lookup(Level level, BlockPos start, BlockPos destination, int tick) {
        Entry entry = CACHE.get(key(level, start, destination));
        if (entry == null || tick - entry.tick() > TTL || tick < entry.tick()) {
            SwarmProfiler.count(SwarmProfiler.Counter.PATH_MISS, 1L);
            return null;
        }
        SwarmProfiler.count(SwarmProfiler.Counter.PATH_HIT, 1L);
        return entry.path() == null ? null : copy(entry.path());
    }

    /**
     * Record a search result, including a failed one: proving a route does not exist costs more than finding one,
     * and is exactly as worth sharing.
     */
    public static void store(Level level, BlockPos start, BlockPos destination, @Nullable Path path, int tick) {
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(key(level, start, destination), new Entry(path, tick));
    }

    public static void clear() {
        CACHE.clear();
    }

    public static int size() {
        return CACHE.size();
    }

    private static Path copy(Path path) {
        List<Node> nodes = new ArrayList<>(path.getNodeCount());
        for (int i = 0; i < path.getNodeCount(); i++) {
            nodes.add(path.getNode(i));
        }
        return new Path(nodes, path.getTarget(), path.canReach());
    }
}
