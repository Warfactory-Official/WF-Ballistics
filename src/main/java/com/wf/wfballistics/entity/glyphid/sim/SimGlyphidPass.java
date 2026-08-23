package com.wf.wfballistics.entity.glyphid.sim;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidBrain;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidSnapshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the sim tier's per-record work on a worker, across the world thread's own tick.
 *
 * <pre>
 *   tick N, world thread, Pre:   1. fill the columns last tick's pass asked for and could not answer
 *                                2. re-resolve the flow field for every destination it marched to
 *                                3. hand the record list to a worker and return
 *   tick N, vanilla level tick:  entities tick, blocks tick, chunks tick  -- the world thread's real work
 *   tick N, worker:              plan + apply, every record        (this class's {@link #advance})
 *   tick N, world thread, Post:  4. join
 *                                5. decide who changes tier, which is the only step that touches an entity
 * </pre>
 *
 * <p><b>Why this is worth doing at 0.05 ms.</b> It is not, at three hundred records — and that is the point of
 * having measured it. The tier exists so a colony can have thousands of glyphids walking somewhere, and the
 * pass is linear in them: the same per-record cost that reads as nothing at 300 is 1.7 ms at ten thousand,
 * which is a third of a tick budget spent on glyphids nobody can see. Off the thread it is spent on a core
 * that was idle, in a window that the vanilla tick is going to occupy anyway.
 *
 * <p><b>The threading contract.</b>
 * <ul>
 *   <li>The worker cannot reach the world. It is handed a {@link SimWorldPrefetch}, which holds no
 *       {@code Level} — a compile-time guarantee, not a convention. The three world reads a record makes were
 *       all made on the world thread by {@link SimWorldPrefetch#refill}, before dispatch.</li>
 *   <li>Both directions are asserted at runtime by {@link WorldThread}: {@link #advance} refuses to run on the
 *       world thread when dispatched, and the prefetch refuses to read a chunk anywhere else.</li>
 *   <li>Nothing but the pass may touch a record while it is in flight, and that is enforced structurally
 *       rather than by memory: every other caller reaches the list through
 *       {@link SimGlyphidRegistry#view()}, which joins first. An explosion landing on a swarm mid-tick
 *       therefore waits for the pass instead of racing it — a sub-millisecond stall on a rare event, against
 *       a damage queue that would have needed its own ordering rules.</li>
 *   <li>The flow field is read but never written here. {@code GlyphidFlowFields.tick} floods it on the world
 *       thread, after the join.</li>
 *   <li>One worker per level, not many. The records do not interact during the pass — separation is a
 *       separate stage and its impulses are banked in fields — so splitting the list further would be safe,
 *       and it is still not worth it: the window is the whole vanilla level tick, tens of milliseconds wide,
 *       and a pass that fits in it does not need to be shorter. It would also break
 *       {@link SimWorldPrefetch}, whose miss list is unsynchronised precisely because one worker writes it.
 *       </li>
 * </ul>
 *
 * <p>Not the drone pool, deliberately. A drone route search is explicitly allowed to take as long as it needs
 * and land whenever it lands; a sim pass has to be finished by the end of the tick that started it. Queued
 * behind an A* across a few thousand cells it would still be running at the join, and the world thread would
 * wait out both.
 */
public final class SimGlyphidPass {

    private static final Map<ResourceKey<Level>, SimGlyphidPass> BY_LEVEL = new HashMap<>();
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    private static @Nullable ExecutorService pool;
    private static int poolSize;

    /**
     * The world, as far as this level's records may see it. Refilled on the world thread each tick and read
     * by the worker; see {@link SimWorldPrefetch} for why nothing here is synchronised.
     */
    private final SimWorldPrefetch world = new SimWorldPrefetch();

    /**
     * Wall time of the last pass, whichever thread ran it. Written by the worker, read on the world thread
     * after the join, so the {@code Future} is the happens-before edge.
     */
    private long passNanos;
    private volatile String ranOn = "-";
    private boolean ranOffThread;
    private int advanced;
    private long lastStallNanos;

    private SimGlyphidPass() {
    }

    /**
     * Start the worker pool. Sized like the drone pool: leave the world thread and a spare core alone, and
     * cap it, because the work is one task per dimension and a server does not have many dimensions with a
     * swarm marching across them.
     */
    public static void startup() {
        shutdown();
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        poolSize = workers;
        pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "wfb-glyphid-sim-" + THREAD_NUMBER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Stop the pool, once every level has joined whatever it had in flight. Joining first rather than
     * interrupting: a pass killed halfway through leaves a swarm with half its records moved, which would then
     * be saved.
     */
    public static void shutdown(Iterable<ServerLevel> levels) {
        for (ServerLevel level : levels) {
            SimGlyphidRegistry.get(level).await();
        }
        shutdown();
    }

    public static void shutdown() {
        ExecutorService running = pool;
        if (running != null) {
            running.shutdownNow();
            pool = null;
        }
        BY_LEVEL.clear();
        poolSize = 0;
    }

    public static int poolSize() {
        return poolSize;
    }

    /**
     * Do this level's world reads and set the pass going. Called from {@code LevelTickEvent.Pre}, with nothing
     * in flight.
     */
    static void begin(ServerLevel level, SimGlyphidRegistry registry) {
        SimGlyphidPass pass = BY_LEVEL.computeIfAbsent(level.dimension(), key -> new SimGlyphidPass());
        List<SimGlyphid> records = registry.view();
        if (records.isEmpty()) {
            pass.world.clear();
            pass.advanced = 0;
            return;
        }
        pass.world.refill(level);
        pass.advanced = records.size();

        ExecutorService workers = pool;
        if (workers == null || !SwarmBench.simAsync) {
            // The control arm, and the fallback for a level ticking before the server has finished starting.
            // Same view of the world and same point in the tick, so the only difference from the arm below is
            // which thread pays for it.
            pass.ranOffThread = false;
            pass.advance(records);
            return;
        }
        pass.ranOffThread = true;
        registry.dispatch(workers.submit(() -> {
            WorldThread.assertOff("the glyphid sim pass");
            pass.advance(records);
        }));
    }

    /**
     * Wait for the pass and book what it cost. Called from {@code LevelTickEvent.Post}, before anything else
     * looks at a record.
     */
    static void join(ServerLevel level, SimGlyphidRegistry registry) {
        registry.await();
        SimGlyphidPass pass = BY_LEVEL.get(level.dimension());
        if (pass == null || pass.advanced == 0) {
            return;
        }
        // Taken from the registry rather than measured here, because the join may already have happened:
        // anything that reaches a record mid-tick joins first, and that stall is just as much world-thread
        // time as this one.
        long stall = registry.takeStall();
        pass.lastStallNanos = pass.ranOffThread ? stall : pass.passNanos;
        SwarmProfiler.charge(SwarmProfiler.Phase.SIM_ASYNC, pass.passNanos);
        SwarmProfiler.charge(SwarmProfiler.Phase.SIM_WAIT, pass.lastStallNanos);
        registry.setDirty();
    }

    /**
     * Plan and move every record. The whole tier, and the reason it is worth having: one pass, no entity
     * list, no chunk, and the same {@link GlyphidBrain} the bodies run.
     */
    private void advance(List<SimGlyphid> records) {
        long start = System.nanoTime();
        for (int i = 0; i < records.size(); i++) {
            SimGlyphid sim = records.get(i);
            sim.tickCount++;
            GlyphidSnapshot self = sim.snapshot(world);
            sim.apply(world, GlyphidBrain.plan(self, sim.mind()));
        }
        passNanos = System.nanoTime() - start;
        ranOn = Thread.currentThread().getName();
    }

    /**
     * Forget a level's prefetched terrain, for a tier that has been switched off or a swarm that has gone.
     */
    static void idle(ServerLevel level) {
        SimGlyphidPass pass = BY_LEVEL.get(level.dimension());
        if (pass != null) {
            pass.world.clear();
            pass.advanced = 0;
        }
    }

    /**
     * @return one line per line of {@code swarmbench simthread}: where the pass ran, what it cost, and how
     * much of that the world thread waited out. The last of those is the number that says whether moving it
     * off the thread achieved anything — a stall the size of the pass means the work moved and the waiting
     * did not.
     */
    public static List<String> report(ServerLevel level) {
        SimGlyphidPass pass = BY_LEVEL.get(level.dimension());
        if (pass == null) {
            return List.of("No sim pass has run in this dimension.");
        }
        int[] terrain = pass.world.stats();
        return List.of(
                String.format(Locale.ROOT, "  %d records advanced on %s (%s, %d workers, assertions %s)",
                        pass.advanced, pass.ranOn,
                        SwarmBench.simAsync ? "async" : "async off", poolSize,
                        WorldThread.armed() ? "armed" : "NOT ARMED"),
                String.format(Locale.ROOT, "  last pass %.3f ms, world thread waited %.3f ms (%.0f%%)",
                        pass.passNanos / 1.0E6, pass.lastStallNanos / 1.0E6,
                        pass.passNanos == 0L ? 0.0 : 100.0 * pass.lastStallNanos / pass.passNanos),
                String.format(Locale.ROOT,
                        "  prefetch: %d columns held, %d wanted, %d destinations, %d in unloaded chunks",
                        terrain[0], terrain[1], terrain[2], terrain[3]));
    }
}
