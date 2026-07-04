package com.wf.wfballistics.sim;

/**
 * Tunables for the missile chunk-loading + off-world simulation system. Plain constants for now
 * (the mod has no config file yet); centralised here so they are easy to promote to a
 * {@code ForgeConfigSpec} later.
 */
public final class MissileSimConfig {
    /**
     * How far ahead of the missile (toward its travel direction) to keep chunks loaded.
     */
    public static final double FAN_LOOKAHEAD = 64.0;

    // --- Stage 1: chunk-loading fan (blocks) ---
    /**
     * Lateral padding around the missile/look-ahead segment.
     */
    public static final double FAN_LATERAL = 32.0;
    /**
     * Within this horizontal distance of the target, a high-altitude missile starts force-loading its
     * look-ahead fan again so the terminal dive/impact has terrain loaded (gives lead time before the
     * ATTACK phase). Keep comfortably larger than the per-tick travel distance for fast missiles.
     */
    public static final double FAN_TERMINAL_RANGE = 128.0;
    /**
     * Ticks a missile must spend in CRUISE before it is allowed to offload to simulation (~5s).
     */
    public static final int CRUISE_SIM_DELAY_TICKS = 100;

    // --- Stage 2: off-world simulation ---
    /**
     * Horizontal distance to target at which a simulated missile is respawned for its terminal run.
     */
    public static final double DESTINATION_RANGE = 1000.0;
    /**
     * Clamp on the per-tick gametime delta so a long gap (restart/lag) can't teleport a missile.
     */
    public static final int MAX_SIM_STEP = 40;
    /**
     * Minimum altitude a missile flies at while simulated, to avoid respawning inside terrain.
     */
    public static final double SIM_ALTITUDE_FLOOR = 200.0;
    /**
     * A simulated missile becomes real slightly before crossing a listener boundary, by this margin.
     */
    public static final double LISTENER_SPAWN_MARGIN = 16.0;

    // --- Listeners ---
    /**
     * Radius around each online player that pulls a passing simulated missile back into existence.
     */
    public static final double PLAYER_LISTENER_RANGE = 160.0;
    /**
     * Default range of the debug listener block.
     */
    public static final double DEBUG_LISTENER_RANGE = 512.0;
    /**
     * Blocks between an interceptor and its target at which they are considered to collide.
     */
    public static final double INTERCEPT_DISTANCE = 24.0;

    // --- Simulated interception ---
    /**
     * Probability an intercept roll succeeds (CHANCE_ROLL mode only), when the interceptor carries no
     * per-missile chance of its own.
     */
    public static final float INTERCEPT_CHANCE = 0.5f;
    /**
     * Simulated interceptors close on their target at this speed (blocks/tick).
     */
    public static final double INTERCEPTOR_SPEED = 1.5;
    /**
     * An interceptor self-destructs (fizzles) after this many ticks aloft, so a miss can't loiter forever.
     */
    public static final int INTERCEPTOR_LIFETIME_TICKS = 600;

