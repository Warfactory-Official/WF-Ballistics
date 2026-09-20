package com.wf.wflib.recon;

import com.mojang.logging.LogUtils;
import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.drone.ai.DroneAiScheduler;
import com.wf.wflib.recon.detect.DetectionPass;
import com.wf.wflib.recon.detect.Plot;
import com.wf.wflib.recon.env.Atmosphere;
import com.wf.wflib.recon.env.ReconWeather;
import com.wf.wflib.recon.env.WeatherStation;
import com.wf.wflib.recon.event.BlastEvent;
import com.wf.wflib.recon.event.SeismicEventPass;
import com.wf.wflib.recon.event.SeismicEvents;
import com.wf.wflib.recon.event.SeismicFix;
import com.wf.wflib.recon.event.SonarEventPass;
import com.wf.wflib.recon.fuse.CrossFix;
import com.wf.wflib.recon.grid.ReconGrid;
import com.wf.wflib.recon.propagate.Propagator;
import com.wf.wflib.recon.propagate.Propagators;
import com.wf.wflib.recon.snapshot.ReconTerrain;
import com.wf.wflib.recon.snapshot.ReconTerrainCache;
import com.wf.wflib.recon.snapshot.SensorSnapshot;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import com.wf.wflib.recon.track.TrackId;
import com.wf.wflib.recon.track.TrackPicture;
import com.wf.wflib.recon.track.Tracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** One network: a set of sensors that share a picture, and the pass that keeps it up to date. */
public final class ReconNetwork {

    /** Ticks a sensor may go without its owner refreshing it before it is dropped. */
    public static final int SENSOR_TIMEOUT = 60;
    /** Blocks per side of the grid sensors are grouped into for collection. */
    private static final int GROUP_CELL = 256;
    /** Seismic fixes a network keeps in hand for its hubs to pull. */
    private static final int EVENT_BUFFER = 64;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final long netId;
    private final Map<Long, SensorHandle> sensors = new LinkedHashMap<>();
    /** The met probes on this net. */
    private final Map<Long, WeatherStation> stations = new LinkedHashMap<>();
    private final ReconGrid grid;
    private final Tracker tracker = new Tracker();
    private final Map<Long, Claim> claims = new HashMap<>();
    /**
     * Plots measured by something that is not a sensor on this grid: an orbital instrument, or a picture carried in
     * over a relay.
     */
    private final List<Plot> contributed = new ArrayList<>();

    private volatile TrackPicture picture = TrackPicture.EMPTY;
    private Future<PassResult> inFlight;
    private int inFlightEpoch;
    private int epoch;

    /**
     * The seismic events this net has fixed lately, newest last, keyed so a second read of the same blast refines
     * its entry instead of adding one.
     */
    private final Map<Long, SeismicFix> events = new LinkedHashMap<>();
    /** Bumped whenever {@link #events} changes. */
    private long eventStamp;

    private int lastTargetCount;
    private int lastSensorCount;
    /** Sensors that were due a sweep and had no route to a hub. The first thing to print when a grid goes dark. */
    private int lastOrphanCount;
    /** What the last pass collected, by class. */
    private final int[] lastKinds = new int[ContactClass.values().length];

    ReconNetwork(long netId) {
        this.netId = netId;
        this.grid = new ReconGrid(netId);
    }

    public long netId() {
        return netId;
    }

    /**
     * @return this network's physical topology, who can reach the hub, through whom, and at what cost.
     */
    public ReconGrid grid() {
        return grid;
    }

    /** The latest published belief. */
    public TrackPicture picture() {
        return picture;
    }

    public int sensorCount() {
        return sensors.size();
    }

    public int trackCount() {
        return picture.tracks().size();
    }

    public int lastTargetCount() {
        return lastTargetCount;
    }

    public int lastSensorCount() {
        return lastSensorCount;
    }

    public int lastOrphanCount() {
        return lastOrphanCount;
    }

    /**
     * @return what the last pass was handed, by class, for a diagnostic to print.
     */
    public String lastKindSummary() {
        StringBuilder out = new StringBuilder();
        ContactClass[] kinds = ContactClass.values();
        for (int i = 0; i < kinds.length; i++) {
            if (lastKinds[i] > 0) {
                out.append(out.isEmpty() ? "" : " ").append(kinds[i]).append('=').append(lastKinds[i]);
            }
        }
        return out.isEmpty() ? "none" : out.toString();
    }

    /**
     * @return every sensor on this network, for diagnostics.
     */
    public java.util.Collection<SensorHandle> sensorHandles() {
        return java.util.Collections.unmodifiableCollection(sensors.values());
    }

    SensorHandle register(BlockPos pos, SensorSpec spec, long gameTime) {
        SensorHandle existing = sensors.get(pos.asLong());
        if (existing != null) {
            existing.refresh(spec, gameTime);
            return existing;
        }
        SensorHandle handle = new SensorHandle(pos, spec, gameTime);
        sensors.put(handle.id(), handle);
        epoch++;
        return handle;
    }

