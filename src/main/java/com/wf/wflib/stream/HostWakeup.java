package com.wf.wflib.stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.DetachedBodyHost;
import com.wf.wflib.config.WFConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Load a sleeping {@link DetachedBodyHost} by id, then hand it to a callback (remote connect). */
@EventBusSubscriber(modid = WFLib.MODID)
public final class HostWakeup {
    // TODO: keep a host loaded while it still has velocity; unloading mid-flight freezes it in the air.

    public enum Result {
        LOADED,
        WAKING,
        UNKNOWN,
        OTHER_DIMENSION
    }

    @FunctionalInterface
    public interface WakeCallback {

        void onWake(ServerPlayer requester, Entity host);

        default void onTimeout(ServerPlayer requester) {
        }

    }

    private static final Comparator<Unit> UNIT_COMPARATOR = (a, b) -> 0;
    private static final TicketType<Unit> WAKE_TICKET = TicketType.create("wf_host_wakeup", UNIT_COMPARATOR, 200);
    private static final List<Pending> PENDING = new ArrayList<>();

    private HostWakeup() {
    }

    static void recordPosition(Entity host) {
        if (!(host instanceof DetachedBodyHost) || !(host.level() instanceof ServerLevel level)) {
            return;
        }
        HostWakeupData.get(level.getServer()).put(host.getUUID(), level.dimension(),
                host.position(), level.getGameTime());
    }

    static void recordSleep(Entity host) {
        if (!(host instanceof DetachedBodyHost) || !(host.level() instanceof ServerLevel level)) {
            return;
        }
        Entity.RemovalReason reason = host.getRemovalReason();
        if (reason != null && !reason.shouldSave()) {
            HostWakeupData.get(level.getServer()).remove(host.getUUID());
            StreamDebug.log(StreamDebug.Category.WAKEUP, "host {} removed ({}), forgotten",
                    StreamDebug.shortId(host.getUUID()), reason);
            return;
        }
        recordPosition(host);
        StreamDebug.log(StreamDebug.Category.WAKEUP, "host {} sleeping at {} in {}",
                StreamDebug.shortId(host.getUUID()), host.chunkPosition(), level.dimension().location());
    }

    @Nullable
    public static HostWakeupData.Entry lookup(MinecraftServer server, UUID hostId) {
        return HostWakeupData.get(server).get(hostId);
    }

    public static Result request(ServerPlayer requester, UUID hostId, WakeCallback callback) {
        MinecraftServer server = requester.server;
        HostWakeupData.Entry entry = lookup(server, hostId);
        if (entry == null) {
            StreamDebug.log(StreamDebug.Category.WAKEUP, "{} requested unknown host {}",
                    requester.getScoreboardName(), StreamDebug.shortId(hostId));
            return Result.UNKNOWN;
        }
        if (entry.dimension() != requester.level().dimension()) {
            StreamDebug.log(StreamDebug.Category.WAKEUP, "{} requested host {} in {}, but stands in {}",
                    requester.getScoreboardName(), StreamDebug.shortId(hostId),
                    entry.dimension().location(), requester.level().dimension().location());
            return Result.OTHER_DIMENSION;
        }
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) {
            return Result.UNKNOWN;
        }
        Entity present = level.getEntity(hostId);
        if (present != null && isReady(present)) {
            callback.onWake(requester, present);
            return Result.LOADED;
        }
        for (Pending pending : PENDING) {
            if (pending.hostId.equals(hostId) && pending.requesterId.equals(requester.getUUID())) {
                return Result.WAKING;
            }
        }
        Pending pending = new Pending(hostId, requester.getUUID(), entry, callback,
                WFConfig.WAKEUP_TIMEOUT.get());
        PENDING.add(pending);
        ticket(level, entry.chunk());
        StreamDebug.log(StreamDebug.Category.WAKEUP,
                "{} waking host {} at chunk {} in {} (timeout {}t)",
                requester.getScoreboardName(), StreamDebug.shortId(hostId), entry.chunk(),
                entry.dimension().location(), pending.ticksLeft);
        return Result.WAKING;
    }

    static void shutdown() {
        PENDING.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        Iterator<Pending> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            ServerPlayer requester = server.getPlayerList().getPlayer(pending.requesterId);
            ServerLevel level = server.getLevel(pending.entry.dimension());
            if (requester == null || level == null) {
                iterator.remove();
                continue;
            }
            Entity host = level.getEntity(pending.hostId);
            if (host != null && isReady(host)) {
                iterator.remove();
                StreamDebug.log(StreamDebug.Category.WAKEUP, "host {} woke at {} after {}t",
                        StreamDebug.shortId(pending.hostId), host.chunkPosition(),
                        pending.waitedTicks());
                pending.callback.onWake(requester, host);
                continue;
            }
            pending.ticksLeft--;
            if (pending.ticksLeft <= 0) {
                iterator.remove();
                pending.onTimeout(requester);
                continue;
            }
            if (pending.ticksLeft % 20 == 0) {
                ticket(level, pending.entry.chunk());
            }
        }
    }

    /** Tick 1: a host may eject passengers it deserialised; connecting then mounts and ejects the operator. */
    private static boolean isReady(Entity host) {
        return host.tickCount > 1;
    }

    private static void ticket(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().addRegionTicket(WAKE_TICKET, pos,
                WFConfig.WAKEUP_TICKET_RADIUS.get(), Unit.INSTANCE);
        StreamDebug.log(StreamDebug.Category.TICKET, "wakeup ticket at {} in {}",
                pos, level.dimension().location());
    }

    private static final class Pending {

        private final UUID hostId;
        private final UUID requesterId;
        private final HostWakeupData.Entry entry;
        private final WakeCallback callback;
        private final int timeout;
        private int ticksLeft;

        private Pending(UUID hostId, UUID requesterId, HostWakeupData.Entry entry,
                        WakeCallback callback, int timeout) {
            this.hostId = hostId;
            this.requesterId = requesterId;
            this.entry = entry;
            this.callback = callback;
            this.timeout = timeout;
            this.ticksLeft = timeout;
        }

        private int waitedTicks() {
            return this.timeout - this.ticksLeft;
        }

        private void onTimeout(ServerPlayer requester) {
            StreamDebug.warn(StreamDebug.Category.WAKEUP,
                    "host {} did not load within {}t at chunk {} in {} (requested by {})",
                    StreamDebug.shortId(this.hostId), this.timeout, this.entry.chunk(),
                    this.entry.dimension().location(), requester.getScoreboardName());
            this.callback.onTimeout(requester);
        }

    }

}
