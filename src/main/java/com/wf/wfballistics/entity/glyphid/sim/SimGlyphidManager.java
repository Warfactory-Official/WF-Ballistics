package com.wf.wfballistics.entity.glyphid.sim;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.GlyphidTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which tier each glyphid belongs in, and runs the ones that are records: <b>a glyphid is an entity
 * when something could interact with it, and a record the rest of the time</b>, with hysteresis so one on
 * the boundary does not flicker.
 *
 * <p>§22.1 measured a marching swarm as nearly two thirds entity overhead — {@code Entity.move} and
 * collision, {@code baseTick}, data sync, attribute lookups. A record pays none of it and makes the same
 * decisions through the same brain.
 *
 * <p>Both directions are budgeted per tick, since a player walking at a swarm crosses the boundary for
 * hundreds of glyphids at once and {@code addFreshEntity} is not free.
 */
public final class SimGlyphidManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Inside this range of a player a glyphid is a real entity. An interaction distance, not a render one —
     * sim glyphids are drawn. A bug covers four blocks a second, so 64 is three seconds of warning.
     */
    public static double range = 64.0;
    /** Extra distance before a glyphid may go back to a record, so one on the boundary does not flicker. */
    private static final double HYSTERESIS = 24.0;
    /** Bodies created per level per tick, and records made per level per tick. */
    private static final int PROMOTE_PER_TICK = 24;
    private static final int DEMOTE_PER_TICK = 24;
    /** Ticks between tier decisions. At a fifth of a block a tick, five ticks costs a block of the band. */
    private static final int DECIDE_INTERVAL = 5;

    private SimGlyphidManager() {
    }

    /**
     * The front half of the tick, from {@code LevelTickEvent.Pre}: read the world, then set the pass going,
     * so records advance <em>during</em> the vanilla level tick. See {@link SimGlyphidPass}.
     */
    public static void beginTick(ServerLevel level) {
        if (!SwarmBench.simTier) {
            SimGlyphidPass.idle(level);
            return;
        }
        long t = SwarmProfiler.begin();
        SimGlyphidPass.begin(level, SimGlyphidRegistry.get(level));
        SwarmProfiler.end(SwarmProfiler.Phase.SIM, t);
    }

    /**
     * The back half, from {@code LevelTickEvent.Post}: join the pass, then decide who belongs in which tier.
     * Decisions come after the walk, so nothing loses a tick or moves twice on the tick it changes form.
     */
    public static void tick(ServerLevel level) {
        SimGlyphidRegistry registry = SimGlyphidRegistry.get(level);
        if (!SwarmBench.simTier) {
            if (registry.count() > 0) {
                promoteAll(level, registry);
            }
            return;
        }
        long t = SwarmProfiler.begin();
        SimGlyphidPass.join(level, registry);
        if (level.getGameTime() % DECIDE_INTERVAL == 0L) {
            demote(level, registry);
            promote(level, registry);
        }
        SwarmProfiler.end(SwarmProfiler.Phase.SIM, t);
    }

    // --- entity -> record ---

    private static void demote(ServerLevel level, SimGlyphidRegistry registry) {
        List<ServerPlayer> players = level.players();
        int budget = DEMOTE_PER_TICK;
        // Copied because discarding a body removes it from the tracker as it is being walked.
        for (EntityGlyphid glyphid : new ArrayList<>(GlyphidTracker.glyphids(level))) {
            if (budget <= 0) {
                return;
            }
            if (!simmable(players, glyphid)) {
                continue;
            }
            registry.add(SimGlyphid.fromEntity(registry.claimId(), glyphid));
            glyphid.discard();
            budget--;
        }
    }

    /**
     * Whether a live glyphid is doing nothing that needs a body. Every clause is a capability a record does
     * not have; what is left is a glyphid walking somewhere, which is what a swarm mostly is.
     */
    private static boolean simmable(List<ServerPlayer> players, EntityGlyphid glyphid) {
        if (!glyphid.isAlive() || glyphid.isRemoved()) {
            return false;
        }
        if (glyphid.getCurrentTask() != GlyphidTasks.TASK_FOLLOW || glyphid.isAtDestination()) {
            return false;
        }
        if (glyphid.getTarget() != null || glyphid.mind().chewing) {
            return false;
        }
        if (glyphid.canFly() || glyphid.isAirborne() || glyphid.isInWater() || !glyphid.onGround()) {
            return false;
        }
        if (glyphid.isPassenger() || !glyphid.getPassengers().isEmpty()) {
            return false;
        }
        // A bridge is made of hitboxes. An anchor without a body is a hole in the deck.
        if (glyphid.hasBridgeSlot()) {
            return false;
        }
        if (glyphid.getHealth() < glyphid.getMaxHealth()) {
            return false;
        }
        if (!simmableCaste(GlyphidCaste.byType(glyphid.getType()))) {
            return false;
        }
        return !watched(players, glyphid.getX(), glyphid.getZ(), range + HYSTERESIS);
    }

    /**
     * Two castes never leave the entity tier: the scout founds nests, which is a world write, and the nuclear
     * one detonates on death, where a record's death is a list removal.
     */
    private static boolean simmableCaste(GlyphidCaste caste) {
        return caste != GlyphidCaste.SCOUT && caste != GlyphidCaste.NUCLEAR;
    }

    // --- record -> entity ---

    /**
     * Give a body back to every record that has run into something only a body can do. Walked backwards so a
     * removal cannot skip an entry, and a record is dropped only once it is dead or standing in the world —
     * a failed spawn stays a record to be retried.
     */
    private static void promote(ServerLevel level, SimGlyphidRegistry registry) {
        List<ServerPlayer> players = level.players();
        int budget = PROMOTE_PER_TICK;
        List<SimGlyphid> all = registry.view();
        for (int i = all.size() - 1; i >= 0 && budget > 0; i--) {
            SimGlyphid sim = all.get(i);
            if (!sim.carrierAlive()) {
                all.remove(i);
                registry.setDirty();
                continue;
            }
            if (!needsBody(level, players, sim)) {
                continue;
            }
            if (materialise(level, sim)) {
                all.remove(i);
                registry.setDirty();
                budget--;
            }
        }
    }

    /** Whether a record has run into something only a body can do. */
    private static boolean needsBody(ServerLevel level, List<ServerPlayer> players, SimGlyphid sim) {
        if (!sim.carrierAlive() || sim.wantsChew || sim.wounded) {
            return true;
        }
        if (sim.task != GlyphidTasks.TASK_FOLLOW || sim.atDestination()) {
            return true;
        }
        // Nowhere to put it. A record in an unloaded chunk keeps walking, and the spawn never loads one.
        if (!level.hasChunk((int) Math.floor(sim.x) >> 4, (int) Math.floor(sim.z) >> 4)) {
            return false;
        }
        return watched(players, sim.x, sim.z, range);
    }

    private static boolean materialise(ServerLevel level, SimGlyphid sim) {
        if (!sim.carrierAlive()) {
            return false;
        }
        EntityGlyphid glyphid = sim.toEntity(level);
        if (glyphid == null || !level.addFreshEntity(glyphid)) {
            LOGGER.debug("[wfballistics] sim glyphid {} could not be given a body at ({}, {}, {})",
                    sim.id, (int) sim.x, (int) sim.y, (int) sim.z);
            return false;
        }
        return true;
    }

    /** Give every record a body, for turning the tier off and for shutdown. Nothing may be left behind. */
    public static void promoteAll(ServerLevel level, SimGlyphidRegistry registry) {
        List<SimGlyphid> all = registry.view();
        for (int i = all.size() - 1; i >= 0; i--) {
            SimGlyphid sim = all.get(i);
            if (!sim.carrierAlive() || materialise(level, sim)) {
                all.remove(i);
            }
        }
        registry.setDirty();
    }

    public static void promoteAll(ServerLevel level) {
        promoteAll(level, SimGlyphidRegistry.get(level));
    }

    /**
     * A place the benchmark says to treat as a player, or null. The harness has nobody logged in, and without
     * one every glyphid in a headless run is a record — which measures the tier at its best and never at all.
     */
    public static double @org.jetbrains.annotations.Nullable [] watcher;

    /**
     * Whether anybody -- a real player, or the bench's stand-in -- is within {@code radius} of a spot. Public
     * because the egg chambers ask the same question, and the fake watcher has to count for both.
     */
    public static boolean watched(ServerLevel level, double x, double z, double radius) {
        return watched(level.players(), x, z, radius);
    }

    private static boolean watched(List<ServerPlayer> players, double x, double z, double radius) {
        double limit = radius * radius;
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            if (dx * dx + dz * dz <= limit) {
                return true;
            }
        }
        double[] fake = watcher;
        if (fake != null) {
            double dx = fake[0] - x;
            double dz = fake[1] - z;
            return dx * dx + dz * dz <= limit;
        }
        return false;
    }

    public static int count(ServerLevel level) {
        return SimGlyphidRegistry.get(level).count();
    }

    /**
     * @return mean blocks covered last tick by the records in this level, for checking that the two tiers
     * walk at the same speed.
     */
    public static double meanSpeed(ServerLevel level) {
        List<SimGlyphid> all = SimGlyphidRegistry.get(level).view();
        if (all.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (SimGlyphid sim : all) {
            double dx = sim.x - sim.prevX;
            double dz = sim.z - sim.prevZ;
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total / all.size();
    }
}