    void unregister(BlockPos pos) {
        if (sensors.remove(pos.asLong()) != null) {
            epoch++;
        }
    }

    WeatherStation registerStation(BlockPos pos, double range, long gameTime) {
        WeatherStation existing = stations.get(pos.asLong());
        if (existing != null) {
            existing.refresh(range, gameTime);
            return existing;
        }
        WeatherStation station = new WeatherStation(pos, range, gameTime);
        stations.put(station.id(), station);
        return station;
    }

    void unregisterStation(BlockPos pos) {
        stations.remove(pos.asLong());
    }

    /** Hand this network a look somebody else took. */
    void contribute(List<Plot> plots) {
        if (!plots.isEmpty()) {
            contributed.addAll(plots);
        }
    }

    /**
     * @return how well this network is measuring the conditions at a position, 0 to 1. See
     *      {@link WeatherStation} for why several stations take the best rather than adding up.
     */
    public double coverageAt(double x, double y, double z) {
        double best = 0.0;
        for (WeatherStation station : stations.values()) {
            best = Math.max(best, station.coverageAt(x, y, z));
            if (best >= 1.0) {
                return 1.0;
            }
        }
        return best;
    }

    public int stationCount() {
        return stations.size();
    }

    /**
     * @return every met station on this network, for diagnostics.
     */
    public java.util.Collection<WeatherStation> stationHandles() {
        return java.util.Collections.unmodifiableCollection(stations.values());
    }

    boolean isQuiet() {
        return sensors.isEmpty() && stations.isEmpty() && grid.isQuiet() && inFlight == null
                && contributed.isEmpty();
    }

    /**
     * Claim a track for engagement, so two batteries do not spend two interceptors on one missile.
     *
     * @return true if the claim was taken, false if someone else already holds it.
     */
    public boolean claim(TrackId track, long claimant, long gameTime, int ticks) {
        Claim held = claims.get(track.value());
        if (held != null && held.expires > gameTime && held.claimant != claimant) {
            return false;
        }
        claims.put(track.value(), new Claim(claimant, gameTime + ticks));
        return true;
    }

    /**
     * @return true if somebody other than {@code claimant} currently holds this track.
     */
    public boolean claimedByOther(TrackId track, long claimant, long gameTime) {
        Claim held = claims.get(track.value());
        return held != null && held.expires > gameTime && held.claimant != claimant;
    }

    void tick(ServerLevel level) {
        WorldThread.assertOn("recon network tick");
        long gameTime = level.getGameTime();
        adopt();
        dropStaleSensors(gameTime);
        grid.tick(level, gameTime);
        claims.entrySet().removeIf(e -> e.getValue().expires <= gameTime);
        if ((sensors.isEmpty() && contributed.isEmpty()) || inFlight != null) {
            return;
        }
        ExecutorService pool = DroneAiScheduler.pool();
        if (pool == null) {
            return;
        }
        List<SensorJob> jobs = prepare(level, gameTime);
        if (jobs.isEmpty() && contributed.isEmpty()) {
            return;
        }
        List<Plot> external = contributed.isEmpty() ? List.of() : new ArrayList<>(contributed);
        contributed.clear();
        List<BlastEvent> blasts = SeismicEvents.live(level, gameTime);
        inFlightEpoch = epoch;
        inFlight = pool.submit(() -> run(jobs, external, blasts, gameTime));
    }

    /**
     * Take the result of a finished pass, if it is still about the network that asked for it.
     */
    private void adopt() {
        if (inFlight == null || !inFlight.isDone()) {
            return;
        }
        Future<PassResult> finished = inFlight;
        inFlight = null;
        if (inFlightEpoch != epoch) {
            return;
        }
        try {
            PassResult result = finished.get();
            picture = result.picture();
            fileEvents(result.events());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error("[wflib] recon pass failed for net {}", Long.toHexString(netId), e);
        }
    }

    private void dropStaleSensors(long gameTime) {
        for (Iterator<Map.Entry<Long, SensorHandle>> it = sensors.entrySet().iterator(); it.hasNext(); ) {
            if (gameTime - it.next().getValue().lastRefresh() > SENSOR_TIMEOUT) {
                it.remove();
                epoch++;
            }
        }
        stations.entrySet().removeIf(e -> gameTime - e.getValue().lastRefresh() > SENSOR_TIMEOUT);
    }

