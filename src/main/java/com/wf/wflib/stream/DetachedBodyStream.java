package com.wf.wflib.stream;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.DetachedBodyHost;
import com.wf.wflib.config.WFConfig;
import com.wf.wflib.mixin.AccessorChunkMap;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Operator view on a {@link DetachedBodyHost}: client chunk cache centred on the host, terrain streamed around it,
 * body parked (view distance, tickets, entity tracking per {@link DetachedBodies}).
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class DetachedBodyStream {

    /** Region ticket: host surroundings tick like a player's. */
    private static final TicketType<Unit> STREAM_TICKET = TicketType.create("wf_detached_stream", (a, b) -> 0, 40);
    private static final TicketType<Unit> BODY_TICKET = TicketType.create("wf_detached_body", (a, b) -> 0, 40);
    private static final int TICKET_REFRESH = 20;
    private static final int WAKEUP_RECORD_INTERVAL = 40;

    private static final Set<Entity> HOSTS = new HashSet<>();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static long ticks;

    private DetachedBodyStream() {
    }

    private static final class Session {
        private final ServerPlayer operator;
        private final StreamWindow window = new StreamWindow();
        private long bodyChunk = Long.MIN_VALUE;

        private Session(ServerPlayer operator) {
            this.operator = operator;
        }
    }

    private record View(ServerPlayer operator, Entity host, ServerLevel level) {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tick(event.getServer());
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof DetachedBodyHost) {
            HOSTS.add(event.getEntity());
            HostWakeup.recordPosition(event.getEntity());
            StreamDebug.log(StreamDebug.Category.SESSION, "tracking host {} @ {}",
                    StreamDebug.shortId(event.getEntity().getUUID()), event.getEntity().chunkPosition());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof DetachedBodyHost) {
            HOSTS.remove(event.getEntity());
            HostWakeup.recordSleep(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            forget(player.getUUID(), "logout");
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null) {
                releaseToBody(session, "death");
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HOSTS.clear();
        SESSIONS.clear();
        DetachedBodies.shutdown();
        HostWakeup.shutdown();
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % 20 == 0) {
            StreamDebug.refresh();
        }
        if (HOSTS.isEmpty() && SESSIONS.isEmpty()) {
            return;
        }
        int radius = Math.min(WFConfig.DETACHED_STREAM_RADIUS.get(), server.getPlayerList().getViewDistance());
        int budget = WFConfig.DETACHED_CHUNKS_PER_TICK.get();
        boolean heartbeat = ticks % StreamDebug.heartbeatTicks() == 0;

        Map<UUID, View> views = collect(radius);
        DetachedBodies.publish(views.isEmpty() ? Set.of() : Set.copyOf(views.keySet()));

        for (Session session : new ArrayList<>(SESSIONS.values())) {
            if (session.operator.isRemoved()) {
                forget(session.operator.getUUID(), "removed");
            } else if (!views.containsKey(session.operator.getUUID())) {
                releaseToBody(session, "stopped-viewing");
            }
        }
        for (View view : views.values()) {
            stream(view, radius, budget, heartbeat);
        }
        if (ticks % WAKEUP_RECORD_INTERVAL == 0) {
            for (Entity host : HOSTS) {
                HostWakeup.recordPosition(host);
            }
        }
    }

    private static Map<UUID, View> collect(int radius) {
        Map<UUID, View> views = new HashMap<>();
        for (Entity entity : HOSTS) {
            if (!(entity.level() instanceof ServerLevel level)) {
                continue;
            }
            DetachedBodyHost host = (DetachedBodyHost) entity;
            String hostTag = "host " + StreamDebug.shortId(entity.getUUID());
            if (!host.isDetachedBodyActive()) {
                StreamDebug.state(StreamDebug.Category.SESSION, hostTag, "idle (no detached operator)");
                continue;
            }
            for (Entity passenger : entity.getPassengers()) {
                if (!(passenger instanceof ServerPlayer operator)) {
                    continue;
                }
                String tag = hostTag + "/" + operator.getScoreboardName();
                if (host.getDetachedBodyAnchor(operator) == null) {
                    StreamDebug.state(StreamDebug.Category.SESSION, tag, "riding without a body anchor");
                } else if (operator.level() != level) {
                    StreamDebug.state(StreamDebug.Category.SESSION, tag, "body is in another dimension");
                } else {
                    StreamDebug.state(StreamDebug.Category.SESSION, tag,
                            "streaming host chunk " + entity.chunkPosition() + " radius " + radius);
                    views.put(operator.getUUID(), new View(operator, entity, level));
                }
            }
        }
        return views;
    }

    private static void stream(View view, int radius, int budget, boolean heartbeat) {
        ServerPlayer operator = view.operator();
        ServerLevel level = view.level();
        ChunkPos target = view.host().chunkPosition();
        ChunkPos body = operator.chunkPosition();
        StreamDebug.Session debug = StreamDebug.session(operator.getUUID(), operator.getScoreboardName());
        debug.host = StreamDebug.shortId(view.host().getUUID());
        debug.center = target;
        debug.radius = radius;

        Session session = SESSIONS.get(operator.getUUID());
        boolean firstView = session == null;
        if (firstView) {
            session = new Session(operator);
            SESSIONS.put(operator.getUUID(), session);
        }
        boolean moved = session.window.centre() != target.toLong();
        boolean bodyMoved = session.bodyChunk != body.toLong();
        session.bodyChunk = body.toLong();

        if (session.window.restamp(target, ticks, TICKET_REFRESH)) {
            level.getChunkSource().addRegionTicket(STREAM_TICKET, target, radius, Unit.INSTANCE);
            debug.ticketsIssued++;
            StreamDebug.log(StreamDebug.Category.TICKET, "{}: region ticket {} radius {}",
                    operator.getScoreboardName(), target, radius);
        }
        if (firstView || bodyMoved) {
            parkBody(operator, level, body);
        }
        // After parkBody: vanilla re-centres on the body whenever the body changes chunk.
        if (moved || bodyMoved) {
            operator.connection.send(new ClientboundSetChunkCacheCenterPacket(target.x, target.z));
            debug.centerUpdates++;
            StreamDebug.log(StreamDebug.Category.CENTER, "{}: cache center -> {}", operator.getScoreboardName(), target);
        }
        if (firstView) {
            StreamDebug.log(StreamDebug.Category.SESSION, "{}: view handed to host {} (body stays at chunk {})",
                    operator.getScoreboardName(), target, body);
        }
        int sent = session.window.publish(level, target, radius, List.of(operator), budget,
                new ChunkStreams.Encoder(level, budget));
        debug.chunksSent += sent;
        if (sent > 0) {
            StreamDebug.log(StreamDebug.Category.CHUNK, "{}: sent {} chunk(s) around {}",
                    operator.getScoreboardName(), sent, target);
        }
        if (heartbeat) {
            debug.heartbeat();
        }
    }

    private static void releaseToBody(Session session, String reason) {
        ServerPlayer operator = session.operator;
        UUID id = operator.getUUID();
        SESSIONS.remove(id);
        // Pilot flag cleared first: the release's entity re-scan lifts body suppression too.
        DetachedBodies.clear(id);
        session.window.close();
        ChunkPos body = operator.chunkPosition();
        operator.connection.send(new ClientboundSetChunkCacheCenterPacket(body.x, body.z));
        restoreBody(operator, operator.serverLevel());
        StreamDebug.log(StreamDebug.Category.SESSION, "{}: view handed back to body at chunk {} ({})",
                operator.getScoreboardName(), body, reason);
        StreamDebug.endSession(id, reason);
    }

    /** Pilot flags already published: {@code skipPlayer} and body view distance read the parked values. */
    private static void parkBody(ServerPlayer operator, ServerLevel level, ChunkPos body) {
        int ticketRadius = WFConfig.DETACHED_BODY_TICKET_RADIUS.get();
        if (ticketRadius >= 0) {
            level.getChunkSource().addRegionTicket(BODY_TICKET, body, ticketRadius, Unit.INSTANCE);
        }
        restoreBody(operator, level);
    }

    /** {@code move} re-registers the player ticket when {@code skipPlayer} flipped; view distance needs its own pass. */
    private static void restoreBody(ServerPlayer operator, ServerLevel level) {
        level.getChunkSource().move(operator);
        ((AccessorChunkMap) level.getChunkSource().chunkMap).wfUpdateChunkTracking(operator);
    }

    private static void forget(UUID id, String reason) {
        SESSIONS.remove(id);
        DetachedBodies.clear(id);
        StreamDebug.endSession(id, reason);
    }
}
