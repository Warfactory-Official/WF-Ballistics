package com.wf.wflib.sim;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives every {@link SimKind}: Pre = prepare + dispatch advance; Post = join + resolve, per slot. */
public final class SimScheduler {

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    private static @Nullable ExecutorService pool;
    private static int poolSize;
    /** Testing/bench switch: false => every advance inline on the tick thread. */
    public static boolean asyncEnabled = true;

    private SimScheduler() {
    }

    /** One task per async kind per level: capped small. */
    public static void startup() {
        shutdownPool();
        poolSize = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        pool = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "wflib-sim-" + THREAD_NUMBER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Join everything in flight, then stop the pool. */
    public static void shutdown(Iterable<ServerLevel> levels) {
        for (ServerLevel level : levels) {
            for (SimTier<?> tier : SimWorld.get(level).tiers()) {
                tier.await();
            }
        }
        shutdownPool();
    }

    private static void shutdownPool() {
        ExecutorService running = pool;
        if (running != null) {
            running.shutdownNow();
            pool = null;
        }
        poolSize = 0;
    }

    public static int poolSize() {
        return poolSize;
    }

    public static void pre(ServerLevel level) {
        long now = level.getGameTime();
        for (SimTier<?> tier : SimWorld.get(level).tiers()) {
            begin(level, tier, now);
        }
    }

    private static <R> void begin(ServerLevel level, SimTier<R> tier, long now) {
        tier.await();
        SimKind<R> kind = tier.kind;
        kind.prepare(level, tier);
        ExecutorService workers = pool;
        if (kind.async() && asyncEnabled && workers != null && !tier.passRecords().isEmpty()) {
            tier.dispatch(workers.submit(() -> {
                WorldThread.assertOff("sim pass " + kind.id());
                run(tier, now);
            }));
        } else {
            run(tier, now);
        }
    }

    private static <R> void run(SimTier<R> tier, long now) {
        long start = System.nanoTime();
        tier.kind.advance(tier, now);
        tier.passNanos = System.nanoTime() - start;
    }

    public static void post(ServerLevel level, SimKind.Slot slot) {
        for (SimTier<?> tier : SimWorld.get(level).tiers()) {
            if (tier.kind.slot() == slot) {
                resolve(level, tier);
            }
        }
    }

    private static <R> void resolve(ServerLevel level, SimTier<R> tier) {
        tier.await();
        tier.kind.resolve(level, tier);
    }
}
