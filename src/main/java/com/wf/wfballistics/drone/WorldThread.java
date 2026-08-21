package com.wf.wfballistics.drone;

/**
 * Which thread is the world thread, and assertions that the drone system stays on the right side of it.
 *
 * <p>The whole drone AI rests on one split: <b>anything that reads the world runs on the world thread, and
 * anything expensive runs off it.</b> Chunk reads and entity mutation on the server thread; terrain search
 * and flight decisions on the pool; results handed back and applied on the server thread again.
 *
 * <p>That split is mostly enforced by the type system: {@code DroneSnapshot} and {@code TerrainField} hold
 * no {@code Level}, {@code Entity} or {@code ChunkAccess}, so a worker has nothing to reach through. But
 * types cannot express "this method must not be called from over there", and the failure mode if someone
 * later moves a call across the line is the worst kind: it works fine until it corrupts a chunk under load.
 * So the two boundary crossings assert it outright, and {@code DroneSelfTest} checks the assertions are armed
 * and firing.
 */
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
            throw new IllegalStateException("[wfballistics] " + what
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
            throw new IllegalStateException("[wfballistics] " + what
                    + " reads the world and must only run on the server thread, but was called from "
                    + Thread.currentThread().getName());
        }
    }
}