    /** Snapshot the sensors that are due a look, collect what is around them, and hand each its terrain. */
    private List<SensorJob> prepare(ServerLevel level, long gameTime) {
        Map<Long, List<SensorHandle>> groups = new LinkedHashMap<>();
        int orphans = 0;
        for (SensorHandle handle : sensors.values()) {
            if (!handle.sweepDue(gameTime)) {
                continue;
            }
            Propagator propagator = Propagators.of(handle.spec().band());
            if (propagator == null) {
                continue;
            }
            if (grid.hopsForSensor(handle.pos()) < 0) {
                orphans++;
                continue;
            }
            BlockPos pos = handle.pos();
            long key = (((long) Math.floorDiv(pos.getX(), GROUP_CELL)) << 32)
                    ^ (Math.floorDiv(pos.getZ(), GROUP_CELL) & 0xFFFF_FFFFL);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(handle);
        }
        lastOrphanCount = orphans;
        if (groups.isEmpty()) {
            return List.of();
        }

        List<SensorJob> jobs = new ArrayList<>();
        java.util.Arrays.fill(lastKinds, 0);
        Atmosphere weather = ReconWeather.sample(level);
        int targets = 0;
        int swept = 0;
        for (List<SensorHandle> group : groups.values()) {
            AABB volume = null;
            for (int i = 0; i < group.size(); i++) {
                SensorHandle handle = group.get(i);
                AABB box = new AABB(handle.pos()).inflate(handle.spec().baseRange());
                volume = volume == null ? box : volume.minmax(box);
            }
            List<TargetSnapshot> collected = ReconTargets.collect(level, volume);
            targets += collected.size();
            for (int i = 0; i < collected.size(); i++) {
                lastKinds[collected.get(i).kind().ordinal()]++;
            }
            for (int i = 0; i < group.size(); i++) {
                SensorHandle handle = group.get(i);
                refreshTerrain(level, handle, gameTime);
                handle.swept(gameTime);
                swept++;
                BlockPos at = handle.pos();
                Atmosphere atmos = weather.withCoverage(
                        coverageAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5));
                jobs.add(new SensorJob(handle.snapshot(gameTime, grid.hopsForSensor(at), atmos),
                        handle.terrain(), collected, Propagators.of(handle.spec().band())));
            }
        }
        lastTargetCount = targets;
        lastSensorCount = swept;
        return jobs;
    }

    /** Rebuild a sensor's terrain if it has aged out and the level can still afford a build this tick. */
    private static void refreshTerrain(ServerLevel level, SensorHandle handle, long gameTime) {
        if (!handle.terrainStale(gameTime)) {
            return;
        }
        ReconTerrain built = ReconTerrainCache.get(level).build(level,
                handle.pos().getX() + 0.5, handle.pos().getZ() + 0.5, handle.spec().baseRange());
        if (built != null) {
            handle.adoptTerrain(built, gameTime);
        }
    }

    /** The whole off-thread half: propagation, line of sight, merging, association, filtering, publication. */
    private PassResult run(List<SensorJob> jobs, List<Plot> external, List<BlastEvent> blasts, long gameTime) {
        List<Plot> plots = new ArrayList<>(external);
        List<SeismicEventPass.Ear> ears = blasts.isEmpty() ? List.of() : new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            SensorJob job = jobs.get(i);
            plots.addAll(DetectionPass.run(job.sensor(), job.targets(), job.terrain(), job.propagator(), gameTime));
            Band band = job.sensor().spec().band();
            if (!blasts.isEmpty() && (band == Band.SEISMIC || band == Band.SONAR)) {
                ears.add(new SeismicEventPass.Ear(job.sensor(), job.terrain()));
            }
        }
        tracker.update(CrossFix.fuse(plots), gameTime);
        // Both passes read the same ears and each filters to its own band: a blast in the water reaches the
        // hydrophones and one in rock reaches the geophones, and a charge on a beach reaches both.
        List<SeismicFix> heard = SeismicEventPass.run(ears, blasts, gameTime);
        List<SeismicFix> pinged = SonarEventPass.run(ears, blasts, gameTime);
        if (!pinged.isEmpty()) {
            List<SeismicFix> both = new ArrayList<>(heard);
            both.addAll(pinged);
            heard = both;
        }
        return new PassResult(tracker.publish(gameTime), heard);
    }

    /** File what the last pass heard. */
    private void fileEvents(List<SeismicFix> fixed) {
        if (fixed.isEmpty()) {
            return;
        }
        for (int i = 0; i < fixed.size(); i++) {
            SeismicFix fix = fixed.get(i);
            events.put(fix.eventId(), fix);
        }
        Iterator<Map.Entry<Long, SeismicFix>> it = events.entrySet().iterator();
        while (events.size() > EVENT_BUFFER && it.hasNext()) {
            it.next();
            it.remove();
        }
        eventStamp++;
    }

    /**
     * @return every seismic event this network has fixed lately, oldest first. See {@link #eventStamp()} for
     *      how to avoid walking it when nothing has changed.
     */
    public java.util.Collection<SeismicFix> events() {
        return java.util.Collections.unmodifiableCollection(events.values());
    }

    /**
     * @return a counter that changes whenever {@link #events()} does, and never otherwise.
     */
    public long eventStamp() {
        return eventStamp;
    }

    /**
     * One sensor's look, as everything the worker needs and nothing else.
     */
    private record SensorJob(SensorSnapshot sensor, ReconTerrain terrain,
                             List<TargetSnapshot> targets, Propagator propagator) {
    }

    /** Both halves of a pass. */
    private record PassResult(TrackPicture picture, List<SeismicFix> events) {
    }

    private record Claim(long claimant, long expires) {
    }
}
