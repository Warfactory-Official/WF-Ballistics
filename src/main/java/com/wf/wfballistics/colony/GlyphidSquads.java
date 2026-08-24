package com.wf.wfballistics.colony;

import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.GlyphidTracker;
import com.wf.wfballistics.industry.IndustryApi;
import com.wf.wfballistics.industry.IndustryCluster;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a swarm into squads of roughly equal strength and gives each one something different to do, so a
 * swarm is several problems at once rather than one.
 *
 * <p>Equal by <em>power</em>, not count, or every behemoth ends up in one half: a greedy
 * longest-processing-time partition, strongest first onto whichever squad is weakest.
 *
 * <p>Stateless — recomputed every {@link #REFORM_INTERVAL} ticks from what is standing there rather than
 * maintained as a roster. The input is sorted deterministically, so an unchanged roster splits the same way.
 */
public final class GlyphidSquads {

    /** Ticks between reassignments. Also how stale a squad's fix on a moving player can get. */
    public static final int REFORM_INTERVAL = 100;
    /** Most squads one swarm will divide into, however many objectives are going. */
    private static final int MAX_SQUADS = 4;
    /** Below this many bugs a squad is not a squad, it is a casualty. Bounds the split for small swarms. */
    private static final int MIN_PER_SQUAD = 4;
    /** How far from the swarm something has to be before it stops being worth splitting up over. */
    private static final double OBJECTIVE_RANGE = 96.0;
    /** How far to look along the line to an objective for the wall in the way. */
    private static final double BREACH_REACH = 48.0;
    /** How far apart two machine objectives must be to be different parts of a base: about a room. */
    private static final double MACHINE_SEPARATION = 24.0;
    /**
     * Size of the cell that decides two glyphids came from the same place, as a power of two. 64 blocks holds
     * one nest and its spill, while keeping two colonies at the same base separate.
     */
    private static final int HOME_CELL_BITS = 6;

    private GlyphidSquads() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % REFORM_INTERVAL != 0L) {
            return;
        }
        Set<EntityGlyphid> swarm = GlyphidTracker.glyphids(level);
        if (swarm.size() < MIN_PER_SQUAD * 2) {
            return;
        }
        long t = SwarmProfiler.begin();
        try {
            split(level, swarm);
        } finally {
            SwarmProfiler.end(SwarmProfiler.Phase.SQUAD, t);
        }
    }

    private static void split(ServerLevel level, Set<EntityGlyphid> swarm) {

        // Grouped by where they came from, not where they are: two warbands that happen to have met are not
        // one swarm, and clustering by position to work that out costs more than the split.
        Map<Long, List<EntityGlyphid>> hosts = new HashMap<>();
        for (EntityGlyphid bug : swarm) {
            if (!bug.hasHome || bug.isScoutType()) {
                continue;
            }
            // Quantised: a warband spreads as it lands, so the exact block would give forty hosts of one.
            long cell = ((long) (bug.homeX >> HOME_CELL_BITS) << 32) ^ (bug.homeZ >> HOME_CELL_BITS) & 0xFFFFFFFFL;
            hosts.computeIfAbsent(cell, k -> new ArrayList<>()).add(bug);
        }
        for (List<EntityGlyphid> host : hosts.values()) {
            if (host.size() >= MIN_PER_SQUAD * 2) {
                assign(level, host);
            }
        }
    }

    private static void assign(ServerLevel level, List<EntityGlyphid> members) {
        // Median, not mean: a split swarm walks two ways at once and the mean lands in the empty ground
        // between them. The median sits inside the larger group, where glyphids actually are.
        double centreX = median(members, EntityGlyphid::getX);
        double centreY = median(members, EntityGlyphid::getY);
        double centreZ = median(members, EntityGlyphid::getZ);

        List<GlyphidObjective> objectives = discover(level, members, centreX, centreY, centreZ);
        int squads = Math.min(Math.min(objectives.size(), MAX_SQUADS), members.size() / MIN_PER_SQUAD);
        if (squads <= 0) {
            return;
        }

        // Sorted by power descending, then by id so an unchanged roster splits the same way twice running.
        members.sort(Comparator.comparingDouble((EntityGlyphid bug) -> -bug.getStats().power())
                .thenComparingInt(EntityGlyphid::getId));

        double[] load = new double[squads];
        for (EntityGlyphid bug : members) {
            int weakest = 0;
            for (int i = 1; i < squads; i++) {
                if (load[i] < load[weakest]) {
                    weakest = i;
                }
            }
            load[weakest] += bug.getStats().power();
            order(level, bug, weakest, objectives.get(weakest));
        }
    }

    /** Point one glyphid at its squad's objective. */
    private static void order(ServerLevel level, EntityGlyphid bug, int squad, GlyphidObjective objective) {
        // Captured before the first overwrite, or the warband's own destination is lost on the first split.
        if (!bug.hasRally) {
            GlyphidObjective rally = rallyOf(bug);
            bug.hasRally = true;
            bug.rallyX = rally.x();
            bug.rallyY = rally.y();
            bug.rallyZ = rally.z();
        }
        bug.squad = squad;
        bug.objective = objective;
        bug.taskX = objective.x();
        bug.taskZ = objective.z();
        bug.taskY = level.hasChunk(objective.x() >> 4, objective.z() >> 4)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, objective.x(), objective.z())
                : objective.y();
        if (bug.getCurrentTask() != GlyphidTasks.TASK_FOLLOW) {
            bug.setCurrentTask(GlyphidTasks.TASK_FOLLOW, null);
        }
    }

    /**
     * What is worth splitting up over, best first. Players before machines, since a defender who is shooting
     * is the more urgent problem, and the rally point last so the list is never empty.
     */
    private static List<GlyphidObjective> discover(ServerLevel level, List<EntityGlyphid> members,
                                                   double centreX, double centreY, double centreZ) {
        List<GlyphidObjective> out = new ArrayList<>();

        List<ServerPlayer> players = new ArrayList<>(level.players());
        players.sort(Comparator.comparingDouble(p -> distanceSq(p.getX(), p.getZ(), centreX, centreZ)));
        for (ServerPlayer player : players) {
            if (player.isSpectator() || player.isCreative()) {
                continue;
            }
            if (distanceSq(player.getX(), player.getZ(), centreX, centreZ) > OBJECTIVE_RANGE * OBJECTIVE_RANGE) {
                continue;
            }
            out.add(GlyphidObjective.player(player.getUUID(), player.getX(), player.getY(), player.getZ()));
            if (out.size() >= MAX_SQUADS) {
                break;
            }
        }

        addMachines(level, out, centreX, centreZ);

        EntityGlyphid first = members.get(0);
        GlyphidObjective rally = rallyOf(first);

        GlyphidObjective primary = out.isEmpty() ? rally : out.get(0);
        BlockPos wall = obstruction(level, centreX, centreY, centreZ, primary);
        if (wall != null) {
            out.add(GlyphidObjective.breach(wall.getX(), wall.getY(), wall.getZ()));
        }

        out.add(rally);
        return out;
    }

    /**
     * Where a squad with nothing better to do goes: one rule read at three times, being whatever this glyphid
     * was doing before any squad touched it. The fallback to where it came down matters — it is the only
     * position a split cannot have overwritten, and without it an early-assigned swarm walks to nowhere.
     */
    private static GlyphidObjective rallyOf(EntityGlyphid bug) {
        if (bug.hasRally) {
            return GlyphidObjective.rally(bug.rallyX, bug.rallyY, bug.rallyZ);
        }
        return bug.getCurrentTask() == GlyphidTasks.TASK_FOLLOW
                ? GlyphidObjective.rally(bug.taskX, bug.taskY, bug.taskZ)
                : GlyphidObjective.rally(bug.homeX, bug.homeY, bug.homeZ);
    }

    /**
     * What there is to wreck, best first: the block-precise machine where the base is loaded, the cluster
     * centre where it is not. The centre only has to point the walk, and the objective sharpens on arrival.
     *
     * <p>The cluster is not a precondition for the precise lookup — a centre in 512-block cells can sit
     * hundreds of blocks from its own machines, and gating on it threw away a base the swarm stood inside.
     *
     * <p>Up to two, {@link #MACHINE_SEPARATION} apart: two squads on one machine hall is one objective.
     */
    private static void addMachines(ServerLevel level, List<GlyphidObjective> out,
                                    double centreX, double centreZ) {
        BlockPos first = IndustryApi.pressingMachine(level, centreX, centreZ, OBJECTIVE_RANGE);
        if (first != null) {
            out.add(GlyphidObjective.machines(first.getX(), first.getY(), first.getZ()));
            BlockPos second = IndustryApi.pressingMachine(level, centreX, centreZ, OBJECTIVE_RANGE,
                    first, MACHINE_SEPARATION);
            if (second != null) {
                out.add(GlyphidObjective.machines(second.getX(), second.getY(), second.getZ()));
            }
            return;
        }

        IndustryCluster base = IndustryApi.nearestCluster(level, centreX, centreZ);
        if (base == null || base.distanceSqTo(centreX, centreZ) > OBJECTIVE_RANGE * OBJECTIVE_RANGE) {
            return;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.centerX(), base.centerZ());
        out.add(GlyphidObjective.machines(base.centerX(), y, base.centerZ()));
    }

    /**
     * The first wall between the swarm and where it wants to be, or null if the way is open enough.
     *
     * <p>Two filters, since almost any ray across terrain hits something: the ray must strike a side face
     * rather than a top, and the block above must be solid. Together that means "vertical and two tall",
     * which a step or a slope fails — and getting it wrong sends a squad to chew a hillside.
     *
     * <p>Distance is deliberately not filtered. Requiring the hit to be a few blocks out discarded the very
     * case this exists for: a swarm already pressed against the wall it needs to open.
     */
    private static @Nullable BlockPos obstruction(ServerLevel level, double x, double y, double z,
                                                  GlyphidObjective towards) {
        Vec3 from = new Vec3(x, y + 1.5, z);
        Vec3 to = new Vec3(towards.x() + 0.5, towards.y() + 1.5, towards.z() + 0.5);
        if (from.distanceTo(to) > BREACH_REACH) {
            to = from.add(to.subtract(from).normalize().scale(BREACH_REACH));
        }
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        if (!hit.getDirection().getAxis().isHorizontal()) {
            return null;
        }
        BlockPos wall = hit.getBlockPos();
        return level.getBlockState(wall.above()).isSolidRender(level, wall.above()) ? wall : null;
    }

    private static double median(List<EntityGlyphid> members, java.util.function.ToDoubleFunction<EntityGlyphid> axis) {
        double[] values = new double[members.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = axis.applyAsDouble(members.get(i));
        }
        java.util.Arrays.sort(values);
        return values[values.length / 2];
    }

    private static double distanceSq(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }
}
