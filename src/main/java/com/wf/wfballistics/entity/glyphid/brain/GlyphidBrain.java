package com.wf.wfballistics.entity.glyphid.brain;

import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Every decision a glyphid makes about where to go and what to bite, as a function of a {@link GlyphidSnapshot} and
 * the glyphid's own {@link GlyphidMind}.
 */
public final class GlyphidBrain {

    /** How far ahead the next pathing waypoint is placed. */
    public static final int HOP = 16;
    /**
     * Path range asked for, which has to clear {@link #HOP} with room to spare.
     */
    public static final int PATH_RANGE = HOP + 8;
    /** Never repath more often than this. One A* per glyphid per second is already the dominant cost. */
    public static final int REPATH_MIN = 20;
    /** Repath at least this often even while a path is still being walked, so the bearing stays fresh. */
    public static final int REPATH_MAX = 100;
    /** How long a glyphid must fail to make progress before it stops trying to walk and starts chewing. */
    public static final int STUCK_TICKS = 60;
    /** Progress, in blocks, that counts as not being stuck. */
    public static final double PROGRESS = 1.0;
    /** How far ahead to look for the thing in the way. */
    public static final double CHEW_REACH = 3.0;
    /** Inside this range, with the target in sight, a glyphid steers instead of pathfinding. */
    public static final double CHARGE_RANGE = 12.0;
    /** Ticks between bites. */
    public static final int ATTACK_INTERVAL = 20;
    /** Drift the target may take before re-aiming. Vanilla re-aims on one block, and so repaths constantly. */
    private static final double REAIM_DISTANCE_SQ = (double) HOP * HOP;

    /** Nothing to walk toward. */
    public static final int ERRAND_NONE = 0;
    /** Chasing something to bite. */
    public static final int ERRAND_MELEE = 1;
    /** Walking to where the colony said to be. */
    public static final int ERRAND_MARCH = 2;

    private GlyphidBrain() {
    }

    /** Which errand a glyphid is on: biting outranks marching, and neither happens in the air. */
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

    /** Whether this is a tick a glyphid would get as far as choosing somewhere to path to. */
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

    /** Decide whether this is a tick to repath on, and if so where to. */
    private static GlyphidPlan advance(GlyphidSnapshot self, GlyphidMind mind, Vec3 destination,
                                       @Nullable Vec3 lookAt, boolean bite) {
        if (mind.chewing) {
            return new GlyphidPlan(GlyphidPlan.Move.CHEW, mind.chewX, mind.chewY, mind.chewZ, false,
                    destination, lookAt, bite, GlyphidPlan.KEEP_TASK, 0, 0.0);
        }
        Vec3 flow = self.flowStep();
        if (flow != null) {
            mind.sinceRepath++;
            boolean due = repathDue(mind, self.tickCount(), self.id(), true, self.stagger());
            int elapsed = 0;
            double moved = 0.0;
            if (due) {
                elapsed = mind.sinceRepath;
                mind.sinceRepath = 0;
                moved = closed(self.position(), destination, mind);
            }
            return new GlyphidPlan(GlyphidPlan.Move.FLOW, Mth.floor(flow.x), Mth.floor(flow.y),
                    Mth.floor(flow.z), false, destination, lookAt, bite, GlyphidPlan.KEEP_TASK, elapsed, moved);
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
        double moved = closed(pos, destination, mind);
        if (distance < 1.0) {
            // Standing on it. Counts as arriving, not as a failed search, so the backoff cannot punish it.
            settle(mind, true, moved, elapsed);
            return GlyphidPlan.hold(lookAt, bite);
        }

        double reach = Math.min(distance, HOP);
        int hopX = Mth.floor(pos.x + dx / distance * reach);
        int hopZ = Mth.floor(pos.z + dz / distance * reach);
        int hopY;
        boolean sampleY;
        if (distance <= HOP) {
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
     * Fold the outcome of a search back into the mind.
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

    /** Ground covered <em>towards the destination</em>, not ground covered. */
    private static double closed(Vec3 pos, Vec3 destination, GlyphidMind mind) {
        double dx = destination.x - pos.x;
        double dz = destination.z - pos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double closed = distance < 1.0E-4
                ? 0.0
                : ((pos.x - mind.lastX) * dx + (pos.z - mind.lastZ) * dz) / distance;
        mind.lastX = pos.x;
        mind.lastZ = pos.z;
        return closed;
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
