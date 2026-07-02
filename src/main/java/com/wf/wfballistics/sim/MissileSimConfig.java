package com.wf.wfballistics.sim;

/**
 * Tunables for the missile chunk-loading + off-world simulation system. Plain constants for now
 * (the mod has no config file yet); centralised here so they are easy to promote to a
 * {@code ForgeConfigSpec} later.
 */
public final class MissileSimConfig {
    private MissileSimConfig() {
    }

    // --- Stage 1: chunk-loading fan (blocks) ---
    /** How far ahead of the missile (toward its travel direction) to keep chunks loaded. */
    public static final double FAN_LOOKAHEAD = 64.0;
    /** Lateral padding around the missile/look-ahead segment. */
    public static final double FAN_LATERAL = 32.0;
    /**
     * Within this horizontal distance of the target, a high-altitude missile starts force-loading its
     * look-ahead fan again so the terminal dive/impact has terrain loaded (gives lead time before the
     * ATTACK phase). Keep comfortably larger than the per-tick travel distance for fast missiles.
     */
    public static final double FAN_TERMINAL_RANGE = 128.0;

    // --- Stage 2: off-world simulation ---
    /** Ticks a missile must spend in CRUISE before it is allowed to offload to simulation (~5s). */
    public static final int CRUISE_SIM_DELAY_TICKS = 100;
    /** Horizontal distance to target at which a simulated missile is respawned for its terminal run. */
    public static final double DESTINATION_RANGE = 1000.0;
    /** Clamp on the per-tick gametime delta so a long gap (restart/lag) can't teleport a missile. */
    public static final int MAX_SIM_STEP = 40;
    /** Minimum altitude a missile flies at while simulated, to avoid respawning inside terrain. */
    public static final double SIM_ALTITUDE_FLOOR = 200.0;

    // --- Listeners ---
    /** A simulated missile becomes real slightly before crossing a listener boundary, by this margin. */
    public static final double LISTENER_SPAWN_MARGIN = 16.0;
    /** Radius around each online player that pulls a passing simulated missile back into existence. */
    public static final double PLAYER_LISTENER_RANGE = 160.0;
    /** Default range of the debug listener block. */
    public static final double DEBUG_LISTENER_RANGE = 512.0;

    // --- Simulated interception ---
    /** Blocks between an interceptor and its target at which they are considered to collide. */
    public static final double INTERCEPT_DISTANCE = 24.0;
    /** Probability an intercept roll succeeds (CHANCE_ROLL mode only). */
    public static final float INTERCEPT_CHANCE = 0.5f;
    /** Simulated interceptors close on their target at this speed (blocks/tick). */
    public static final double INTERCEPTOR_SPEED = 1.5;

    /**
     * How the interception between two simulated missiles is resolved.
     */
    public enum InterceptResolution {
        /** Cheap: roll a chance when interceptor and target get close in simulation. */
        CHANCE_ROLL,
        /** Realistic: predict the collision area and spawn both missiles into the loaded world near it. */
        IN_WORLD
    }

    /** Active interception mode. Togglable at runtime via {@code /wfballistics interceptmode ...}. */
    public static InterceptResolution INTERCEPT_MODE = InterceptResolution.IN_WORLD;
    /** How many ticks ahead the collision predictor integrates the two tracks (IN_WORLD mode). */
    public static final int PREDICT_HORIZON_TICKS = 400;
    /** Trigger the in-world spawn once the predicted collision is within this many ticks. */
    public static final int INTERCEPT_LEAD_TICKS = 100;
    /** Distance back from the predicted collision point at which each missile is spawned. */
    public static final double INTERCEPT_SPAWN_DISTANCE = 64.0;

    // --- Continuous collision (anti-tunneling) ---
    /**
     * Max length (blocks) of one collision sub-segment. The nose-extended sweep length is split into
     * {@code ceil(sweepLen / this)} substeps, clamped by {@link #COLLISION_MAX_SUBSTEPS}. The block DDA
     * ({@link net.minecraft.world.level.Level#clip}) is continuous regardless, so for {@code CENTER_RAY}
     * this only bounds per-call setup; for {@code OBB_SWEEP} it is the box-sampling pitch.
     */
    public static final double COLLISION_MAX_SUBSTEP_DIST = 4.0;
    /** Hard clamp on substeps/tick so a very fast or stuck missile cannot blow the ray budget. */
    public static final int COLLISION_MAX_SUBSTEPS = 8;

    /**
     * Fidelity of the swept missile-vs-block collision.
     */
    public enum CollisionFidelity {
        /** Nose-extended DDA center ray (cheapest, default). Fully stops head-on tunneling. */
        CENTER_RAY,
        /** Also samples the oriented body box per substep, catching oblique/edge clips a thin ray misses. */
        OBB_SWEEP
    }

    /** Active collision fidelity. Left on the cheap ray by default; {@code OBB_SWEEP} is opt-in. */
    public static CollisionFidelity COLLISION_FIDELITY = CollisionFidelity.CENTER_RAY;
}
