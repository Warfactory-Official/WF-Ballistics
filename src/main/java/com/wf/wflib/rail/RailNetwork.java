package com.wf.wflib.rail;

import com.wf.wflib.WFLib;
import com.wf.wflib.network.RailAlignmentPacket;
import com.wf.wflib.network.RailBuildPacket;
import com.wf.wflib.network.RailDeletePacket;
import com.wf.wflib.network.RailSavePacket;
import com.wf.wflib.network.RailSaveResultPacket;
import com.wf.wflib.network.RailStatusPacket;
import com.wf.wflib.network.RailRightOfWayPacket;
import com.wf.wflib.network.RailRightOfWayRequestPacket;
import com.wf.wflib.network.WFNetwork;
import com.wf.wflib.rail.align.AlignCompiler;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.AlignmentStore;
import com.wf.wflib.rail.align.RightOfWaySurvey;
import com.wf.wflib.rail.align.AlignmentEdits;
import com.wf.wflib.rail.align.AlignmentService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The rail package's own payloads, registered here so the feature stays in one piece. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class RailNetwork {

    private RailNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("rail-2").optional();

        registrar.playToServer(RailSavePacket.TYPE, RailSavePacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player
                            && player.level() instanceof ServerLevel level
                            && allowSave(player)) {
                        AlignmentEdits.SaveOutcome outcome = AlignmentEdits.save(player, level, pkt.id(),
                                pkt.name(), pkt.coreColour(), pkt.designClass(), pkt.points(),
                                pkt.baseRevision());
                        WFNetwork.sendToPlayer(player,
                                new RailSaveResultPacket(pkt.id(), outcome, revisionOf(level, pkt.id())));
                    }
                }));
        registrar.playToServer(RailStatusPacket.TYPE, RailStatusPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player
                            && player.level() instanceof ServerLevel level) {
                        AlignmentEdits.setStatus(player, level, pkt.id(), pkt.status());
                    }
                }));
        registrar.playToServer(RailBuildPacket.TYPE, RailBuildPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player
                            && player.level() instanceof ServerLevel level) {
                        AlignmentEdits.setBuilt(player, level, pkt.id(), pkt.from(), pkt.to(), pkt.laid());
                    }
                }));
        registrar.playToServer(RailDeletePacket.TYPE, RailDeletePacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player
                            && player.level() instanceof ServerLevel level) {
                        AlignmentEdits.delete(player, level, pkt.id());
                    }
                }));
        registrar.playToServer(RailRightOfWayRequestPacket.TYPE, RailRightOfWayRequestPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player
                            && player.level() instanceof ServerLevel level
                            && allowRightOfWay(player)) {
                        var centreline = AlignCompiler.compile(pkt.points(), pkt.designClass()).centreline();
                        WFNetwork.sendToPlayer(player, new RailRightOfWayPacket(
                                RightOfWaySurvey.survey(player, level, centreline, pkt.designClass())));
                    }
                }));
        registrar.playToClient(RailRightOfWayPacket.TYPE, RailRightOfWayPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(
                        () -> com.wf.wflib.client.rail.RouteOwnership.accept(pkt)));
        registrar.playToClient(RailAlignmentPacket.TYPE, RailAlignmentPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(
                        () -> com.wf.wflib.client.rail.PublishedAlignments.accept(pkt)));
        registrar.playToClient(RailSaveResultPacket.TYPE, RailSaveResultPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(
                        () -> com.wf.wflib.client.rail.PublishedAlignments.acceptResult(pkt)));
    }

    /** The version a route is at now, which is what the client must save against next. */
    private static int revisionOf(ServerLevel level, java.util.UUID id) {
        Alignment alignment = AlignmentStore.of(level).get(id);
        return alignment == null ? 0 : alignment.revision().number();
    }

    /**
     * Rate limit on the right-of-way query.
     *
     * <p>It is cheap (claim lookups over data already in memory, nothing loads a chunk), but it is
     * driven by a client dragging a point, and a client decides for itself how often to ask. This is
     * the server deciding instead.</p>
     */
    private static final long ROW_MIN_INTERVAL_MS = 150L;

    /**
     * Rate limit on saves.
     *
     * <p>A save compiles the route and writes the world's save data, so it is the expensive one. The
     * client already debounces at more than twice this, and a dropped save is not lost: the client keeps
     * its changes until an acknowledgement arrives and sends them again if one does not.</p>
     */
    private static final long SAVE_MIN_INTERVAL_MS = 400L;

    private static final java.util.Map<java.util.UUID, Long> LAST_ROW = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Long> LAST_SAVE = new java.util.HashMap<>();

    private static boolean allow(java.util.Map<java.util.UUID, Long> seen, ServerPlayer player,
                                 long interval) {
        long now = System.currentTimeMillis();
        Long last = seen.get(player.getUUID());
        if (last != null && now - last < interval) {
            return false;
        }
        seen.put(player.getUUID(), now);
        return true;
    }

    private static boolean allowRightOfWay(ServerPlayer player) {
        return allow(LAST_ROW, player, ROW_MIN_INTERVAL_MS);
    }

    private static boolean allowSave(ServerPlayer player) {
        return allow(LAST_SAVE, player, SAVE_MIN_INTERVAL_MS);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AlignmentService.push(player);
        }
    }

    /** A dimension change means a different set of routes entirely. */
    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AlignmentService.push(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AlignmentService.forget(player);
            LAST_ROW.remove(player.getUUID());
            LAST_SAVE.remove(player.getUUID());
        }
    }
}
