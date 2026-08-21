package com.wf.wfballistics.drone.squad;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where each member of a squad should sit relative to its leader. Pure geometry with no world access, so it
 * runs inside the off-thread squad job alongside the rest of the planning.
 *
 * <p>Index 0 is the leader and always resolves to a zero offset; subordinates take 1..n-1 in a stable order
 * so nobody swaps slots from tick to tick.
 */
public interface Formation {

    /**
     * How far apart neighbouring slots sit by default, in blocks: the squad's slack, and the one number
     * that decides how tightly it flies.
     *
     * <p>Wide on purpose. The old figure was six, which is only twice the width of the hull holding it, and
     * a formation with a hull's clearance either side has none to give: every turn swings the outer drones
     * across the gap, every gust of station-keeping error eats into it, and the drones spend the flight
     * shouldering each other out of the way instead of flying. The cost of opening it up is nothing, a
     * follower in its slot flies exactly the leader's velocity however far away that slot is, and what it
     * buys is room for all of the above to happen without anybody touching.
     *
     * <p>Used for both the lateral and the trailing step, so one number scales the whole shape and a squad
     * stays the same proportions however loose or tight it is ordered to fly.
     */
    double DEFAULT_SPACING = 12.0;

    /**
     * The range a configured spacing is held to. The floor is a little over the width of a hull, because a
     * formation packed tighter than the drones flying it is not a formation, it is a pile-up; the ceiling
     * keeps a flight recognisably a flight rather than several drones on unrelated errands.
     */
    double MIN_SPACING = 4.0;
    double MAX_SPACING = 96.0;

    /**
     * @param index   0-based slot; 0 is the leader
     * @param forward the leader's horizontal heading (unit length)
     * @param spacing distance between neighbouring slots, in blocks
     * @return the world position this slot should hold
     */
    Vec3 slot(int index, Vec3 leaderPos, Vec3 forward, double spacing);

    String id();

    /**
     * @return the horizontal right-hand vector for a heading.
     */
    static Vec3 right(Vec3 forward) {
        return new Vec3(forward.z, 0.0, -forward.x);
    }

    /**
     * @return the direction a formation is built along, for a yaw in radians.
     *
     * <p>A heading and never a velocity, and the difference is worth the method. A velocity is a
     * measurement, and at low speed almost all of what it measures is drift, so a frame built on one swings
     * whenever the flight is doing anything but travelling flat out, and takes the slots, fifteen blocks out
     * on the end of it, with it. A reference climbing vertically with three hundredths of a block a tick of
     * sideways wander once had its followers sweeping through eighteen blocks of arc chasing a formation
     * rotating underneath them.
     *
     * <p>A heading is the same direction of travel with all of that already taken out:
     * {@code Steering#faceTravel} rate limits how fast it may turn and holds it entirely below a floor. The
     * formation inherits that filtering for free, and there is no threshold here to cross or get wrong.
     */
    static Vec3 forward(float yaw) {
        return new Vec3(Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    /**
     * @return {@code spacing} brought inside {@link #MIN_SPACING}..{@link #MAX_SPACING}, falling back to
     * {@link #DEFAULT_SPACING} for a value that is not a number at all. Applied where a spacing enters the
     * system rather than where it is used, so nothing downstream has to wonder whether it was checked.
     */
    static double clampSpacing(double spacing) {
        return Double.isFinite(spacing) ? Mth.clamp(spacing, MIN_SPACING, MAX_SPACING) : DEFAULT_SPACING;
    }
}
