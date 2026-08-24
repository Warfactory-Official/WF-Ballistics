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
 * <p>Not worth it at three hundred records (0.05 ms), and that is why it was measured: the pass is linear, so
 * the same per-record cost is 1.7 ms at ten thousand — a third of a tick spent on glyphids nobody can see.
 *
 * <p>The threading contract:
 * <ul>
 *   <li>The worker cannot reach the world. It holds a {@link SimWorldPrefetch}, which has no {@code Level},
 *       and every world read was made on the world thread by {@link SimWorldPrefetch#refill} before
 *       dispatch. Both directions are asserted at runtime by {@link WorldThread}.</li>
 *   <li>Nothing else may touch a record in flight, enforced structurally: every other caller goes through
 *       {@link SimGlyphidRegistry#view()}, which joins first.</li>
 *   <li>The flow field is read but never written here; {@code GlyphidFlowFields.tick} floods it after the
 *       join.</li>
 *   <li>One worker per level. The window is the whole vanilla level tick, so a pass that fits in it does not
 *       need splitting — and {@link SimWorldPrefetch}'s miss list is unsynchronised because one worker
 *       writes it.</li>
 * </ul>
 *
 * <p>Not the drone pool: a route search may land whenever it lands, but a sim pass has to be done by the end
 * of the tick that started it, and queueing behind an A* would make the world thread wait out both.
 */
public final class SimGlyphidPass {

    private static final Map<ResourceKey<Level>, SimGlyphidPass> BY_LEVEL = new HashMap<>();
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    private static @Nullable ExecutorService pool;
    private static int poolSize;

    /** The world as this level's records may see it. Refilled on the world thread, read by the worker. */
    private final SimWorldPrefetch world = new SimWorldPrefetch();

    /** Wall time of the last pass. Written by the worker; the {@code Future} is the happens-before edge. */
    private long passNanos;
    private volatile String ranOn = "-";
    private boolean ranOffThread;
    private int advanced;
    private long lastStallNanos;

    private SimGlyphidPass() {
    }

    /**
     * Start the worker pool. Sized like the drone pool, and capped: the work is one task per dimension.
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
     * Stop the pool, once every level has joined what it had in flight. Interrupting instead would save a
     * swarm with half its records moved.
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
            // The control arm, and the fallback for a level ticking during startup. Same view of the world
            // and same point in the tick; only the thread differs.
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
        // From the registry, not measured here: anything reaching a record mid-tick joins first, and that
        // stall is world-thread time too.
        long stall = registry.takeStall();
        pass.lastStallNanos = pass.ranOffThread ? stall : pass.passNanos;
        SwarmProfiler.charge(SwarmProfiler.Phase.SIM_ASYNC, pass.passNanos);
        SwarmProfiler.charge(SwarmProfiler.Phase.SIM_WAIT, pass.lastStallNanos);
        registry.setDirty();
    }

    /** Plan and move every record: one pass, no entity list, no chunk, the same {@link GlyphidBrain}. */
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

    /** Forget a level's prefetched terrain, for a tier that has been switched off or a swarm that has gone. */
    static void idle(ServerLevel level) {
        SimGlyphidPass pass = BY_LEVEL.get(level.dimension());
        if (pass != null) {
            pass.world.clear();
            pass.advanced = 0;
        }
    }

    /**
     * @return the lines of {@code swarmbench simthread}: where the pass ran, what it cost, and how much of
     * that the world thread waited out. A stall the size of the pass means the work moved and the wait did
     * not.
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
