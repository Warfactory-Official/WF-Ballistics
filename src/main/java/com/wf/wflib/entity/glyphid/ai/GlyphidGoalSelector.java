package com.wf.wflib.entity.glyphid.ai;

import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.util.EnumSet;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The same goal arbitration vanilla does, over an array instead of a linked hash set. */
public final class GlyphidGoalSelector extends GoalSelector {

    /** Cached: {@code values()} clones its array, and this is read per goal per update. */
    private static final Goal.Flag[] FLAGS = Goal.Flag.values();

    private WrappedGoal[] goals = new WrappedGoal[0];
    private int[] flagMasks = new int[0];
    /** Which goal holds each flag, by ordinal. Vanilla's {@code lockedFlags} EnumMap, as an array. */
    private final WrappedGoal[] holders = new WrappedGoal[FLAGS.length];
    private int disabledMask;
    private boolean stale = true;

    public GlyphidGoalSelector(Supplier<ProfilerFiller> profiler) {
        super(profiler);
    }

    // --- mutation: keep the flat copy in step with the set the superclass owns ---

    @Override
    public void addGoal(int priority, Goal goal) {
        super.addGoal(priority, goal);
        stale = true;
    }

    @Override
    public void removeGoal(Goal goal) {
        super.removeGoal(goal);
        stale = true;
    }

    @Override
    public void removeAllGoals(Predicate<Goal> filter) {
        super.removeAllGoals(filter);
        stale = true;
    }

    @Override
    public void disableControlFlag(Goal.Flag flag) {
        super.disableControlFlag(flag);
        disabledMask |= 1 << flag.ordinal();
    }

    @Override
    public void enableControlFlag(Goal.Flag flag) {
        super.enableControlFlag(flag);
        disabledMask &= ~(1 << flag.ordinal());
    }

    // --- the update ---

    @Override
    public void tick() {
        WrappedGoal[] list = refresh();
        int[] masks = flagMasks;
        int off = disabledMask;

        // Cancel: anything running that has lost its flags or no longer wants to run.
        for (int i = 0; i < list.length; i++) {
            WrappedGoal goal = list[i];
            if (goal.isRunning() && ((masks[i] & off) != 0 || !goal.canContinueToUse())) {
                goal.stop();
            }
        }
        for (int f = 0; f < holders.length; f++) {
            WrappedGoal held = holders[f];
            if (held != null && !held.isRunning()) {
                holders[f] = null;
            }
        }

        // Start: anything idle that can take every flag it needs off whoever holds it.
        for (int i = 0; i < list.length; i++) {
            WrappedGoal goal = list[i];
            int mask = masks[i];
            if (goal.isRunning() || (mask & off) != 0 || !canTakeFlags(mask, goal) || !goal.canUse()) {
                continue;
            }
            for (int f = 0; f < holders.length; f++) {
                if ((mask & (1 << f)) == 0) {
                    continue;
                }
                WrappedGoal held = holders[f];
                if (held != null) {
                    held.stop();
                }
                holders[f] = goal;
            }
            goal.start();
        }

        tickRunningGoals(true);
    }

    @Override
    public void tickRunningGoals(boolean tickAllRunning) {
        for (WrappedGoal goal : refresh()) {
            if (goal.isRunning() && (tickAllRunning || goal.requiresUpdateEveryTick())) {
                goal.tick();
            }
        }
    }

    /** Vanilla's {@code goalCanBeReplacedForAllFlags}. */
    private boolean canTakeFlags(int mask, WrappedGoal candidate) {
        for (int f = 0; f < holders.length; f++) {
            if ((mask & (1 << f)) == 0) {
                continue;
            }
            WrappedGoal held = holders[f];
            if (held != null && !held.canBeReplacedBy(candidate)) {
                return false;
            }
        }
        return true;
    }

    /** Read each goal's flags into a bitmask, once per change to the goal list. */
    private static void readFlags(WrappedGoal[] list, int[] masks) {
        for (int i = 0; i < list.length; i++) {
            EnumSet<Goal.Flag> flags = list[i].getFlags();
            int mask = 0;
            for (int f = 0; f < FLAGS.length; f++) {
                if (flags.contains(FLAGS[f])) {
                    mask |= 1 << f;
                }
            }
            masks[i] = mask;
        }
    }

    private WrappedGoal[] refresh() {
        if (stale) {
            goals = getAvailableGoals().toArray(new WrappedGoal[0]);
            flagMasks = new int[goals.length];
            readFlags(goals, flagMasks);
            stale = false;
        }
        return goals;
    }
}
