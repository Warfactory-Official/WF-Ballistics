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
 * Decides which tier each glyphid belongs in, and runs the ones that are records.
 *
 * <p>The rule in one line: <b>a glyphid is an entity when something could interact with it, and a record the
 * rest of the time.</b> Everything below is that rule spelled out, plus the hysteresis that stops a bug on
 * the boundary from flickering between forms twice a second.
 *
 * <p>The saving is not subtle and it is not an optimisation of anything. §22.1 measured a marching swarm as
 * 33.8% {@code Entity.move} and collision, 18.9% {@code baseTick}, 5.7% data sync and 5.2% attribute lookups
 * — nearly two thirds of it spent on being an entity rather than on being a glyphid. A record pays none of
 * that, and the decisions it does make are the same ones, taken by the same brain.
 *
 * <p><b>Both directions are budgeted.</b> A player walking toward a swarm crosses the boundary for hundreds
 * of glyphids within a few ticks, and {@code addFreshEntity} is not free — done in one go it is exactly the
 * single-tick spike §11.8 says matters more than the mean. Spread over ticks it is invisible, and the
 * hysteresis band is wide enough that nothing arrives late because of it.
 */
public final class SimGlyphidManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Inside this range of a player a glyphid is a real entity.
     *
     * <p>Not a render distance: sim glyphids are drawn (see {@code SimGlyphidPacket}). It is an
     * <em>interaction</em> distance — the range within which something could shoot one, be bitten by one, or
     * walk into one — with enough margin that nothing has to become real in the same tick it becomes
     * relevant. A bug covers about four blocks a second, so 64 is three seconds of warning.
     */
    public static double range = 64.0;
    /**
     * Extra distance a glyphid must put between itself and every player before it may go back to being a
     * record. Without a band this wide, one standing on the boundary changes form every other tick.
     */
    private static final double HYSTERESIS = 24.0;
    /**
     * Bodies created per level per tick, and records made per level per tick.
     */
    private static final int PROMOTE_PER_TICK = 24;
    private static final int DEMOTE_PER_TICK = 24;
    /**
     * Ticks between tier decisions. The band is 24 blocks and a glyphid covers about a fifth of one a tick,
     * so a five-tick gap costs at most a block of the margin.
     */
    private static final int DECIDE_INTERVAL = 5;

    private SimGlyphidManager() {
    }

    /**
     * The front half of the tick, from {@code LevelTickEvent.Pre}: read the world, then set the pass going.
     *
     * <p>Split from {@link #tick} so the records advance <em>while</em> the world thread runs the vanilla
     * level tick rather than after it. See {@link SimGlyphidPass} for the contract that makes that safe.
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
     *
     * <p>Decisions after the walk rather than before it, which is the one thing the split changes. A glyphid
     * demoted here has already had its tick as an entity and takes its first as a record next tick, and one
     * promoted here had its tick as a record and takes its first as an entity next tick — so neither loses a
     * tick, and the demoted one no longer moves twice on the tick it changes form.
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
     * Whether a live glyphid is doing nothing that needs a body.
     *
     * <p>Every clause here is a capability the record does not have, and the list is the honest statement of
     * what the tier is: a glyphid that is fighting, swimming, flying, chewing, wounded, falling, being
     * ridden, or is one of the two castes whose whole point is a world write, stays an entity. What is left
     * is a glyphid walking somewhere, which is what a swarm mostly is.
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
        if (glyphid.getHealth() < glyphid.getMaxHealth()) {
            return false;
        }
        if (!simmableCaste(GlyphidCaste.byType(glyphid.getType()))) {
            return false;
        }
        return !watched(players, glyphid.getX(), glyphid.getZ(), range + HYSTERESIS);
    }

    /**
     * Two castes never leave the entity tier.
     *
     * <p>The scout founds nests, which is a world write and the mechanic the whole expansion loop turns on.
     * The nuclear one detonates when it dies, and a record's death is a list removal — simming one would be a
     * way of quietly disarming the most dangerous thing a colony can send. Neither is a limitation worth
     * engineering around: between them they are a few percent of a warband.
     */
    private static boolean simmableCaste(GlyphidCaste caste) {
        return caste != GlyphidCaste.SCOUT && caste != GlyphidCaste.NUCLEAR;
    }

    // --- record -> entity ---

    /**
     * Give a body back to every record that has run into something only a body can do.
     *
     * <p>Backwards over the list so a removal cannot skip the next entry, and — the part that matters — a
     * record is only dropped once it is <em>either</em> dead <em>or</em> standing in the world. A failed
     * spawn leaves it a record to be retried, for the same reason {@code WarbandMaterialiser} leaves a
     * glyphid owed to its warband: the count is the ledger, and nothing may spend it except the line that
     * puts a body on the ground.
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

    /**
     * Whether a record has run into something only a body can do.
     */
    private static boolean needsBody(ServerLevel level, List<ServerPlayer> players, SimGlyphid sim) {
        if (!sim.carrierAlive() || sim.wantsChew || sim.wounded) {
            return true;
        }
        if (sim.task != GlyphidTasks.TASK_FOLLOW || sim.atDestination()) {
            return true;
        }
        // Nowhere to put it. A record in an unloaded chunk keeps walking, which is the tier working; the
        // check is here so the spawn below never has to load one.
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

    /**
     * Give every record a body, whatever it is doing. For turning the tier off, and for shutdown: a record
     * left behind by a switched-off tier is a glyphid that has stopped existing without dying.
     */
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
     * A place the benchmark says to treat as a player, or null.
     *
     * <p>Bench-only, and it exists because the harness has nobody logged in. Without a watcher every glyphid
     * in a headless run is a record, which measures the tier at its best and never at all — the case that
     * matters is a swarm arriving at a base somebody is standing in, where the front rank has bodies and the
     * column behind it does not. Faking the standing is cheaper and more repeatable than logging a client in
     * and steering it.
     */
    public static double @org.jetbrains.annotations.Nullable [] watcher;

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
