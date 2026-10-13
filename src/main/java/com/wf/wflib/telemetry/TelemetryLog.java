package com.wf.wflib.telemetry;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.recon.ReconOwners;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reporter events -> PDA carriers hearing that reporter ({@link TelemetryLink#receives}). Only while
 * {@link TelemetryReporter#datalink()} transmits. Last {@link #RING} kept for history requests; not saved.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class TelemetryLog {
    static final int RING = TelemetryLogPacket.MAX_ENTRIES;
    /** Per-hit / tier-handoff noise. */
    private static final Set<WFEventType> SILENT = EnumSet.of(WFEventType.DAMAGED, WFEventType.OFFLOAD,
            WFEventType.ONLOAD);

    private record Event(TelemetryLogPacket.Entry entry, Telemetry telemetry) {
    }

    private static final ArrayDeque<Event> HISTORY = new ArrayDeque<>();
    private static final List<Event> PENDING = new ArrayList<>();
    private static long seq;

    private TelemetryLog() {
    }

    /** {@code reporter} must be a {@link TelemetryReporter}. Server thread. */
    public static void post(Entity reporter, WFEventType type, String detail) {
        if (SILENT.contains(type)) {
            return;
        }
        Telemetry telemetry = ((TelemetryReporter) reporter).datalink();
        if (telemetry == null) {
            return;
        }
        Event event = new Event(new TelemetryLogPacket.Entry(++seq, reporter.level().getDayTime(),
                TelemetryLink.trackId(reporter.getUUID()), telemetry.kind(), telemetry.label(), type, detail,
                reporter.getBlockX(), reporter.getBlockY(), reporter.getBlockZ(), false), telemetry);
        HISTORY.addLast(event);
        if (HISTORY.size() > RING) {
            HISTORY.removeFirst();
        }
        PENDING.add(event);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (Pda.carries(player)) {
                TelemetryLogPacket packet = filter(player, PENDING);
                if (!packet.entries().isEmpty()) {
                    PacketDistributor.sendToPlayer(player, packet);
                }
            }
        }
        PENDING.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HISTORY.clear();
        PENDING.clear();
        seq = 0;
    }

    /** History after {@code after}; nothing without a PDA. */
    public static TelemetryLogPacket since(ServerPlayer viewer, long after) {
        if (!Pda.carries(viewer)) {
            return new TelemetryLogPacket(List.of());
        }
        List<Event> newer = new ArrayList<>();
        for (Event event : HISTORY) {
            if (event.entry().seq() > after) {
                newer.add(event);
            }
        }
        return filter(viewer, newer);
    }

    private static TelemetryLogPacket filter(ServerPlayer viewer, Iterable<Event> events) {
        UUID side = ReconOwners.owningEntity(viewer);
        List<TelemetryLogPacket.Entry> out = new ArrayList<>();
        for (Event event : events) {
            if (TelemetryLink.receives(viewer.getUUID(), side, event.telemetry())) {
                out.add(event.entry().owned(viewer.getUUID().equals(event.telemetry().operator())));
            }
        }
        return new TelemetryLogPacket(out);
    }
}
