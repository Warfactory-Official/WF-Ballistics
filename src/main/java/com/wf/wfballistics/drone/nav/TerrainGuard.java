package com.wf.wfballistics.drone.nav;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The reflex that keeps a drone out of the ground, sitting underneath the planned route rather than instead
 * of it.
 *
 * <p>{@link PathPlanner} decides where to fly and is good at it, but it plans occasionally, over a coarse
 * grid, from terrain that was sampled some ticks ago. This runs every tick against a fresh look at the ground
 * immediately ahead, and it is what actually guarantees the drone does not hit anything: given a floor it
 * must be above, it will climb at up to {@link #CLIMB_BOOST} times the drone's normal rate to get there, and
 * give up ground speed while it does. Trading forward speed for climb is the right instinct when something is
 * rising in front of you, and it is also why a drone that has to climb burns battery faster, since the
 * throttle to do it comes out of the same flight model as everything else.
 *
 * <p>Kept in terms of plain numbers rather than a snapshot so the navigation package stays independent of the
 * AI's own types.
 */
public final class TerrainGuard {

    /**
     * Height above the terrain the guard insists on. Lower than {@link PathPlanner#CLEARANCE} on purpose: the
     * planned route should be what a drone normally flies, and this should only bite when the route is
     * missing, stale, or wrong.
     */
    public static final double MIN_CLEARANCE = 4.0;
    /**
     * How much harder than its nominal rate a drone will climb when it is below where it needs to be.
     */
    public static final double CLIMB_BOOST = 1.5;
    /**
     * How much ground speed it gives up to do that.
     */
    public static final double SPEED_GIVEUP = 0.7;

    private TerrainGuard() {
    }

    /**
     * @param desired   the velocity the guidance layer asked for
     * @param y         the drone's current height
     * @param floor     the height it must be at, from {@link DroneNavigation#requiredAltitude}
     * @param climbRate its nominal climb rate
     * @return the velocity to actually fly, unchanged if the drone is already high enough
     */
    public static Vec3 enforce(Vec3 desired, double y, double floor, double climbRate) {
        double deficit = floor - y;
        if (deficit <= 0.0) {
            return desired;
        }
        double urgency = Mth.clamp(deficit / MIN_CLEARANCE, 0.0, 1.0);
        double climb = climbRate * (1.0 + urgency * CLIMB_BOOST);
        double brake = 1.0 - urgency * SPEED_GIVEUP;
        return new Vec3(desired.x * brake, Math.max(desired.y, climb), desired.z * brake);
    }
}
