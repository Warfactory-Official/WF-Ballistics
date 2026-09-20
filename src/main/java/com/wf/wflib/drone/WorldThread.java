package com.wf.wflib.drone;

/** Which thread is the world thread, and assertions that the drone system stays on the right side of it. */
public final class WorldThread {

    private static volatile Thread world;

    private WorldThread() {
    }

    /**
     * Record the calling thread as the world thread. Called once from server startup.
     */
    public static void mark() {
        world = Thread.currentThread();
    }

    public static void clear() {
        world = null;
    }

    /**
     * @return true once {@link #mark} has run, i.e. the assertions below actually mean something.
     */
    public static boolean armed() {
        return world != null;
    }

    public static boolean isWorldThread() {
        Thread known = world;
        return known != null && Thread.currentThread() == known;
    }

    /**
     * Refuse to run expensive work on the server thread.
     *
     * @throws IllegalStateException if called from the world thread
     */
    public static void assertOff(String what) {
        if (isWorldThread()) {
            throw new IllegalStateException("[wflib] " + what
                    + " must not run on the server thread - it is meant to be computed off-thread and only"
                    + " applied on the world thread");
        }
    }

    /**
     * Refuse to touch the world from a worker.
     *
     * @throws IllegalStateException if called from anywhere but the world thread
     */
    public static void assertOn(String what) {
        Thread known = world;
        if (known != null && Thread.currentThread() != known) {
            throw new IllegalStateException("[wflib] " + what
                    + " reads the world and must only run on the server thread, but was called from "
                    + Thread.currentThread().getName());
        }
    }
}
