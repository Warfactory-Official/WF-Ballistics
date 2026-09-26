package com.wf.wflib.drone.cam;

import com.wf.wflib.block.SecurityCameraBlock;
import com.wf.wflib.config.WFConfig;
import com.wf.wflib.stream.ChunkStreams;
import com.wf.wflib.stream.StreamWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Terrain for a drone that is nowhere near its audience. */
public final class CameraChunkStream {

    /** Residency for a chunk the camera needs to read. */
    private static final TicketType<Unit> CAMERA_TICKET =
            TicketType.create("wf_camera_feed", (a, b) -> 0, 100);
    /** Window centre = entity + velocity * this, capped at half the radius. */
    private static final double STREAM_LEAD_TICKS = 20.0;
    /** Ticks between re-stamping the ticket window when the drone has not changed chunk. */
    private static final int TICKET_REFRESH = 40;

    private static final Set<UUID> CAPABLE = new HashSet<>();
    private static final Map<ResourceKey<Level>, Map<Integer, StreamWindow>> WINDOWS = new HashMap<>();

    private static long chunkEncodes;
    private static long chunkDeliveries;

    private CameraChunkStream() {
    }

    // --- capability -----------------------------------------------------------------------------------

    /** Record whether this client can actually render terrain outside its own view. */
    public static void setCapable(UUID player, boolean capable) {
        if (capable) {
            CAPABLE.add(player);
        } else {
            CAPABLE.remove(player);
        }
    }

    public static void forget(ServerPlayer player) {
        CAPABLE.remove(player.getUUID());
        for (Map<Integer, StreamWindow> windows : WINDOWS.values()) {
            for (StreamWindow window : windows.values()) {
                window.players().remove(player);
            }
        }
    }

    // --- the pass -------------------------------------------------------------------------------------

    /**
     * Bring every observer's terrain up to date.
     *
     * @param audiences feed id to the players watching it, exactly as the video uses
     */
    public static void update(ServerLevel level, Map<Integer, List<ServerPlayer>> audiences) {
        Map<Integer, StreamWindow> windows = WINDOWS.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        if (audiences.isEmpty() && windows.isEmpty()) {
            return;
        }
        int radius = WFConfig.CAMERA_STREAM_RADIUS.get();
        long now = level.getGameTime();
        Set<StreamWindow> live = Collections.newSetFromMap(new IdentityHashMap<>());
        if (radius > 0 && !audiences.isEmpty()) {
            int budget = Math.max(1, WFConfig.CAMERA_STREAM_BUDGET.get() / audiences.size());
            for (Map.Entry<Integer, List<ServerPlayer>> entry : audiences.entrySet()) {
                ChunkPos centre = centreOf(level, entry.getKey());
                if (centre == null) {
                    continue;
                }
                StreamWindow window = windows.computeIfAbsent(entry.getKey(), id -> new StreamWindow());
                live.add(window);
                if (window.restamp(centre, now, TICKET_REFRESH)) {
                    stampTickets(level, centre, radius);
                }
                // Built once, handed to everybody watching this feed.
                ChunkStreams.Encoder encoder = new ChunkStreams.Encoder(level, budget);
                List<ServerPlayer> capable = entry.getValue().stream()
                        .filter(player -> CAPABLE.contains(player.getUUID())).toList();
                chunkDeliveries += window.publish(level, centre, radius, capable, Integer.MAX_VALUE, encoder);
                chunkEncodes += encoder.encodes();
            }
        }
        for (Iterator<StreamWindow> it = windows.values().iterator(); it.hasNext(); ) {
            StreamWindow window = it.next();
            if (!live.contains(window)) {
                window.close();
                it.remove();
            }
        }
    }

    /**
     * @return the chunk a feed's window is centred on, or null if this feed should not be streamed for.
     */
    @Nullable
    private static ChunkPos centreOf(ServerLevel level, int feedId) {
        if (feedId < 0) {
            GlobalPos at = StaticCameraFeeds.posFor(feedId);
            if (at == null || !at.dimension().equals(level.dimension())) {
                return null;
            }
            BlockPos pos = at.pos();
            if (level.hasChunkAt(pos)
                    && !(level.getBlockState(pos).getBlock() instanceof SecurityCameraBlock)) {
                StaticCameraFeeds.invalidate(level, pos);
                return null;
            }
            return new ChunkPos(pos);
        }
        Entity entity = level.getEntity(feedId);
        if (entity == null || !entity.isAlive() || CameraNet.specOf(entity) == null) {
            return null;
        }
        // Led by its own velocity: a TV round crosses a chunk every few ticks and would outrun a centred window.
        int radius = WFConfig.CAMERA_STREAM_RADIUS.get();
        Vec3 lead = entity.getDeltaMovement().scale(STREAM_LEAD_TICKS);
        double cap = radius * 8.0;
        if (lead.lengthSqr() > cap * cap) {
            lead = lead.normalize().scale(cap);
        }
        return new ChunkPos(BlockPos.containing(entity.position().add(lead)));
    }

    /** Keep the window loaded. */
    private static void stampTickets(ServerLevel level, ChunkPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                level.getChunkSource().addRegionTicket(
                        CAMERA_TICKET, new ChunkPos(centre.x + dx, centre.z + dz), 0, Unit.INSTANCE);
            }
        }
    }

    // --- diagnostics ----------------------------------------------------------------------------------

    /** Chunk packets built. The number that costs CPU and allocation. */
    public static long chunkEncodeCount() {
        return chunkEncodes;
    }

    /** Chunk packets handed to a connection. The gap against {@link #chunkEncodeCount()} is what was saved. */
    public static long chunkDeliveryCount() {
        return chunkDeliveries;
    }

    public static int streamingViewers() {
        Set<ServerPlayer> players = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map<Integer, StreamWindow> windows : WINDOWS.values()) {
            for (StreamWindow window : windows.values()) {
                players.addAll(window.players());
            }
        }
        return players.size();
    }

    public static void resetCounters() {
        chunkEncodes = 0;
        chunkDeliveries = 0;
    }

    public static void shutdown() {
        CAPABLE.clear();
        WINDOWS.clear();
        resetCounters();
    }
}
