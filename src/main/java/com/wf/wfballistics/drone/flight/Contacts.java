package com.wf.wfballistics.drone.flight;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What happens when two airframes end up in the same piece of sky.
 *
 * <p>Drones fly with block physics switched off: the hull is three blocks across, so vanilla collision
 * would wedge one into the first hillside it parked on and refuse to let it climb out (see
 * {@code DroneEntity}'s constructor). That buys the terrain handling its freedom at the cost of the drone
 * being a ghost to everything, including other drones, so hull-to-hull contact is put back here explicitly
 * rather than inherited.
 *
 * <p>This is the <em>last</em> of three layers, and it should almost never be the one doing the work. The
 * formation puts drones in slots that are already further apart than they are wide; {@code Cruising}'s
 * separation rule pushes them off each other before they touch. Both of those are steering: they ask a
 * drone to go somewhere else and the airframe takes its time about it, so neither can promise anything.
 * This one is geometry applied after the fact, and it is what keeps two hulls out of the same place.
 *
 * <p><b>It corrects position and nothing else.</b> Not the velocity, and not the movement on its way to
 * being taken, and that restraint is the whole design, arrived at by getting it wrong first. The obvious
 * implementation is the rigid one: sweep the movement so it stops against the other hull, then cancel the
 * component of velocity driving into it. On a body that can stop quickly that is right, and on this one it
 * is a trap. A quadcopter here needs some thirty-five blocks to shed cruise speed, so it cannot avoid a
 * contact it is a metre away from: it is going to arrive whatever it wants. Blocking the movement
 * therefore does not model a collision, it simply deletes the tick; and cancelling the velocity writes that
 * deletion into {@code getDeltaMovement}, which is the same field the brain reads back next tick as the
 * drone's flight state. Between them the two clear the aircraft's motion, every tick, for as long as the
 * hulls touch. Drones that met while converging on one point stopped dead in mid-air at exactly zero speed
 * and stayed there for good, because the flight they needed in order to leave was being erased faster than
 * the airframe could rebuild it.
 *
 * <p>Pushing them apart instead leaves the flight untouched. Two drones asked to be in the same place come
 * to rest against one another, which is the honest answer to an impossible order, and the instant guidance
 * points either of them somewhere reachable it simply goes.
 *
 * <p>Pure arithmetic on boxes, with no world access, so it can be asserted directly by {@code DroneSelfTest}.
 */
public final class Contacts {

    /**
     * The share of an overlap a drone clears on its own account.
     *
     * <p>Half, because the drone it is inside of is running the same arithmetic from the other side on its
     * own tick and clearing the other half. Resolving the whole of it from each side would separate a pair
     * twice as fast as either of them is actually overlapping and fling them apart; half from each converges
     * on contact instead of bouncing off it. It also means a pair only half-resolves when one of them is not
     * being ticked, which is the right answer: the one that is still flying gets out of the way.
     */
    public static final double SHARE = 0.5;

    /**
     * Cap on how far one tick of contact may move a drone.
     *
     * <p>Two figures bracket this. It has to beat half of a cruise speed, because that is the pull a pair of
     * drones ordered onto the same point hold against each other and each of them only clears its own half:
     * under that they would grind together forever instead of parting. And it has to stay well inside a
     * tick's flight, or being touched would fling a drone further than flying does and the contact would
     * read as a teleport rather than a shove.
     */
    public static final double MAX_ESCAPE = 0.5;

    private Contacts() {
    }

    /**
     * @param tieBreakPositive which way to go when the two are exactly concentric and the geometry has no
     *                         opinion. The caller passes opposite values to the two drones, comparing their
     *                         entity ids does it, so they part instead of both choosing the same way and
     *                         travelling as a stack for the rest of the flight.
     * @return the shortest displacement that takes {@code self} clear of {@code other}, or zero if they are
     * not overlapping in the first place.
     *
     * <p>Out along the axis of least penetration rather than along the line between the two centres, which
     * matters because a drone is a wide flat plate and not a ball: one sitting just above another overlaps
     * by a hand's breadth vertically and by the full three blocks horizontally, and the centre line would
     * shove it sideways across the formation when all it needed was to rise a few inches.
     */
    public static Vec3 escape(AABB self, AABB other, boolean tieBreakPositive) {
        double x = Math.min(self.maxX, other.maxX) - Math.max(self.minX, other.minX);
        double y = Math.min(self.maxY, other.maxY) - Math.max(self.minY, other.minY);
        double z = Math.min(self.maxZ, other.maxZ) - Math.max(self.minZ, other.minZ);
        if (x <= 0.0 || y <= 0.0 || z <= 0.0) {
            return Vec3.ZERO;
        }
        if (y <= x && y <= z) {
            return new Vec3(0.0, y * away(self.minY + self.maxY, other.minY + other.maxY, tieBreakPositive), 0.0);
        }
        if (x <= z) {
            return new Vec3(x * away(self.minX + self.maxX, other.minX + other.maxX, tieBreakPositive), 0.0, 0.0);
        }
        return new Vec3(0.0, 0.0, z * away(self.minZ + self.maxZ, other.minZ + other.maxZ, tieBreakPositive));
    }

    /**
     * @return which way along an axis is away from {@code other}, from the two centres: doubled, since only
     * their difference is wanted and halving both would change nothing.
     */
    private static double away(double selfCentre, double otherCentre, boolean tieBreakPositive) {
        double delta = selfCentre - otherCentre;
        if (Math.abs(delta) < 1.0E-9) {
            return tieBreakPositive ? 1.0 : -1.0;
        }
        return delta > 0.0 ? 1.0 : -1.0;
    }

    /**
     * @return {@code escape} shortened to one tick's worth of movement: this drone's share of it, and never
     * more than {@link #MAX_ESCAPE}.
     */
    public static Vec3 step(Vec3 escape) {
        double length = escape.length();
        if (length < 1.0E-9) {
            return Vec3.ZERO;
        }
        return escape.scale(Math.min(SHARE, MAX_ESCAPE / length));
    }

}
