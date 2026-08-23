package com.wf.wfballistics.drone.ai;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.drone.ai.coord.SquadAnchor;
import com.wf.wfballistics.drone.nav.DronePath;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.nav.PlannerStats;
import com.wf.wfballistics.drone.nav.TerrainCache;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Drives the drone AI for one dimension, one tick at a time.
 *
 * <pre>
 *   tick N, world thread:  1. collect finished steering jobs from tick N-1
 *                          2. adopt any route searches that have finished
 *                          3. apply the plans (the only step that touches the world)
 *                          4. snapshot every drone, group into squads
 *                          5. dispatch one steering job per squad, plus a route search for any drone due one
 *   tick N, workers:       DroneBrain.planSquad(...) -> plans    (microseconds, must land every tick)
 *                          DroneBrain.planRoute(...) -> a route  (milliseconds, lands whenever it lands)
 * </pre>
 *
 * <p>The threading contract that makes this safe:
 * <ul>
 *   <li>Workers only ever see {@link DroneSnapshot}/{@link SquadView}, which hold no world references at
 *       all. That is a compile-time guarantee, not a convention. Terrain reaches them the same way, as an
 *       immutable {@code TerrainField} condensed from chunks on this thread.</li>
 *   <li>Both boundary crossings are asserted at runtime by {@link WorldThread}: the searches refuse to run
 *       on the server thread, and the chunk reads refuse to run anywhere else. {@code DroneSelfTest} checks
 *       those assertions are armed, and {@code /wfballistics drone threads} reports which thread the
 *       searches have actually been running on.</li>
 *   <li>Every field of this class is read and written on the world thread only. The single thing that
 *       crosses threads is the {@link Future}, and it is polled, never completed into shared state, so
 *       there is nothing here to race on.</li>
 *   <li>A plan is applied at most {@link #MAX_PLAN_AGE} ticks after the snapshot it came from. Anything
 *       staler is dropped and the drone coasts, so a slow planner degrades into a hover instead of
 *       teleporting drones around on old data.</li>
 *   <li>One job per squad, so coordination needs no locks.</li>
 * </ul>
 */
public final class DroneAiScheduler {

    /**
     * Ticks a plan stays usable. Normally plans land the tick after they were requested; this is the
     * tolerance for a worker that got starved.
     */
    public static final int MAX_PLAN_AGE = 4;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceKey<Level>, DroneAiScheduler> BY_LEVEL = new HashMap<>();
    private static final List<DroneCarrierSource> SOURCES = new ArrayList<>();

    private static ExecutorService pool;
    private static int poolSize;

    /**
     * Plans waiting to be applied, keyed by drone. Keying by id (not by carrier) means a drone that
     * offloaded to the sim between request and apply still gets its plan.
     */
    private final Map<UUID, DronePlan> ready = new HashMap<>();
    /**
     * In-flight squad jobs, keyed by the leader's id. A squad is never re-dispatched while planning.
     */
    private final Map<UUID, Future<SquadPlan>> inFlight = new HashMap<>();
    /**
     * Each squad's reference frame as of the last plan that landed, keyed by squad.
     *
     * <p>The only piece of drone AI state that lives between ticks anywhere but on a drone, and it is here
     * rather than off-thread on purpose: a {@code CoordinationModel} that computes its reference has to
     * remember it, and the threading contract says a worker may not remember anything. So the frame is handed
     * out with the snapshot and handed back with the plan, and the models stay pure functions.
     *
     * <p>A squad that misses a tick simply gets its frame back one tick stale, which every model tolerates,
     * and one that has been gone long enough to be forgotten entirely seeds a fresh frame, which is the same
     * thing that happens on launch.
     */
    private final Map<Long, SquadAnchor> anchors = new HashMap<>();
    /**
     * In-flight route searches, keyed by drone.
     *
     * <p>Kept separate from the steering jobs above, and that separation is the whole point. Steering must
     * land every tick or the drone coasts; an A* across a few thousand cells takes far longer than a tick. In
     * one job the search would stall the squad's steering and the flight would stutter each time it replanned.
     * Split, a search takes as long as it needs and its route is adopted whenever it arrives: a tick or ten
     * later, on a route that was planned hundreds of blocks ahead, makes no difference at all.
     */
    private final Map<UUID, Future<DronePath>> pathJobs = new HashMap<>();

    private DroneAiScheduler() {
    }

    /**
     * Register a place drones live. The entity and sim packages each add one, so this class never has to
     * know about either.
     */
    public static void addSource(DroneCarrierSource source) {
        SOURCES.add(source);
    }

    /**
     * Start the shared worker pool. Sized like the parallel nuke pool: leave the server thread and a spare
     * core alone.
     */
    public static void startup() {
        shutdown();
        WorldThread.mark();
        int workers = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        poolSize = workers;
        pool = Executors.newWorkStealingPool(workers);
        PlannerStats.reset();
        LOGGER.debug("[wfballistics] drone AI pool started with {} workers", workers);
    }

    /**
     * @return how many worker threads the planner pool was given.
     */
    public static int poolSize() {
        return poolSize;
    }

    public static void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        BY_LEVEL.clear();
        poolSize = 0;
        TerrainCache.clear();
        WorldThread.clear();
    }

    public static void tick(ServerLevel level) {
        TerrainCache.beginTick(level);
        List<DroneCarrier> carriers = new ArrayList<>();
        for (DroneCarrierSource source : SOURCES) {
            source.collect(level, carriers);
        }
        DroneAiScheduler scheduler = BY_LEVEL.get(level.dimension());
        if (carriers.isEmpty()) {
            if (scheduler != null && scheduler.isQuiet()) {
                BY_LEVEL.remove(level.dimension());
            }
            return;
        }
        if (scheduler == null) {
            scheduler = new DroneAiScheduler();
            BY_LEVEL.put(level.dimension(), scheduler);
        }
        scheduler.run(level, carriers);
    }

    /**
     * @return true if there is no work outstanding, so this dimension's scheduler can be dropped.
     *
     * <p>Deliberately does not consider {@link #anchors}. They are only reachable through a live squad, and
     * this is only asked when there are no drones at all, so a frame still sitting in the map is a frame for
     * a squad that no longer exists, and dropping the scheduler is exactly what should happen to it.
     */
    private boolean isQuiet() {
        return ready.isEmpty() && inFlight.isEmpty() && pathJobs.isEmpty();
    }

    private void run(ServerLevel level, List<DroneCarrier> carriers) {
        long now = level.getGameTime();
        collectFinished();
        collectRoutes(carriers);
        applyPlans(level, carriers, now);
        dispatch(level, carriers, now);
    }

    /**
     * Hand out routes from searches that have finished, whenever they finish.
     */
    private void collectRoutes(List<DroneCarrier> carriers) {
        if (pathJobs.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<UUID, Future<DronePath>>> it = pathJobs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Future<DronePath>> entry = it.next();
            if (!entry.getValue().isDone()) {
                continue;
            }
            it.remove();
            try {
                DronePath path = entry.getValue().get();
                if (path == null) {
                    continue;
                }
                for (DroneCarrier carrier : carriers) {
                    if (carrier.droneId().equals(entry.getKey()) && carrier.carrierAlive()) {
                        carrier.adoptPath(path);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.error("[wfballistics] drone route search failed for {}", entry.getKey(), e);
            }
        }
    }

    /**
     * Drain jobs that finished since last tick. Polling rather than completing into shared state keeps all
     * of this class's mutation on the world thread.
     */
    private void collectFinished() {
        for (Iterator<Map.Entry<UUID, Future<SquadPlan>>> it = inFlight.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Future<SquadPlan>> entry = it.next();
            if (!entry.getValue().isDone()) {
                continue;
            }
            it.remove();
            try {
                SquadPlan squadPlan = entry.getValue().get();
                for (DronePlan plan : squadPlan.plans()) {
                    ready.put(plan.droneId(), plan);
                }
                if (squadPlan.squadId() != 0L && squadPlan.anchor() != null) {
                    anchors.put(squadPlan.squadId(), squadPlan.anchor());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.error("[wfballistics] drone squad planning failed for leader {}", entry.getKey(), e);
            }
        }
    }

    private void applyPlans(ServerLevel level, List<DroneCarrier> carriers, long now) {
        for (DroneCarrier carrier : carriers) {
            if (!carrier.carrierAlive()) {
                ready.remove(carrier.droneId());
                continue;
            }
            DronePlan plan = ready.remove(carrier.droneId());
            if (plan != null && now - plan.sourceGameTime() <= MAX_PLAN_AGE) {
                carrier.apply(level, plan);
            } else {
                carrier.coast(level);
            }
        }
        if (ready.size() > carriers.size()) {
            ready.keySet().removeIf(id -> carriers.stream().noneMatch(c -> c.droneId().equals(id)));
        }
    }

    private void dispatch(ServerLevel level, List<DroneCarrier> carriers, long now) {
        if (pool == null) {
            return;
        }
        for (SquadView squad : buildSquads(level, carriers, now)) {
            for (DroneSnapshot member : squad.slots()) {
                if (member.nav().canPlan() && !pathJobs.containsKey(member.id())) {
                    pathJobs.put(member.id(), pool.submit(() -> DroneBrain.planRoute(member)));
                }
            }
            UUID key = squad.leader().id();
            if (inFlight.containsKey(key)) {
                continue;
            }
            inFlight.put(key, pool.submit(() -> DroneBrain.planSquad(squad)));
        }
    }

    /**
     * @return this dimension's squads as the planner would see them this tick, for a diagnostic to read.
     *
     * <p>Built through the same {@link #buildSquads} the planner uses, frames included, because a report
     * assembled any other way is a report about a different squad. World thread only, like everything else
     * here: taking a snapshot reads the level.
     */
    public static List<SquadView> squadsFor(ServerLevel level) {
        WorldThread.assertOn("squad diagnostics");
        List<DroneCarrier> carriers = new ArrayList<>();
        for (DroneCarrierSource source : SOURCES) {
            source.collect(level, carriers);
        }
        DroneAiScheduler scheduler = BY_LEVEL.get(level.dimension());
        if (scheduler == null || carriers.isEmpty()) {
            return List.of();
        }
        return scheduler.buildSquads(level, carriers, level.getGameTime());
    }

    /**
     * Group live carriers into squads and snapshot them. Solo drones become a squad of one, so the planner
     * has exactly one shape to handle.
     */
    private List<SquadView> buildSquads(ServerLevel level, List<DroneCarrier> carriers, long now) {
        Map<Long, List<DroneCarrier>> bySquad = new LinkedHashMap<>();
        List<DroneCarrier> solo = new ArrayList<>();
        for (DroneCarrier carrier : carriers) {
            if (!carrier.carrierAlive()) {
                continue;
            }
            if (carrier.squadId() == 0L) {
                solo.add(carrier);
            } else {
                bySquad.computeIfAbsent(carrier.squadId(), k -> new ArrayList<>()).add(carrier);
            }
        }

        List<SquadView> out = new ArrayList<>(bySquad.size() + solo.size());
        for (DroneCarrier carrier : solo) {
            DroneSnapshot snapshot = carrier.snapshot(level, now);
            out.add(SquadView.solo(snapshot));
        }
        for (Map.Entry<Long, List<DroneCarrier>> entry : bySquad.entrySet()) {
            List<DroneCarrier> members = entry.getValue();
            promoteIfLeaderless(level.getRandom(), members);
            members.sort(Comparator.comparing((DroneCarrier c) -> !c.leader())
                    .thenComparing(c -> c.droneId().toString()));
            List<DroneSnapshot> snapshots = new ArrayList<>(members.size());
            for (DroneCarrier member : members) {
                snapshots.add(member.snapshot(level, now));
            }
            out.add(new SquadView(entry.getKey(), members.get(0).formationId(),
                    Formation.clampSpacing(members.get(0).formationSpacing()),
                    members.get(0).coordinationId(), anchors.get(entry.getKey()), snapshots.get(0),
                    List.copyOf(snapshots)));
        }
        anchors.keySet().retainAll(bySquad.keySet());
        return out;
    }

    /**
     * Hand a leaderless squad to a survivor picked at random, mirroring {@code SwarmManager.promoteSuccessor}
     * on the missile side: losing the drone that was leading is not supposed to end the mission for everyone
     * behind it.
     *
     * <p>Random rather than "the next one in line" on purpose. The sort below would otherwise hand every
     * decapitated squad to its lowest-id member, which makes the survivor predictable from the outside:
     * shoot the leader, and you already know which drone becomes the one worth shooting next. Drawing the
     * successor makes the flight cost of taking out a leader the same whichever one you take out.
     *
     * <p>Written back to the carrier rather than recomputed, so the choice is made once. Re-drawing it every
     * tick would rebuild the formation around a different drone each time and the squad would never settle.
     */
    public static void promoteIfLeaderless(RandomSource random, List<DroneCarrier> members) {
        if (members.isEmpty()) {
            return;
        }
        for (DroneCarrier member : members) {
            if (member.leader()) {
                return;
            }
        }
        members.get(random.nextInt(members.size())).promote();
    }

    /**
     * Where {@link DroneAiScheduler} finds drones. Implementations are called on the world thread.
     */
    public interface DroneCarrierSource {
        void collect(ServerLevel level, List<DroneCarrier> out);
    }
}
