package com.wf.wfballistics.drone.nav;

import java.util.concurrent.atomic.AtomicLong;

/** Where the route searches actually ran, and what they cost. */
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
