package com.wf.wfballistics.colony;

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
 * Splits a swarm into squads of roughly equal strength and gives each one something different to do.
 *
 * <p>A swarm that arrives and sends every bug at the nearest player is one problem to solve once. Split three
 * ways — one squad on each defender, one eating the reactor — it is three problems at once, and the defenders
 * have to divide too. That is the whole mechanic.
 *
 * <p><b>Equal by power, not by count.</b> Splitting forty bugs into two twenties puts every behemoth in one
 * half and hands the other half a rout. Each caste carries a {@code power} on its stat bundle and the split is
 * a greedy longest-processing-time partition: strongest first, each one onto whichever squad is currently
 * weakest. That is within a few percent of an even split for cases this size and costs one sort.
 *
 * <p><b>Stateless.</b> There is no squad object that lives between reassignments — the whole thing is
 * recomputed every {@link #REFORM_INTERVAL} ticks from what is actually standing there. Objectives die,
 * players move, bugs are killed; a squad roster that had to be maintained through all of that is a lifecycle
 * to get wrong, and recomputing it costs one pass over the swarm. Membership stays stable anyway because the
 * input is sorted deterministically, so an unchanged roster produces an unchanged split.
 */
public final class GlyphidSquads {

    /**
     * Ticks between reassignments. Also how stale a squad's fix on a moving player can get.
     */
    public static final int REFORM_INTERVAL = 100;
    /**
     * Most squads one swarm will divide into, however many objectives are going.
     */
    private static final int MAX_SQUADS = 4;
    /**
     * Below this many bugs a squad is not a squad, it is a casualty. Bounds the split for small swarms.
     */
    private static final int MIN_PER_SQUAD = 4;
    /**
     * How far from the swarm something has to be before it stops being worth splitting up over.
     */
    private static final double OBJECTIVE_RANGE = 96.0;
    /**
     * How far to look along the line to an objective for the wall in the way.
     */
    private static final double BREACH_REACH = 48.0;
    /**
     * Size of the cell that decides two glyphids came from the same place, as a power of two. 64 blocks: wide
     * enough to hold one nest and everything that spilled out of it, narrow enough that two colonies attacking
     * the same base still divide themselves separately.
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

        // Grouped by where they came from, not by where they are. A colony's attack force is the thing that
        // should divide itself; two colonies' warbands that happen to have met are not one swarm, and
        // clustering by position to work that out would cost more than the split does.
        Map<Long, List<EntityGlyphid>> hosts = new HashMap<>();
        for (EntityGlyphid bug : swarm) {
            if (!bug.hasHome || bug.isScoutType()) {
                continue;
            }
            // Quantised, not the exact position. A materialised warband spreads out as it lands and each bug
            // records where it personally came down, so keying on the exact block would give forty hosts of
            // one instead of one host of forty.
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
        // Median, not mean. Once a swarm has been split it is walking two ways at once, and the mean of that
        // is a point in the empty ground between them -- which is where objectives would then be looked for
        // and where a wall would then be sought. The median sits inside whichever group is larger, which is
        // somewhere glyphids actually are.
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

    /**
     * Point one glyphid at its squad's objective.
     */
    private static void order(ServerLevel level, EntityGlyphid bug, int squad, GlyphidObjective objective) {
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
     * What is worth splitting up over, best first.
     *
     * <p>Players before machines because a defender who is shooting is the more urgent problem, and the rally
     * point is always last so the list is never empty.
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

        IndustryCluster base = IndustryApi.nearestCluster(level, centreX, centreZ);
        if (base != null && base.distanceSqTo(centreX, centreZ) <= OBJECTIVE_RANGE * OBJECTIVE_RANGE) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.centerX(), base.centerZ());
            out.add(GlyphidObjective.machines(base.centerX(), y, base.centerZ()));
        }

        EntityGlyphid first = members.get(0);
        GlyphidObjective rally = GlyphidObjective.rally(first.taskX, first.taskY, first.taskZ);

        GlyphidObjective primary = out.isEmpty() ? rally : out.get(0);
        BlockPos wall = obstruction(level, centreX, centreY, centreZ, primary);
        if (wall != null) {
            out.add(GlyphidObjective.breach(wall.getX(), wall.getY(), wall.getZ()));
        }

        out.add(rally);
        return out;
    }

    /**
     * The first wall between the swarm and where it wants to be, or null if the way is open enough.
     *
     * <p>Two filters, because almost any ray across real terrain hits something. The ray has to have struck a
     * <em>side</em> face rather than a top, and the block above the hit has to be solid too. Together those
     * say "vertical and at least two tall", which is a wall; a step or a shallow slope fails both, and a
     * glyphid would have walked over it anyway. Getting this wrong sends a whole squad to chew a hillside.
     *
     * <p>Note what is deliberately not filtered: how close the wall is. An earlier version required the hit to
     * be several blocks out, on the theory that anything nearer was the swarm's own ground. That threw the
     * objective away in precisely the case it exists for -- a swarm already pressed against the wall it needs
     * to open, which is where every one of them ends up.
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
