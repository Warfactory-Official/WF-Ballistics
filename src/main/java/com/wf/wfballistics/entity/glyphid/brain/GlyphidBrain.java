package com.wf.wfballistics.entity.glyphid.brain;

import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Every decision a glyphid makes about where to go and what to bite, as a function of a
 * {@link GlyphidSnapshot} and the glyphid's own {@link GlyphidMind}.
 *
 * <p>Touches no world. That is the point of the class and it is checked by what it imports: no {@code Level},
 * no {@code Entity}, no {@code Path}. Behaviour that a warband record will eventually have to reproduce
 * without a body lives here; the world reads that carry a decision out — the A*, the heightmap sample, the
 * raycast, the bite — stay with the applier.
 *
 * <p>Two rules encoded here cost real time to learn:
 *
 * <p><b>Path in hops, with the range named.</b> Vanilla's {@code createPath(pos, accuracy)} takes its range
 * from the mob's follow range — 16 for a monster — so a distant destination comes back as a stub that walks
 * two blocks and stops. A waypoint {@link #HOP} blocks along the bearing, asked for with an explicit range,
 * always yields a usable route.
 *
 * <p><b>Back off on not arriving, not on not pathing.</b> The pathfinder almost never returns nothing: when the
 * destination is unreachable it hands back a partial route to the best node it found, so the expensive case is
 * also the one that reads as success. Movement is the only honest signal — but only movement <em>towards the
 * destination</em>, because a glyphid being jostled by three hundred neighbours is moving constantly and
 * arriving never.
 */
public final class GlyphidBrain {

    /**
     * How far ahead the next pathing waypoint is placed.
     */
    public static final int HOP = 16;
    /**
     * Path range asked for, which has to clear {@link #HOP} with room to spare.
     */
    public static final int PATH_RANGE = HOP + 8;
    /**
     * Never repath more often than this. One A* per glyphid per second is already the dominant cost in a
     * swarm; the point of hopping is to amortise it over the walk, not to pay it every tick.
     */
    public static final int REPATH_MIN = 20;
    /**
     * Repath at least this often even while a path is still being walked, so the bearing stays fresh.
     */
    public static final int REPATH_MAX = 100;
    /**
     * How long a glyphid must fail to make progress before it stops trying to walk and starts chewing.
     */
    public static final int STUCK_TICKS = 60;
    /**
     * Progress, in blocks, that counts as not being stuck.
     */
    public static final double PROGRESS = 1.0;
    /**
     * How far ahead to look for the thing in the way.
     */
    public static final double CHEW_REACH = 3.0;
    /**
     * Inside this range, with the target in sight, a glyphid walks straight at it instead of pathfinding.
     *
     * <p>Measured: melee path search was 28% of the tick, and every one of those searches was a glyphid running
     * a ninety-node A* to reach something it could already see. Steering costs a rotation and two block
     * lookups, and the move control still jumps and steps up on its own.
     */
    public static final double CHARGE_RANGE = 12.0;
    /**
     * Ticks between bites.
     */
    public static final int ATTACK_INTERVAL = 20;
    /**
     * How far the target may drift from the point we last aimed at before the hop is worth re-aiming. Vanilla
     * re-aims on one block of drift, which is what makes it repath every few ticks.
     */
    private static final double REAIM_DISTANCE_SQ = (double) HOP * HOP;

    /**
     * Nothing to walk toward.
     */
    public static final int ERRAND_NONE = 0;
    /**
     * Chasing something to bite.
     */
    public static final int ERRAND_MELEE = 1;
    /**
     * Walking to where the colony said to be.
     */
    public static final int ERRAND_MARCH = 2;

    private GlyphidBrain() {
    }

    /**
     * Which errand a glyphid is on, which is also the arbitration the movement goals used to do by priority:
     * biting outranks marching, and neither happens in the air.
     */
    public static int errand(boolean airborne, boolean hasTarget, int task, boolean atDestination) {
        if (airborne) {
            return ERRAND_NONE;
        }
        if (hasTarget) {
            return ERRAND_MELEE;
        }
        if (task == GlyphidTasks.TASK_FOLLOW && !atDestination) {
            return ERRAND_MARCH;
        }
        return ERRAND_NONE;
    }

    /**
     * Whether this is a tick a glyphid would get as far as choosing somewhere to path to.
     *
     * <p>Public so the body can skip world reads that only a repath needs — the destination's real height is
     * a heightmap lookup, and paying for one on every glyphid on every tick costs more than the search it
     * feeds. One copy of the rule, asked twice, rather than the same three conditions written down in two
     * places and left to drift apart.
     *
     * <p>The stagger is the third condition and it is the load-bearing one: one search slot per glyphid per
     * {@link #REPATH_MIN} ticks, so a swarm's searches can never all land on the same tick. Staggering only
     * where a walk starts is not enough, because anything that makes a cohort decide to repath at once —
     * arriving together, or all re-aiming at one target that moved — re-synchronises them. Measured: the worst
     * 5% of ticks ran ~8x the searches of the rest while each search stayed the same size, so the spikes were
     * pile-ups rather than hard searches.
     */
    public static boolean repathDue(GlyphidMind mind, int tickCount, int id, boolean navigationDone,
                                    boolean stagger) {
        int next = mind.sinceRepath + 1;
        if (next < mind.retryAfter) {
            return false;
        }
        if (!navigationDone && next < REPATH_MAX) {
            return false;
        }
        return !stagger || (tickCount + id) % REPATH_MIN == 0;
    }

    public static GlyphidPlan plan(GlyphidSnapshot self, GlyphidMind mind) {
        int errand = errand(self.airborne(), self.hasTarget(), self.task(), self.atDestination());
        if (errand != mind.errand) {
            // Switching errands restarts the walk from scratch, which is what a goal handing over to another
            // goal used to do. Without it a glyphid that just lost its target keeps the backoff it earned
            // failing to reach that target, and marches off under a penalty it has no reason to carry.
            mind.errand = errand;
            mind.reset(self.id(), self.position().x, self.position().z);
            if (errand == ERRAND_NONE) {
                return new GlyphidPlan(GlyphidPlan.Move.STOP, 0, 0, 0, false, null, null, false,
                        GlyphidPlan.KEEP_TASK, 0, 0.0);
            }
        }

        return switch (errand) {
            case ERRAND_MELEE -> melee(self, mind);
            case ERRAND_MARCH -> march(self, mind);
            default -> GlyphidPlan.IDLE;
        };
    }

    private static GlyphidPlan melee(GlyphidSnapshot self, GlyphidMind mind) {
        Vec3 target = self.targetPosition();
        if (target == null) {
            return GlyphidPlan.IDLE;
        }

        // Re-aim only once the target has left the hop we were walking to.
        double driftX = target.x - mind.aimedX;
        double driftZ = target.z - mind.aimedZ;
        if (driftX * driftX + driftZ * driftZ > REAIM_DISTANCE_SQ) {
            mind.repathNow();
        }

        mind.untilAttack = Math.max(mind.untilAttack - 1, 0);
        boolean bite = false;
        if (mind.untilAttack <= 0 && self.targetInReach() && self.targetVisible()) {
            mind.untilAttack = ATTACK_INTERVAL;
            bite = true;
        }

        if (self.charge() && self.targetDistanceSq() < CHARGE_RANGE * CHARGE_RANGE && self.targetVisible()) {
            // Line of sight to something this close means there is no wall left worth eating.
            mind.stopChewing();
            return new GlyphidPlan(GlyphidPlan.Move.CHARGE, 0, 0, 0, false, target, target, bite,
                    GlyphidPlan.KEEP_TASK, 0, 0.0);
        }
        return advance(self, mind, target, target, bite);
    }

    private static GlyphidPlan march(GlyphidSnapshot self, GlyphidMind mind) {
        Vec3 destination = new Vec3(self.taskX() + 0.5, self.taskY(), self.taskZ() + 0.5);
        return advance(self, mind, destination, null, false);
    }

    /**
     * Decide whether this is a tick to repath on, and if so where to.
     *
     * <p>Repathing is driven by the walk finishing rather than by a fixed interval. Repathing on a timer throws
     * away a live path the glyphid is a fraction of the way through and pays for a fresh A* to replace it,
     * which at swarm scale is the whole cost of the swarm.
     */
    private static GlyphidPlan advance(GlyphidSnapshot self, GlyphidMind mind, Vec3 destination,
                                       @Nullable Vec3 lookAt, boolean bite) {
        if (mind.chewing) {
            // Committed to the wall. Repathing now would throw away work already banked against the block,
            // and a glyphid that alternates between chewing and searching never finishes either.
            return new GlyphidPlan(GlyphidPlan.Move.CHEW, mind.chewX, mind.chewY, mind.chewZ, false,
                    destination, lookAt, bite, GlyphidPlan.KEEP_TASK, 0, 0.0);
        }
        if (!repathDue(mind, self.tickCount(), self.id(), self.navigationDone(), self.stagger())) {
            mind.sinceRepath++;
            return GlyphidPlan.hold(lookAt, bite);
        }
        mind.sinceRepath++;

        int elapsed = mind.sinceRepath;
        mind.sinceRepath = 0;

        Vec3 pos = self.position();
        mind.aimedX = destination.x;
        mind.aimedZ = destination.z;

        double dx = destination.x - pos.x;
        double dz = destination.z - pos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        // Ground covered *towards the destination*, not ground covered. See the note on the backoff above:
        // once the swarm pushes itself apart, plain displacement stops being an honest signal, because a
        // glyphid wedged in a crowd is shoved a block a second and every shove reads as a path that is
        // working. Projecting onto the bearing scores a sideways shove at nothing and a backwards one below
        // nothing, which is what they are worth.
        double moved = distance < 1.0E-4
                ? 0.0
                : ((pos.x - mind.lastX) * dx + (pos.z - mind.lastZ) * dz) / distance;
        mind.lastX = pos.x;
        mind.lastZ = pos.z;
        if (distance < 1.0) {
            // Standing on it. Counts as having got there rather than as a failed search, so the backoff does
            // not punish a glyphid for arriving.
            settle(mind, true, moved, elapsed);
            return GlyphidPlan.hold(lookAt, bite);
        }

        double reach = Math.min(distance, HOP);
        int hopX = Mth.floor(pos.x + dx / distance * reach);
        int hopZ = Mth.floor(pos.z + dz / distance * reach);
        int hopY;
        boolean sampleY;
        if (distance <= HOP) {
            // Close enough to aim at the thing itself; a heightmap sample here would put the waypoint on the
            // roof of whatever the target is standing under.
            hopY = Mth.floor(destination.y);
            sampleY = false;
        } else {
            hopY = Mth.floor(pos.y);
            sampleY = true;
        }
        return new GlyphidPlan(GlyphidPlan.Move.PATH, hopX, hopY, hopZ, sampleY, destination, lookAt, bite,
                GlyphidPlan.KEEP_TASK, elapsed, moved);
    }

    /**
     * Fold the outcome of a search back into the mind, once the applier knows whether one was found.
     *
     * <p>Split from {@link #plan} because whether a path was accepted is a world answer and this class does not
     * get to ask for one. Everything the answer feeds — the backoff, the stuck counter — is arithmetic, and
     * stays here so a body that cannot pathfind at all still ages its own patience the same way.
     *
     * @return where to chew, or null to keep walking
     */
    public static @Nullable Vec3 resolve(GlyphidPlan plan, GlyphidMind mind, boolean pathAccepted) {
        settle(mind, pathAccepted, plan.moved(), plan.elapsed());
        if (mind.stuckFor < STUCK_TICKS) {
            return null;
        }
        mind.stuckFor = 0;
        return plan.destination();
    }

    private static void settle(GlyphidMind mind, boolean pathAccepted, double moved, int elapsed) {
        boolean progressing = pathAccepted && moved > PROGRESS;
        mind.retryAfter = progressing ? REPATH_MIN : Math.min(mind.retryAfter * 2, REPATH_MAX);
        if (progressing) {
            mind.stuckFor = 0;
            return;
        }
        mind.stuckFor += elapsed;
    }
}
