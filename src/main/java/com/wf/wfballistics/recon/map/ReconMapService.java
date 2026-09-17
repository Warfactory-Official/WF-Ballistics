package com.wf.wfballistics.recon.map;

import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.network.ReconMapPacket;
import com.wf.wfballistics.network.WFNetwork;
import com.wf.wfballistics.recon.ReconNet;
import com.wf.wfballistics.recon.ReconNetwork;
import com.wf.wfballistics.recon.ReconOwners;
import com.wf.wfballistics.recon.SensorHandle;
import com.wf.wfballistics.recon.SensorSpec;
import com.wf.wfballistics.recon.env.WeatherStation;
import com.wf.wfballistics.recon.grid.GridNode;
import com.wf.wfballistics.recon.grid.HubIndex;
import com.wf.wfballistics.recon.grid.ReconGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Who is watching which network's coverage, and what to send them. */
public final class ReconMapService {

    /** Ticks between rebuilds. */
    public static final int PUSH_INTERVAL = 20;

    private static final Map<UUID, Watch> WATCHERS = new HashMap<>();

    private ReconMapService() {
    }

    /**
     * @return whether this player may name a network rather than being given one.
     */
    public static boolean mayChooseAnyNet(ServerPlayer player) {
        MinecraftServer server = player.server;
        return player.hasPermissions(2)
                || (server.isSingleplayer() && server.isSingleplayerOwner(player.getGameProfile()));
    }

    /**
     * @return the net this player is entitled to without asking: their faction's, folded the same way every
     *      block on it folds the same UUID. {@link ReconNet#UNAFFILIATED} with no faction, which is a real network
     *      (the one everything unclaimed shares), rather than a refusal.
     */
    public static long autoNetFor(ServerPlayer player) {
        return ReconOwners.netOf(player);
    }

    /**
     * Start (or move) a subscription that tracks the player's own faction.
     *
     * @return the net they landed on.
     */
    public static long watchAuto(ServerPlayer player) {
        Watch watch = new Watch(true, autoNetFor(player));
        WATCHERS.put(player.getUUID(), watch);
        return push(player, watch, true);
    }

    /**
     * Start (or move) a subscription pinned to one net, whatever the player's affiliation.
     *
     * @return the net, unchanged: returned so the two entry points read the same at the call site.
     */
    public static long watch(ServerPlayer player, long netId) {
        Watch watch = new Watch(false, netId);
        WATCHERS.put(player.getUUID(), watch);
        return push(player, watch, true);
    }

    /** Stop watching, and tell the client to drop what it is drawing. */
    public static void stop(ServerPlayer player) {
        if (WATCHERS.remove(player.getUUID()) != null) {
            WFNetwork.sendToPlayer(player, new ReconMapPacket(player.level().dimension(), ReconMapView.NONE));
        }
    }

    @Nullable
    public static Watch watchOf(ServerPlayer player) {
        return WATCHERS.get(player.getUUID());
    }

    /** Drop a departed player's subscription. */
    public static void forget(UUID playerId) {
        WATCHERS.remove(playerId);
    }

    public static void shutdown() {
        WATCHERS.clear();
    }

    /** Refresh every watcher standing in this level. */
    public static void tick(ServerLevel level) {
        if (WATCHERS.isEmpty() || level.getGameTime() % PUSH_INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            Watch watch = WATCHERS.get(player.getUUID());
            if (watch != null) {
                push(player, watch, false);
            }
        }
    }

    /**
     * Build this watcher's view and send it if it differs from what they already have.
     *
     * @return the net actually pushed, which for an automatic watch is re-derived here and so can differ
     *      from the one the subscription started on.
     */
    private static long push(ServerPlayer player, Watch watch, boolean force) {
        ServerLevel level = player.serverLevel();
        long netId = watch.auto ? autoNetFor(player) : watch.netId;
        ReconMapView view = build(level, netId);
        ResourceKey<Level> dim = level.dimension();
        if (!force && netId == watch.netId && dim.equals(watch.dimension) && view.equals(watch.sent)) {
            return netId;
        }
        watch.netId = netId;
        watch.dimension = dim;
        watch.sent = view;
        WFNetwork.sendToPlayer(player, new ReconMapPacket(dim, view));
        return netId;
    }

