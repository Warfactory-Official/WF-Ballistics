package com.wf.wfballistics.debug;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Wraps a goal so that any pathfinding it triggers is charged to it by name.
 *
 * <p>Path search was a third of a swarm's tick with no way to tell which goal was spending it. The search
 * happens several frames below the goal, inside the navigator, on an object that does not know who asked —
 * so the goal has to say so on the way in. A decorator rather than a mixin because the goals are ours to
 * register, and this way any goal can be put under the microscope by wrapping it at the call site.
 *
 * <p>Wrapping is transparent: flags are copied from the delegate, so the goal selector's mutual-exclusion
 * still behaves exactly as it did unwrapped.
 */
public final class ProfiledGoal extends Goal {

    private final Goal delegate;
    private final SwarmProfiler.Phase bucket;

    public ProfiledGoal(Goal delegate, SwarmProfiler.Phase bucket) {
        this.delegate = delegate;
        this.bucket = bucket;
        setFlags(delegate.getFlags());
    }

    @Override
    public void setFlags(EnumSet<Flag> flagSet) {
        super.setFlags(flagSet);
    }

    @Override
    public boolean canUse() {
        if (!SwarmProfiler.enabled()) {
            return delegate.canUse();
        }
        SwarmProfiler.Phase previous = SwarmProfiler.enterCaller(bucket);
        try {
            return delegate.canUse();
        } finally {
            SwarmProfiler.exitCaller(previous);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!SwarmProfiler.enabled()) {
            return delegate.canContinueToUse();
        }
        SwarmProfiler.Phase previous = SwarmProfiler.enterCaller(bucket);
        try {
            return delegate.canContinueToUse();
        } finally {
            SwarmProfiler.exitCaller(previous);
        }
    }

    @Override
    public void tick() {
        if (!SwarmProfiler.enabled()) {
            delegate.tick();
            return;
        }
        SwarmProfiler.Phase previous = SwarmProfiler.enterCaller(bucket);
        try {
            delegate.tick();
        } finally {
            SwarmProfiler.exitCaller(previous);
        }
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public boolean isInterruptable() {
        return delegate.isInterruptable();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return delegate.requiresUpdateEveryTick();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
