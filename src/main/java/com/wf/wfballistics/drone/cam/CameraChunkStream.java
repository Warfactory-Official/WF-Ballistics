package com.wf.wfballistics.drone.cam;

import com.wf.wfballistics.block.SecurityCameraBlock;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.mixin.AccessorChunkMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    /** Ticks between re-stamping the ticket window when the drone has not changed chunk. */
    private static final int TICKET_REFRESH = 40;

    private static final Map<UUID, Viewer> VIEWERS = new HashMap<>();
    private static final Set<UUID> CAPABLE = new HashSet<>();
    private static final Map<ResourceKey<Level>, Map<Integer, Window>> WINDOWS = new HashMap<>();

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

    public static void forget(UUID player) {
        CAPABLE.remove(player);
        VIEWERS.remove(player);
    }

    // --- the pass -------------------------------------------------------------------------------------

    /**
     * Bring every observer's terrain up to date.
     *
     * @param audiences feed id to the players watching it, exactly as the video uses
     */
    public static void update(ServerLevel level, Map<Integer, List<ServerPlayer>> audiences) {
        if (audiences.isEmpty() && VIEWERS.isEmpty()) {
            return;
        }
        int radius = WFConfig.CAMERA_STREAM_RADIUS.get();
        Map<Integer, Window> windows = WINDOWS.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        Map<Integer, LongOpenHashSet> streamed = new HashMap<>();
        Set<ServerPlayer> changed = new HashSet<>();

        if (radius > 0 && !audiences.isEmpty()) {
            long now = level.getGameTime();
            int budget = Math.max(1, WFConfig.CAMERA_STREAM_BUDGET.get() / audiences.size());
            for (Map.Entry<Integer, List<ServerPlayer>> entry : audiences.entrySet()) {
                int feedId = entry.getKey();
                ChunkPos centre = centreOf(level, feedId);
                if (centre == null) {
                    continue;
                }
                List<Recipient> recipients = recipients(level, feedId, entry.getValue());
                if (recipients.isEmpty()) {
                    continue;
                }
                Window window = windows.computeIfAbsent(feedId, id -> new Window());
                if (window.centre != centre.toLong() || now - window.stampedAt >= TICKET_REFRESH) {
                    stampTickets(level, centre, radius);
                    window.centre = centre.toLong();
                    window.stampedAt = now;
                }
                streamed.put(feedId, stream(level, centre, radius, recipients, budget, changed));
            }
        }

        windows.keySet().removeIf(feedId -> !audiences.containsKey(feedId));
        trim(level, streamed, changed);
        for (ServerPlayer player : changed) {
            rescanEntities(level, player);
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
        return entity instanceof DroneEntity drone && drone.isAlive() && drone.cameraSpec() != null
                ? drone.chunkPosition() : null;
    }

    /**
     * @return whether this entity is standing in terrain streamed to this player, and should therefore be
     *      tracked to them however far away it is. Called from {@code MixinChunkMapTrackedEntity} for every
     *      tracked entity against every player, so the empty case has to be (and is) two field reads.
     */
    public static boolean reveals(Entity entity, ServerPlayer player) {
        if (VIEWERS.isEmpty()) {
            return false;
        }
        Viewer viewer = VIEWERS.get(player.getUUID());
        if (viewer == null || viewer.byFeed.isEmpty()) {
            return false;
        }
        ChunkPos pos = entity.chunkPosition();
        long key = ChunkPos.asLong(pos.x, pos.z);
        for (LongOpenHashSet sent : viewer.byFeed.values()) {
            if (sent.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** Re-run every tracked entity's visibility decision for one observer. */
    private static void rescanEntities(ServerLevel level, ServerPlayer player) {
        for (Object tracked : ((AccessorChunkMap) level.getChunkSource().chunkMap).wfCamEntityMap().values()) {
            ((FeedTracked) tracked).wfCamUpdatePlayer(player);
        }
    }

    /**
     * The observers of one feed that are worth streaming to, paired with what each already holds.
     */
    private static List<Recipient> recipients(ServerLevel level, int feedId, List<ServerPlayer> players) {
        List<Recipient> out = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            if (!CAPABLE.contains(player.getUUID()) || player.level() != level) {
                continue;
            }
            Viewer viewer = VIEWERS.computeIfAbsent(player.getUUID(), id -> new Viewer(level.dimension()));
            viewer.player = player;
            if (viewer.dimension != level.dimension()) {
                viewer.byFeed.clear();
                viewer.dimension = level.dimension();
            }
            out.add(new Recipient(player, viewer.byFeed.computeIfAbsent(feedId, id -> new LongOpenHashSet())));
        }
        return out;
    }

    /**
     * Send the window around {@code centre}, nearest ring first, to whoever is missing each chunk.
     *
     * @return every chunk in the window, whether or not it went out this pass: the caller trims against it
     */
    private static LongOpenHashSet stream(ServerLevel level, ChunkPos centre, int radius,
                                          List<Recipient> recipients, int budget,
                                          Set<ServerPlayer> changed) {
        LongOpenHashSet window = new LongOpenHashSet();
        List<Recipient> needed = new ArrayList<>(recipients.size());
        int spent = 0;
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int cx = centre.x + dx;
                    int cz = centre.z + dz;
                    long key = ChunkPos.asLong(cx, cz);
                    window.add(key);

                    needed.clear();
                    for (Recipient recipient : recipients) {
                        if (recipient.player.getChunkTrackingView().contains(cx, cz)) {
                            if (recipient.sent.remove(key)) {
                                changed.add(recipient.player);
                            }
                        } else if (!recipient.sent.contains(key)) {
                            needed.add(recipient);
                        }
                    }
                    if (needed.isEmpty() || spent >= budget) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        // The ticket was only just stamped; it will be here in a tick or two.
                        continue;
                    }

                    // The one line this class exists for: built once, handed to everybody.
                    Packet<?> packet = chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(
                            new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                    chunkEncodes++;
                    spent++;
                    for (Recipient recipient : needed) {
                        recipient.player.connection.send(packet);
                        recipient.sent.add(key);
                        changed.add(recipient.player);
                        chunkDeliveries++;
                    }
                }
            }
        }
        return window;
    }

    /** Take back everything no feed still wants. */
    private static void trim(ServerLevel level, Map<Integer, LongOpenHashSet> streamed,
                             Set<ServerPlayer> changed) {
        for (Iterator<Map.Entry<UUID, Viewer>> it = VIEWERS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Viewer> entry = it.next();
            Viewer viewer = entry.getValue();
            if (viewer.dimension != level.dimension()) {
                continue;
            }
            ServerPlayer player = viewer.player;
            if (player == null || player.isRemoved() || player.level() != level) {
                it.remove();
                continue;
            }
            LongOpenHashSet keep = new LongOpenHashSet();
            for (Map.Entry<Integer, LongOpenHashSet> feed : viewer.byFeed.entrySet()) {
                LongOpenHashSet window = streamed.get(feed.getKey());
                if (window != null) {
                    keep.addAll(window);
                }
            }
            for (Map.Entry<Integer, LongOpenHashSet> feed : viewer.byFeed.entrySet()) {
                for (LongIterator chunks = feed.getValue().iterator(); chunks.hasNext(); ) {
                    long key = chunks.nextLong();
                    if (keep.contains(key)) {
                        continue;
                    }
                    player.connection.send(new ClientboundForgetLevelChunkPacket(new ChunkPos(key)));
                    chunks.remove();
                    changed.add(player);
                }
            }
            viewer.byFeed.values().removeIf(LongOpenHashSet::isEmpty);
            if (viewer.byFeed.isEmpty()) {
                it.remove();
            }
        }
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
        return VIEWERS.size();
    }

    public static int streamedChunks() {
        int total = 0;
        for (Viewer viewer : VIEWERS.values()) {
            for (LongOpenHashSet set : viewer.byFeed.values()) {
                total += set.size();
            }
        }
        return total;
    }

    public static void resetCounters() {
        chunkEncodes = 0;
        chunkDeliveries = 0;
    }

    public static void shutdown() {
        VIEWERS.clear();
        CAPABLE.clear();
        WINDOWS.clear();
        resetCounters();
    }

    // --- state ----------------------------------------------------------------------------------------

    private record Recipient(ServerPlayer player, LongOpenHashSet sent) {
    }

    private static final class Viewer {
        private final Map<Integer, LongOpenHashSet> byFeed = new HashMap<>();
        private ResourceKey<Level> dimension;
        /** Refreshed every pass this viewer is streamed to; see the comment in {@link #trim}. */
        @Nullable
        private ServerPlayer player;

        Viewer(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }

    private static final class Window {
        private long centre = Long.MIN_VALUE;
        private long stampedAt = Long.MIN_VALUE;
    }
}
