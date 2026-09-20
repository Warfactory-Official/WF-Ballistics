package com.wf.wflib.drone.ai;

import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.PowerProfile;
import com.wf.wflib.drone.flight.Airframe;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Turns remaining charge into a decision. */
public final class PowerPolicy {

    /**
     * Charge never spent on cruising: the allowance for a controlled descent and touchdown.
     */
    public static final double LANDING_RESERVE = 120.0;
    /** Hovering allowance for stopping over the drop, settling, and releasing the crate. */
    public static final int DELIVERY_HOVER_TICKS = 100;
    /**
     * Charge a loitering drone keeps back on top of the run home and the landing reserve, so it breaks station with
     * something in hand rather than at the exact moment the sums stop working.
     */
    public static final double STATION_MARGIN = 90.0;

    private PowerPolicy() {
    }

    /**
     * @return the state the drone must switch to because of its battery, or null to let its handler decide.
     */
    @Nullable
    public static DroneState override(DroneSnapshot self) {
        if (self.state() == DroneState.DOWNED) {
            return null;
        }
        if (self.charge() <= 0.0) {
            return self.state().airborne() ? DroneState.DEPLETED : null;
        }
        if (!self.state().powered()) {
            return null;
        }
        return switch (self.state()) {
            case TRANSIT -> transitOverride(self);
            case EXFIL -> canReach(self, self.exfil()) ? null : DroneState.LANDING;
            case SURVEIL -> canHoldStation(self) ? null : DroneState.EXFIL;
            default -> null;
        };
    }

    /**
     * @return true if the drone can afford to stay on station a little longer.
     */
    public static boolean canHoldStation(DroneSnapshot self) {
        double home = self.power().costToTravel(self.airframe(), self.horizontalDistanceTo(self.exfil()),
                self.cruiseSpeed(), self.hasCargo());
        return self.charge() >= home + LANDING_RESERVE + STATION_MARGIN;
    }

    /**
     * Outbound with a crate: deliver if the delivery is still affordable, otherwise turn back while there is enough
     * charge to make the exfil point, otherwise put down where we are.
     */
    private static DroneState transitOverride(DroneSnapshot self) {
        if (canDeliver(self)) {
            return null;
        }
        return canReach(self, self.exfil()) ? DroneState.EXFIL : DroneState.LANDING;
    }

    /**
     * @return true if the drone can still reach the destination and hover long enough to release the crate.
     */
    public static boolean canDeliver(DroneSnapshot self) {
        return self.charge() >= costToDeliver(self) + LANDING_RESERVE;
    }

    /**
     * @return true if the drone can reach {@code target} and still land.
     */
    public static boolean canReach(DroneSnapshot self, Vec3 target) {
        double cost = self.power().costToTravel(self.airframe(), self.horizontalDistanceTo(target),
                self.cruiseSpeed(), self.hasCargo());
        return self.charge() >= cost + LANDING_RESERVE;
    }

    /**
     * @return true if, after delivering, the drone could still fly home. False means a one-way trip: it
     *      delivers and parks at the destination.
     */
    public static boolean canReturnAfterDelivery(DroneSnapshot self) {
        double home = self.destination().distanceTo(self.exfil());
        double cost = costToDeliver(self)
                + self.power().costToTravel(self.airframe(), home, self.cruiseSpeed(), false);
        return self.charge() >= cost + LANDING_RESERVE;
    }

    private static double costToDeliver(DroneSnapshot self) {
        double travel = self.power().costToTravel(self.airframe(),
                self.horizontalDistanceTo(self.destination()), self.cruiseSpeed(), self.hasCargo());
        return travel + self.power().costToHover(DELIVERY_HOVER_TICKS, self.hasCargo());
    }

    /**
     * Pre-flight check for the dispatcher: can a drone with {@code charge} fly this mission at all? A drone that
     * cannot even reach the destination is never launched.
     *
     * @return the shortfall in charge units, or 0 when the mission is affordable
     */
    public static double shortfall(double charge, PowerProfile power, Airframe frame, double speed, Vec3 start,
                                   Vec3 destination, Vec3 exfil, boolean hasCargo) {
        return shortfallForRoute(charge, power, frame, speed, start.distanceTo(destination), hasCargo);
    }

    /**
     * The same check against a route that is not a straight line.
     *
     * @param outbound total distance actually flown to reach the destination, staging waypoints included
     */
    public static double shortfallForRoute(double charge, PowerProfile power, Airframe frame, double speed,
                                           double outbound, boolean hasCargo) {
        double out = power.costToTravel(frame, outbound, speed, hasCargo)
                + power.costToHover(DELIVERY_HOVER_TICKS, hasCargo);
        return Math.max(0.0, out + LANDING_RESERVE - charge);
    }

    /**
     * @param outbound distance out, staging waypoints included
     * @param back distance home, staging waypoints included
     */
    public static boolean canRoundTripForRoute(double charge, PowerProfile power, Airframe frame, double speed,
                                               double outbound, double back, boolean hasCargo) {
        double out = power.costToTravel(frame, outbound, speed, hasCargo)
                + power.costToHover(DELIVERY_HOVER_TICKS, hasCargo);
        return charge >= out + power.costToTravel(frame, back, speed, false) + LANDING_RESERVE;
    }

    /**
     * @return true if the whole round trip (out, deliver, home) fits in {@code charge}.
     */
    public static boolean canRoundTrip(double charge, PowerProfile power, Airframe frame, double speed,
                                       Vec3 start, Vec3 destination, Vec3 exfil, boolean hasCargo) {
        double out = power.costToTravel(frame, start.distanceTo(destination), speed, hasCargo)
                + power.costToHover(DELIVERY_HOVER_TICKS, hasCargo);
        double back = power.costToTravel(frame, destination.distanceTo(exfil), speed, false);
        return charge >= out + back + LANDING_RESERVE;
    }
}