    // --- Real (in-world) interceptor entities ---
    /**
     * Ticks an interceptor may go without any resolvable target before it gives up and fizzles.
     */
    public static final int INTERCEPTOR_LOST_TARGET_TICKS = 60;
    /**
     * Detection radius the interceptor registers as an {@link IMissileListener} so nearby off-world missiles
     * rematerialize into real entities it can actually engage. Bounded to cap rematerialization cost.
     */
    public static final double INTERCEPTOR_LISTENER_RANGE = 64.0;
    /**
     * Default cruise speed for an in-world interceptor (blocks/tick). Above the target dive speeds
     * ({@code AttackStage} -8, {@code VerticalDiveStage} -14 are terminal only) so it can close pre-terminal.
     */
    public static final double INTERCEPTOR_ENTITY_SPEED = 4.0;
    /**
     * Default max heading change per tick (radians) for an in-world interceptor — nimble, near-pure pursuit.
     */
    public static final double INTERCEPTOR_TURN_RATE = 0.6;
    /**
     * Default cruise speed for a supersonic interceptor — fast enough to actually run down a supersonic
     * missile (rather than only cross its path).
     */
    public static final double INTERCEPTOR_SUPERSONIC_ENTITY_SPEED = 9.0;
    /**
     * Default max heading change per tick (radians) for a supersonic interceptor.
     */
    public static final double INTERCEPTOR_SUPERSONIC_TURN_RATE = 0.5;
    /**
     * How many ticks ahead the collision predictor integrates the two tracks (IN_WORLD mode).
     */
    public static final int PREDICT_HORIZON_TICKS = 400;
    /**
     * Trigger the in-world spawn once the predicted collision is within this many ticks.
     */
    public static final int INTERCEPT_LEAD_TICKS = 100;
    /**
     * Distance back from the predicted collision point at which each missile is spawned.
     */
    public static final double INTERCEPT_SPAWN_DISTANCE = 64.0;
    /**
     * Max length (blocks) of one collision sub-segment. The nose-extended sweep length is split into
     * {@code ceil(sweepLen / this)} substeps, clamped by {@link #COLLISION_MAX_SUBSTEPS}. The block DDA
     * ({@link net.minecraft.world.level.Level#clip}) is continuous regardless, so for {@code CENTER_RAY}
     * this only bounds per-call setup; for {@code OBB_SWEEP} it is the box-sampling pitch.
     */
    public static final double COLLISION_MAX_SUBSTEP_DIST = 4.0;
    /**
     * Hard clamp on substeps/tick so a very fast or stuck missile cannot blow the ray budget.
     */
    public static final int COLLISION_MAX_SUBSTEPS = 8;
    /**
     * Default per-interceptor kill probability (real entities and simulated interceptors), overridable per
     * interceptor (see {@code MissileEntity.interceptChance} / {@code SimMissile.interceptChance}).
     */
    public static float DEFAULT_INTERCEPT_CHANCE = 0.90f;
    /**
     * Separation (blocks) at which an in-world interceptor's closest-approach test rolls for the kill. Kept
     * generous so it triggers reliably for fast crossing targets (cf. the sim's {@link #INTERCEPT_DISTANCE}).
     */
    public static double INTERCEPTOR_KILL_RADIUS = 6.0;
    /**
     * How far a NEAREST-mode interceptor scans for a hostile missile to home on, each tick.
     */
    public static double INTERCEPTOR_ACQUIRE_RANGE = 200.0;
    /**
     * Interceptor battery magazine size — how many interceptors it can fire before it must reload. 0 (default)
     * means unlimited (no ammo logistics).
     */
    public static int BATTERY_MAGAZINE = 0;
    /**
     * Ticks a battery takes to regenerate one interceptor toward its magazine (its "supply chain").
     */
    public static int BATTERY_RELOAD_TICKS = 200;
    /**
     * Stealth missiles are seen by automatic detection (interceptor acquisition, batteries, CIWS) only within
     * this short range (vs the detector's normal range) — the main effect of stealth: a tiny engagement window.
     */
    public static double STEALTH_DETECT_RANGE = 32.0;
    /**
     * Per-scan probability that a stealth missile within {@link #STEALTH_DETECT_RANGE} is actually detected, so
     * a fast stealth missile usually slips through the brief window (but can be seen — it is not invisible).
     */
    public static float STEALTH_DETECT_CHANCE = 0.25f;
    /**
     * Multiplier applied to a missile's evasion while it is in the terminal ATTACK dive, so a maneuvering
     * warhead is hardest to intercept on its way down (clamped so effective evasion never exceeds 1).
     */
    public static double DIVE_EVASION_MULTIPLIER = 1.5;
    /**
     * Kill-chance multiplier for a "crossing" shot — when the interceptor is too slow to catch the target and
     * can only try to cross its flight path. Noticeably lower than a proper timed intercept, so a slow
     * interceptor is unreliable against a fast (e.g. supersonic/ballistic) missile it cannot run down.
     */
    public static float INTERCEPTOR_CROSSING_HIT_FACTOR = 0.35f;
    /**
     * When true, a proximity intercept damages the target's health pool (shared with CIWS fire) instead of a
     * binary destroy/miss: a successful roll deals {@link #INTERCEPTOR_HIT_DAMAGE}, a miss deals
     * {@link #INTERCEPTOR_GRAZE_DAMAGE}, and the target dies once the pool is depleted. Lets interceptors +
     * CIWS combine and makes a missile's {@code health} (toughness) matter. Default off (binary kill).
     */
    public static boolean INTERCEPTOR_CHIP_MODE = false;
    /**
     * Damage a successful intercept deals to the health pool in chip mode (default {@code > DEFAULT_HEALTH},
     * so one good hit still downs an ordinary missile).
     */
    public static float INTERCEPTOR_HIT_DAMAGE = 60.0f;
    /**
     * Damage a missed intercept deals to the health pool in chip mode — many near-misses wear a missile down.
     */
    public static float INTERCEPTOR_GRAZE_DAMAGE = 8.0f;
    /**
     * cruiseSpeed (blocks/tick) at or above which a missile is classed "supersonic". Interceptor batteries
     * use this to split responsibility: the normal battery engages subsonic missiles, the supersonic battery
     * engages supersonic ones.
     */
    public static double SUPERSONIC_SPEED = 2.5;
    /**
     * Active interception mode. Togglable at runtime via {@code /wfballistics interceptmode ...}.
     */
    public static InterceptResolution INTERCEPT_MODE = InterceptResolution.IN_WORLD;

    // --- Continuous collision (anti-tunneling) ---
    /**
     * Active collision fidelity. Left on the cheap ray by default; {@code OBB_SWEEP} is opt-in.
     */
    public static CollisionFidelity COLLISION_FIDELITY = CollisionFidelity.CENTER_RAY;

    private MissileSimConfig() {
    }

    /**
     * How the interception between two simulated missiles is resolved.
     */
    public enum InterceptResolution {
        /**
         * Cheap: roll a chance when interceptor and target get close in simulation.
         */
        CHANCE_ROLL,
        /**
         * Realistic: predict the collision area and spawn both missiles into the loaded world near it.
         */
        IN_WORLD
    }

    /**
     * Fidelity of the swept missile-vs-block collision.
     */
    public enum CollisionFidelity {
        /**
         * Nose-extended DDA center ray (cheapest, default). Fully stops head-on tunneling.
         */
        CENTER_RAY,
        /**
         * Also samples the oriented body box per substep, catching oblique/edge clips a thin ray misses.
         */
        OBB_SWEEP
    }
}
