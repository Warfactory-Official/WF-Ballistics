package com.wf.wflib.recon;

import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.recon.env.ReconWeather;
import com.wf.wflib.recon.env.WeatherStation;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.ReconGrid;
import com.wf.wflib.recon.snapshot.ReconTerrainCache;
import com.wf.wflib.recon.track.TrackPicture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The entry point. */
public final class ReconNet {

    /** The network everything unaffiliated shares. */
    public static final long UNAFFILIATED = 0L;

    private static final Map<ResourceKey<Level>, Map<Long, ReconNetwork>> BY_LEVEL = new HashMap<>();

    private ReconNet() {
    }

    /**
     * @return the network id for a team, or {@link #UNAFFILIATED} for none.
     */
    public static long netId(@Nullable UUID team) {
        return team == null ? UNAFFILIATED : SourceIds.of(team);
    }

    /** Add or refresh a sensor. */
    public static SensorHandle registerSensor(ServerLevel level, BlockPos pos, SensorSpec spec) {
        WorldThread.assertOn("recon sensor registration");
        return network(level, spec.netId()).register(pos, spec, level.getGameTime());
    }

    /**
     * Add or refresh a node on this network's grid: a hub, a probe, or anything else that carries a link.
     *
     * @param root true for a hub: a place data is trying to reach, and the root of the BFS.
     * @param linkRange blocks this node can span in one hop, subject to clearance.
     * @param mast blocks above the block position that links are measured from, so a mast buys reach the
     *      same way it buys sight.
     */
    public static GridNode registerNode(ServerLevel level, BlockPos pos, long netId, double linkRange,
                                        double mast, boolean root, String label) {
        WorldThread.assertOn("recon node registration");
        return network(level, netId).grid()
                .register(pos, linkRange, mast, root, label, level.getGameTime());
    }

    public static void unregisterNode(ServerLevel level, BlockPos pos, long netId) {
        ReconNetwork net = existing(level, netId);
        if (net != null) {
            net.grid().unregister(pos);
        }
    }

    /**
     * Add or refresh a met station: something measuring the conditions on behalf of this network.
     *
     * @param range blocks over which this station's sample is taken as describing the conditions. Full marks
     *      for the inner half and fading to nothing at the edge; see {@code WeatherStation}.
     */
    public static WeatherStation registerStation(ServerLevel level, BlockPos pos, long netId, double range) {
        WorldThread.assertOn("recon station registration");
        return network(level, netId).registerStation(pos, range, level.getGameTime());
    }

    public static void unregisterStation(ServerLevel level, BlockPos pos, long netId) {
        ReconNetwork net = existing(level, netId);
        if (net != null) {
            net.unregisterStation(pos);
        }
    }

    /**
     * Hand a network a look that was taken by something other than one of its own sensors: an orbital instrument
     * over a footprint, or a picture carried in over a relay.
     */
    public static void contributePlots(ServerLevel level, long netId,
                                       List<com.wf.wflib.recon.detect.Plot> plots) {
        WorldThread.assertOn("recon plot contribution");
        if (plots.isEmpty()) {
            return;
        }
        network(level, netId).contribute(plots);
    }

    /**
     * @return this network's topology, or null if the network does not exist.
     */
    @Nullable
    public static ReconGrid grid(ServerLevel level, long netId) {
        ReconNetwork net = existing(level, netId);
        return net == null ? null : net.grid();
    }

    public static void unregisterSensor(ServerLevel level, BlockPos pos, long netId) {
        Map<Long, ReconNetwork> nets = BY_LEVEL.get(level.dimension());
        if (nets == null) {
            return;
        }
        ReconNetwork net = nets.get(netId);
        if (net != null) {
            net.unregister(pos);
        }
    }

    /**
     * @return this network's latest belief, or an empty picture if it has none yet.
     */
    public static TrackPicture picture(ServerLevel level, long netId) {
        ReconNetwork net = existing(level, netId);
        return net == null ? TrackPicture.EMPTY : net.picture();
    }

    /**
     * @return the seismic events this network has fixed lately, oldest first, or nothing if the network does
     *      not exist. What a hub files into its {@code SeismicLog}, and the seam an implementer's own machine uses
     *      to do the same without naming anything inside the pass.
     */
    public static java.util.Collection<com.wf.wflib.recon.event.SeismicFix> events(
            ServerLevel level, long netId) {
        ReconNetwork net = existing(level, netId);
        return net == null ? List.of() : net.events();
    }

    /**
     * @return a counter that changes only when {@link #events} does, so a block entity can poll every tick
     *      without walking anything. Zero for a network that does not exist, which is also its starting value:
     *      correct either way, since neither has anything to hand out.
     */
    public static long eventStamp(ServerLevel level, long netId) {
        ReconNetwork net = existing(level, netId);
        return net == null ? 0L : net.eventStamp();
    }

    /**
     * @return the network itself, for the few consumers that need more than the picture (engagement claims,
     *      mostly), or null if there is none.
     */
    @Nullable
    public static ReconNetwork existing(ServerLevel level, long netId) {
        Map<Long, ReconNetwork> nets = BY_LEVEL.get(level.dimension());
        return nets == null ? null : nets.get(netId);
    }

    /** Run every network in this dimension. */
    public static void tick(ServerLevel level) {
        Map<Long, ReconNetwork> nets = BY_LEVEL.get(level.dimension());
        if (nets == null || nets.isEmpty()) {
            return;
        }
        ReconTerrainCache.beginTick(level);
        for (Iterator<Map.Entry<Long, ReconNetwork>> it = nets.entrySet().iterator(); it.hasNext(); ) {
            ReconNetwork net = it.next().getValue();
            net.tick(level);
            if (net.isQuiet()) {
                it.remove();
            }
        }
    }

    /**
     * @return every network in a dimension, for diagnostics.
     */
    public static List<ReconNetwork> networks(ServerLevel level) {
        Map<Long, ReconNetwork> nets = BY_LEVEL.get(level.dimension());
        return nets == null ? List.of() : new ArrayList<>(nets.values());
    }

    public static void shutdown() {
        BY_LEVEL.clear();
        ReconTerrainCache.clear();
        ReconWeather.clear();
        com.wf.wflib.recon.event.SeismicEvents.shutdown();
    }

    private static ReconNetwork network(ServerLevel level, long netId) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(netId, ReconNetwork::new);
    }
}
