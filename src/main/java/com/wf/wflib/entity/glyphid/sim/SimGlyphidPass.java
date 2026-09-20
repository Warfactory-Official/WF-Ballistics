package com.wf.wflib.entity.glyphid.sim;

import com.wf.wflib.debug.SwarmBench;
import com.wf.wflib.debug.SwarmProfiler;
import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.entity.glyphid.brain.GlyphidBrain;
import com.wf.wflib.entity.glyphid.brain.GlyphidSnapshot;
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

/** Runs the sim tier's per-record work on a worker, across the world thread's own tick. */
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

    /** Stop the pool, once every level has joined what it had in flight. */
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

    /** Do this level's world reads and set the pass going. */
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

    /** Wait for the pass and book what it cost. */
    static void join(ServerLevel level, SimGlyphidRegistry registry) {
        registry.await();
        SimGlyphidPass pass = BY_LEVEL.get(level.dimension());
        if (pass == null || pass.advanced == 0) {
            return;
        }
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
     *      that the world thread waited out. A stall the size of the pass means the work moved and the wait did
     *      not.
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
