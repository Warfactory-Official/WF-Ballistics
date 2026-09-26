package com.wf.wflib.entity.glyphid.sim;

import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.entity.glyphid.brain.GlyphidBrain;
import com.wf.wflib.entity.glyphid.brain.GlyphidSnapshot;
import com.wf.wflib.sim.SimKind;
import com.wf.wflib.sim.SimScheduler;
import com.wf.wflib.sim.SimTier;
import com.wf.wflib.sim.SimWorld;
import com.wf.wflib.debug.SwarmBench;
import com.wf.wflib.debug.SwarmProfiler;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.entity.glyphid.GlyphidTasks;
import com.wf.wflib.entity.glyphid.GlyphidTracker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Glyphid tiers: <b>entity when something could interact with it, record otherwise</b>, with hysteresis.
 * Prepare = terrain prefetch (tick thread); advance = brain pass over the records (worker, overlaps the level tick);
 * resolve (LATE) = demote/promote.
 */
public final class SimGlyphidManager implements SimKind<SimGlyphid> {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final SimGlyphidManager KIND = new SimGlyphidManager();

    /** Inside this range of a player a glyphid is a real entity. */
    public static double range = 64.0;
    /** Extra distance before a glyphid may go back to a record, so one on the boundary does not flicker. */
    private static final double HYSTERESIS = 24.0;
    /** Bodies created per level per tick, and records made per level per tick. */
    private static final int PROMOTE_PER_TICK = 24;
    private static final int DEMOTE_PER_TICK = 24;
    /** Ticks between tier decisions. At a fifth of a block a tick, five ticks costs a block of the band. */
    private static final int DECIDE_INTERVAL = 5;

    /** The world as each level's records may see it: refilled on the tick thread, read by the worker. */
    private static final Map<ResourceKey<Level>, SimWorldPrefetch> PREFETCH = new HashMap<>();
    private static volatile String ranOn = "-";

    private SimGlyphidManager() {
    }

    public static SimTier<SimGlyphid> tier(ServerLevel level) {
        return SimWorld.get(level).tier(KIND);
    }

    /** Next identity, counting down; persisted so a reload cannot reissue a live id. */
    public static int claimId(SimTier<SimGlyphid> tier) {
        int id = tier.meta().contains("nextId") ? tier.meta().getInt("nextId") : -1;
        tier.meta().putInt("nextId", id - 1);
        tier.setDirty();
        return id;
    }

    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "glyphid");
    }

    @Override
    public CompoundTag save(SimGlyphid record) {
        return record.save();
    }

    @Override
    public SimGlyphid load(CompoundTag tag) {
        return SimGlyphid.load(tag);
    }

    @Override
    public boolean async() {
        return SwarmBench.simAsync;
    }

    @Override
    public Slot slot() {
        return Slot.LATE;
    }

    @Override
    public void prepare(ServerLevel level, SimTier<SimGlyphid> tier) {
        SimWorldPrefetch world = PREFETCH.computeIfAbsent(level.dimension(), key -> new SimWorldPrefetch());
        if (!SwarmBench.simTier || tier.passRecords().isEmpty()) {
            world.clear();
            return;
        }
        long t = SwarmProfiler.begin();
        world.refill(level);
        SwarmProfiler.end(SwarmProfiler.Phase.SIM, t);
    }

    @Override
    public void advance(SimTier<SimGlyphid> tier, long gameTime) {
        if (!SwarmBench.simTier) {
            return;
        }
        List<SimGlyphid> records = tier.passRecords();
        if (records.isEmpty()) {
            return;
        }
        SimWorldPrefetch world = PREFETCH.get(tier.dimension());
        for (int i = 0; i < records.size(); i++) {
            SimGlyphid sim = records.get(i);
            sim.tickCount++;
            GlyphidSnapshot self = sim.snapshot(world);
            sim.apply(world, GlyphidBrain.plan(self, sim.mind()));
        }
        ranOn = Thread.currentThread().getName();
    }

    @Override
    public void resolve(ServerLevel level, SimTier<SimGlyphid> tier) {
        if (!SwarmBench.simTier) {
            if (tier.count() > 0) {
                promoteAll(level, tier);
            }
            return;
        }
        long t = SwarmProfiler.begin();
        SwarmProfiler.charge(SwarmProfiler.Phase.SIM_ASYNC, tier.passNanos());
        SwarmProfiler.charge(SwarmProfiler.Phase.SIM_WAIT, tier.takeStall());
        if (level.getGameTime() % DECIDE_INTERVAL == 0L) {
            demote(level, tier);
            promote(level, tier);
        }
        SwarmProfiler.end(SwarmProfiler.Phase.SIM, t);
    }

    /** {@code swarmbench simthread}: where the pass ran, its cost, prefetch stats. */
    public static List<String> report(ServerLevel level) {
        SimTier<SimGlyphid> tier = tier(level);
        SimWorldPrefetch world = PREFETCH.get(level.dimension());
        int[] terrain = world == null ? new int[4] : world.stats();
        return List.of(
                String.format(Locale.ROOT, "  %d records on %s (%s, %d workers, assertions %s)",
                        tier.count(), ranOn, SwarmBench.simAsync ? "async" : "async off", SimScheduler.poolSize(),
                        WorldThread.armed() ? "armed" : "NOT ARMED"),
                String.format(Locale.ROOT, "  last pass %.3f ms", tier.passNanos() / 1.0E6),
                String.format(Locale.ROOT,
                        "  prefetch: %d columns held, %d wanted, %d destinations, %d in unloaded chunks",
                        terrain[0], terrain[1], terrain[2], terrain[3]));
    }

    // --- entity -> record ---

    private static void demote(ServerLevel level, SimTier<SimGlyphid> registry) {
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
            registry.add(SimGlyphid.fromEntity(claimId(registry), glyphid));
            glyphid.discard();
            budget--;
        }
    }

    /** Whether a live glyphid is doing nothing that needs a body. */
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
     * Two castes never leave the entity tier: the scout founds nests, which is a world write, and the nuclear one
     * detonates on death, where a record's death is a list removal.
     */
    private static boolean simmableCaste(GlyphidCaste caste) {
        return caste != GlyphidCaste.SCOUT && caste != GlyphidCaste.NUCLEAR;
    }

    // --- record -> entity ---

    /** Give a body back to every record that has run into something only a body can do. */
    private static void promote(ServerLevel level, SimTier<SimGlyphid> registry) {
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
            LOGGER.debug("[wflib] sim glyphid {} could not be given a body at ({}, {}, {})",
                    sim.id, (int) sim.x, (int) sim.y, (int) sim.z);
            return false;
        }
        return true;
    }

    /** Give every record a body, for turning the tier off and for shutdown. Nothing may be left behind. */
    public static void promoteAll(ServerLevel level, SimTier<SimGlyphid> registry) {
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
        promoteAll(level, tier(level));
    }

    /** A place the benchmark says to treat as a player, or null. */
    public static double @org.jetbrains.annotations.Nullable [] watcher;

    /** Whether anybody (a real player, or the bench's stand-in) is within {@code radius} of a spot. */
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
        return tier(level).count();
    }

    /**
     * @return mean blocks covered last tick by the records in this level, for checking that the two tiers
     *      walk at the same speed.
     */
    public static double meanSpeed(ServerLevel level) {
        List<SimGlyphid> all = tier(level).view();
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
