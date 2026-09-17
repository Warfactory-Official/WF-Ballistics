package com.wf.wfballistics.recon.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One network's physical topology: who can talk to whom, and what dies when you break a block. */
public final class ReconGrid {

    /** Ticks a node may go without its owner refreshing it before it drops off. */
    public static final int NODE_TIMEOUT = 60;
    /** Ticks between unforced relinks. */
    public static final int RELINK_INTERVAL = 100;
    /** Links a node may sit behind the hub. */
    public static final int MAX_HOPS = 8;
    /** Blocks a link may span, unless a node asks for less. */
    public static final double DEFAULT_LINK_RANGE = 128.0;
    /** Blocks the ground must rise above the link line before it blocks it. */
    public static final double LINK_MARGIN = 2.0;
    /** Blocks between height samples along a link. */
    private static final int SAMPLE = 8;

    private final long netId;
    private final Map<Long, GridNode> nodes = new LinkedHashMap<>();

    private int epoch;
    private int linkedEpoch = -1;
    private long lastLink = Long.MIN_VALUE;
    private int onlineCount;
    private int rootCount;

    /**
     * Owned by the {@code ReconNetwork} with the same id, which is the only thing that should be calling the
     * mutators below.
     */
    public ReconGrid(long netId) {
        this.netId = netId;
    }

    public long netId() {
        return netId;
    }

    public Collection<GridNode> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public int nodeCount() {
        return nodes.size();
    }

    /**
     * @return how many nodes currently have a path to a hub.
     */
    public int onlineCount() {
        return onlineCount;
    }

    /**
     * @return how many hubs are on this net. Zero means every probe is orphaned, which is the single most
     *      common reason a freshly built grid shows nothing.
     */
    public int rootCount() {
        return rootCount;
    }

    @Nullable
    public GridNode node(BlockPos pos) {
        return nodes.get(pos.asLong());
    }

    /**
     * @return this node's hop count, or {@code -1} if there is no node here at all.
     */
    public int hopsAt(BlockPos pos) {
        GridNode node = nodes.get(pos.asLong());
        return node == null ? -1 : node.hops();
    }

    /**
     * The topology as the detection layer needs it, which is subtly different from {@link #hopsAt}.
     *
     * @return hop count; {@code 0} for a sensor that is not a grid member; {@code -1} for one that is a member
     *      and currently has no route to a hub, which means it must not contribute at all.
     */
    public int hopsForSensor(BlockPos pos) {
        GridNode node = nodes.get(pos.asLong());
        return node == null ? 0 : node.hops();
    }

    public GridNode register(BlockPos pos, double linkRange, double mastHeight, boolean root, String label,
                             long gameTime) {
        GridNode existing = nodes.get(pos.asLong());
        if (existing != null) {
            boolean shape = existing.root() != root || existing.linkRange() != linkRange;
            existing.refresh(linkRange, mastHeight, root, label, gameTime);
            if (shape) {
                epoch++;
            }
            return existing;
        }
        GridNode node = new GridNode(pos, linkRange, mastHeight, root, label, gameTime);
        nodes.put(node.id(), node);
        epoch++;
        return node;
    }

    public void unregister(BlockPos pos) {
        if (nodes.remove(pos.asLong()) != null) {
            epoch++;
        }
    }

    public boolean isQuiet() {
        return nodes.isEmpty();
    }

    /**
     * Drop timed-out nodes, and relink if anything has changed or enough time has passed.
     */
    public void tick(ServerLevel level, long gameTime) {
        for (Iterator<Map.Entry<Long, GridNode>> it = nodes.entrySet().iterator(); it.hasNext(); ) {
            if (gameTime - it.next().getValue().lastRefresh() > NODE_TIMEOUT) {
                it.remove();
                epoch++;
            }
        }
        if (epoch != linkedEpoch || gameTime - lastLink >= RELINK_INTERVAL) {
            relink(level);
            linkedEpoch = epoch;
            lastLink = gameTime;
        }
    }

    /** Breadth-first from every hub, so each node ends up on its shortest path to one. */
    private void relink(ServerLevel level) {
        for (GridNode node : nodes.values()) {
            node.clearRoute();
        }
        List<GridNode> unvisited = new ArrayList<>(nodes.size());
        Deque<GridNode> frontier = new ArrayDeque<>();
        int roots = 0;
        for (GridNode node : nodes.values()) {
            if (node.root()) {
                node.route(0, 0L);
                frontier.add(node);
                roots++;
            } else {
                unvisited.add(node);
            }
        }
        this.rootCount = roots;

        while (!frontier.isEmpty()) {
            GridNode from = frontier.poll();
            if (from.hops() >= MAX_HOPS) {
                continue;
            }
            for (Iterator<GridNode> it = unvisited.iterator(); it.hasNext(); ) {
                GridNode to = it.next();
                if (!from.withinRange(to) || !clearBetween(level, from, to)) {
                    continue;
                }
                to.route(from.hops() + 1, from.id());
                it.remove();
                frontier.add(to);
            }
        }

        int online = 0;
        for (GridNode node : nodes.values()) {
            if (!node.online()) {
                continue;
            }
            online++;
            long up = node.uplink();
            for (int guard = 0; up != 0L && guard <= MAX_HOPS; guard++) {
                GridNode parent = nodes.get(up);
                if (parent == null) {
                    break;
                }
                parent.addDownstream();
                up = parent.uplink();
            }
        }
        this.onlineCount = online;
    }

    /** Surface line of sight between two nodes, as a height-profile walk. */
    private static boolean clearBetween(ServerLevel level, GridNode from, GridNode to) {
        double x0 = from.pos().getX() + 0.5;
        double z0 = from.pos().getZ() + 0.5;
        double y0 = from.eyeY();
        double dx = (to.pos().getX() + 0.5) - x0;
        double dz = (to.pos().getZ() + 0.5) - z0;
        double dy = to.eyeY() - y0;
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / SAMPLE);
        if (steps <= 1) {
            return true;
        }
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int sx = (int) Math.floor(x0 + dx * t);
            int sz = (int) Math.floor(z0 + dz * t);
            LevelChunk chunk = level.getChunkSource().getChunkNow(sx >> 4, sz >> 4);
            if (chunk == null) {
                continue;
            }
            // The lower of the two heightmaps, which is the top of the solid ground with leaves and fluid
            // both discounted. MOTION_BLOCKING_NO_LEAVES counts water as surface and on its own makes a
            // submerged node unreachable at any range - a hydrophone is wired along the bottom, not talking
            // through the sea - while OCEAN_FLOOR counts a leaf canopy as ground.
            int ground = Math.min(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx & 15, sz & 15),
                    chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, sx & 15, sz & 15));
            if (ground - LINK_MARGIN > y0 + dy * t) {
                return false;
            }
        }
        return true;
    }
}
