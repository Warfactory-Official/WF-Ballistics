package com.wf.wfballistics.drone.nav;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the route searches actually ran, and what they cost.
 *
 * <p>Exists so the off-thread guarantee can be <em>checked</em> rather than taken on trust: it records the
 * name of the thread each search ran on, which {@code /wfballistics drone threads} compares against the
 * server thread it is itself running on.
 *
 * <p>Only the real scheduler path records here: {@code DroneBrain#planRoute}, the thing the pool actually
 * submits. {@link PathPlanner#plan} is left a pure function so the self-test can call it directly with
 * synthetic terrain without those calls showing up as searches that ran on the server thread, which is
 * exactly the false alarm this diagnostic exists to avoid raising.
 *
 * <p>Written from workers, read from the world thread. Plain volatiles: an approximate reading is fine for a
 * diagnostic, and nothing here feeds a decision.
 */
public final class PlannerStats {

    private static final AtomicLong SEARCHES = new AtomicLong();

    private static volatile String lastThread = "none yet";
    private static volatile long lastMicros;
    private static volatile long worstMicros;

    private PlannerStats() {
    }

    public static void record(long micros) {
        lastThread = Thread.currentThread().getName();
        lastMicros = micros;
        worstMicros = Math.max(worstMicros, micros);
        SEARCHES.incrementAndGet();
    }

    /**
     * @return the name of the thread the most recent search ran on.
     */
    public static String lastThread() {
        return lastThread;
    }

    public static long lastMicros() {
        return lastMicros;
    }

    public static long worstMicros() {
        return worstMicros;
    }

    public static long count() {
        return SEARCHES.get();
    }

    public static void reset() {
        lastThread = "none yet";
        lastMicros = 0L;
        worstMicros = 0L;
        SEARCHES.set(0L);
    }
}