    /** One network in one dimension, as {@link ReconMapView}. */
    public static ReconMapView build(ServerLevel level, long netId) {
        ReconNetwork network = ReconNet.existing(level, netId);
        if (network == null) {
            return ReconMapView.empty(netId);
        }
        ReconGrid grid = network.grid();

        List<ReconMapView.Node> nodes = new ArrayList<>(grid.nodeCount());
        for (GridNode node : grid.nodes()) {
            nodes.add(new ReconMapView.Node(node.pos(), node.label(), node.root(), node.hops(),
                    node.downstream(), node.uplink(), node.linkRange()));
        }
        nodes.sort(Comparator.comparingLong(node -> node.pos().asLong()));
        if (nodes.size() > ReconMapView.MAX_NODES) {
            nodes = nodes.subList(0, ReconMapView.MAX_NODES);
        }

        List<ReconMapView.Footprint> coverage = new ArrayList<>();
        for (SensorHandle handle : network.sensorHandles()) {
            SensorSpec spec = handle.spec();
            coverage.add(new ReconMapView.Footprint(handle.pos(), spec.band(), spec.baseRange(),
                    grid.hopsForSensor(handle.pos())));
        }
        for (WeatherStation station : network.stationHandles()) {
            coverage.add(new ReconMapView.Footprint(station.pos(), null, station.range(),
                    grid.hopsForSensor(station.pos())));
        }
        coverage.sort(Comparator.comparingLong(shape -> shape.pos().asLong()));
        if (coverage.size() > ReconMapView.MAX_FOOTPRINTS) {
            coverage = coverage.subList(0, ReconMapView.MAX_FOOTPRINTS);
        }

        return new ReconMapView(netId, List.copyOf(nodes), List.copyOf(coverage));
    }

    /** One player's subscription. */
    public static final class Watch {

        private final boolean auto;
        private long netId;
        @Nullable
        private ResourceKey<Level> dimension;
        private ReconMapView sent = ReconMapView.NONE;

        private Watch(boolean auto, long netId) {
            this.auto = auto;
            this.netId = netId;
        }

        /** True if this follows the player's faction rather than a net they named. */
        public boolean auto() {
            return auto;
        }

        public long netId() {
            return netId;
        }

        /** What this player was last sent, for a readout that wants to count without rebuilding. */
        public ReconMapView view() {
            return sent;
        }
    }

    /**
     * @return a rough count of what the player is looking at, for command feedback. Takes the view rather
     *      than rebuilding one, so a caller that also wants to say something about an empty net does not walk the
     *      grid twice to find out it is empty.
     */
    public static String describe(ReconMapView view) {
        if (view.isEmpty()) {
            return "nothing on it in this dimension";
        }
        int online = 0;
        for (ReconMapView.Node node : view.nodes()) {
            if (node.online()) {
                online++;
            }
        }
        return String.format(Locale.ROOT, "%d/%d node(s) online, %d sensor footprint(s)",
                online, view.nodes().size(), view.coverage().size());
    }

    /**
     * @return the net of the nearest hub to {@code pos}, or null if there is none within {@code within}.
     *      Hubs only, and by design: a hub is the one block that owns a network outright, so "the net here" has an
     *      unambiguous answer only in terms of one.
     */
    @Nullable
    public static Long netNear(ServerLevel level, BlockPos pos, double within) {
        long fallback = Long.MIN_VALUE;
        long found = HubIndex.netFor(level, pos, within, fallback);
        return found == fallback ? null : found;
    }
}
