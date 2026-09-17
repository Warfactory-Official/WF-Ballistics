package com.wf.wfballistics.mine;

import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * The whole condition for a mine going off, as one lambda: given the mine and something near it, does that thing
 * set it off.
 */
@FunctionalInterface
public interface MineTrigger {

    /**
     * @param candidate something alive-and-not-a-mine inside the mine's trigger box
     * @return true to set the mine off
     */
    boolean test(MineEntity mine, Entity candidate);

    /** The most specific class every match is an instance of, for the entity lookup to filter on. */
    default Class<? extends Entity> narrow() {
        return Entity.class;
    }

    default MineTrigger and(MineTrigger other) {
        MineTrigger self = this;
        Class<? extends Entity> narrow = narrower(self.narrow(), other.narrow());
        return new MineTrigger() {
            @Override
            public boolean test(MineEntity mine, Entity candidate) {
                return self.test(mine, candidate) && other.test(mine, candidate);
            }

            @Override
            public Class<? extends Entity> narrow() {
                return narrow;
            }
        };
    }

    default MineTrigger or(MineTrigger other) {
        MineTrigger self = this;
        Class<? extends Entity> narrow = commonAncestor(self.narrow(), other.narrow());
        return new MineTrigger() {
            @Override
            public boolean test(MineEntity mine, Entity candidate) {
                return self.test(mine, candidate) || other.test(mine, candidate);
            }

            @Override
            public Class<? extends Entity> narrow() {
                return narrow;
            }
        };
    }

    /** Negation drops the narrowing, since what a class excludes is everything else. */
    default MineTrigger negate() {
        MineTrigger self = this;
        return (mine, candidate) -> !self.test(mine, candidate);
    }

    /** Anything the mine is offered at all. */
    static MineTrigger always() {
        return (mine, candidate) -> true;
    }

    static MineTrigger never() {
        return (mine, candidate) -> false;
    }

    /** Lifts an entity matcher (see {@link MineTarget}) into a trigger, narrowing included. */
    static MineTrigger matching(MineTarget target) {
        return new MineTrigger() {
            @Override
            public boolean test(MineEntity mine, Entity candidate) {
                return target.test(candidate);
            }

            @Override
            public Class<? extends Entity> narrow() {
                return target.narrow();
            }
        };
    }

    static MineTrigger ofClass(Class<? extends Entity> type) {
        return matching(MineTarget.ofClass(type));
    }

    static MineTrigger tagged(TagKey<EntityType<?>> tag) {
        return matching(MineTarget.tagged(tag));
    }

    /**
     * Inside the mine's own trigger range, measured to the mine's middle, and inside the shorter {@link
     * MineEntity.Builder#sneakTriggerRange} instead when the candidate is crouching, which is what makes creeping
     * up on one work without making it immunity.
     */
    static MineTrigger inRange() {
        return (mine, candidate) -> {
            double range = candidate.isCrouching() ? mine.getSneakTriggerRange() : mine.getTriggerRange();
            return withinSq(mine, candidate, range * range);
        };
    }

    /** Inside a fixed radius, ignoring what the mine was configured with. */
    static MineTrigger within(double blocks) {
        double sq = blocks * blocks;
        return (mine, candidate) -> withinSq(mine, candidate, sq);
    }

    /** Inside the mine's {@link MineEntity.Builder#arc}, centred on its facing. */
    static MineTrigger inArc() {
        return (mine, candidate) -> {
            double arc = mine.getArc();
            if (arc >= 360.0) {
                return true;
            }
            Vec3 facing = mine.facing();
            double dx = candidate.getX() - mine.getX();
            double dz = candidate.getZ() - mine.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < 1.0E-4) {
                return true; // standing on it
            }
            return (dx * facing.x + dz * facing.z) / horizontal
                    >= Math.cos(Math.toRadians(arc * 0.5));
        };
    }

    /**
     * What a mine uses unless told otherwise: something worth going off for, close enough, and in front of it if it
     * has a front.
     */
    static MineTrigger standard(MineTarget target) {
        return matching(target).and(inRange())
                .and(inArc());
    }

    private static boolean withinSq(MineEntity mine, Entity candidate, double rangeSq) {
        return candidate.distanceToSqr(mine.getX(), mine.getY() + mine.getBbHeight() * 0.5, mine.getZ())
                <= rangeSq;
    }

    /** The narrower of two classes, for an AND. */
    private static Class<? extends Entity> narrower(Class<? extends Entity> a, Class<? extends Entity> b) {
        return a.isAssignableFrom(b) ? b : a;
    }

    /** The nearest class both are, for an OR, which has to admit either side. */
    private static Class<? extends Entity> commonAncestor(Class<? extends Entity> a,
                                                          Class<? extends Entity> b) {
        Class<?> common = a;
        while (!common.isAssignableFrom(b)) {
            common = common.getSuperclass();
        }
        return common.asSubclass(Entity.class);
    }
}
