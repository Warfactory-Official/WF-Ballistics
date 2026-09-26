package com.wf.wflib.rail.demo;

import cam72cam.immersiverailroading.util.VecUtil;
import cam72cam.mod.math.Vec3d;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.build.ir.IrStock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puts trains on the finished network.
 *
 * <p>A hand car at every stop, because it is the one piece of stock in Immersive Railroading that needs
 * no fuel: era "manual", a horsepower of one, and a player to pump it. Somebody can get on at any stop
 * and find out whether the railway goes where the map says it does, which is a different and better test
 * than any number this build can print. The locomotive and its two wagons are there to be looked at.</p>
 */
public final class FivePointsStock {

    /** The hand car, matched on its path rather than its name, which a pack may translate. */
    private static final String HAND_CAR = "hand_car";

    /** What stands on the Trunk Line for the photograph, in order from the front. */
    private static final List<String> CONSIST = List.of("alco_rs1", "boxcar_x26", "dw_gondola");

    /** Blocks between the pieces of the parked consist. Not coupled, just standing in line. */
    private static final double SPACING = 22.0;

    /** How far along the Trunk Line the consist stands, clear of the stop at either end. */
    private static final double PARKED_AT = 60.0;

    private FivePointsStock() {
    }

    public record Placed(int stock, List<String> notes) {
    }

    /**
     * A hand car at every stop, and a consist on the Trunk Line.
     *
     * <p>Each hand car is faced along one of the two lines that serve its stop, so that getting on and
     * pushing forward goes somewhere rather than into a wall.</p>
     */
    public static Placed put(ServerLevel level, int floorY) {
        List<Alignment> routes = FivePointsFixture.drawn(level);
        List<String> notes = new ArrayList<>();
        String handCar = definition(HAND_CAR);
        int placed = 0;
        if (handCar == null) {
            notes.add("this install has no hand car, so the stops are empty");
        } else {
            for (FivePoints.Stop stop : FivePoints.stops()) {
                placed += atStop(level, routes, stop, floorY, handCar, notes) ? 1 : 0;
            }
        }
        placed += consist(level, routes, floorY, notes);
        return new Placed(placed, notes);
    }

    private static boolean atStop(ServerLevel level, List<Alignment> routes, FivePoints.Stop stop,
                                  int floorY, String handCar, List<String> notes) {
        for (Alignment route : routes) {
            Aim aim = aimAlong(route, stop);
            if (aim == null) {
                continue;
            }
            // Clear of the junction itself. The last stretch of a route that meets another one is the
            // junction's rather than the line's, so the first blocks out of a stop carry the connecting
            // curve, and that is the one place a piece of parked stock is in the way of the test.
            double off = com.wf.wflib.rail.RailConfig.TURNOUT_LEAD.get() + 8.0;
            Vec3 at = new Vec3(aim.x() + Math.cos(aim.heading()) * off, floorY,
                    aim.z() + Math.sin(aim.heading()) * off);
            IrStock.Placed result = IrStock.spawn(level, at, aim.yaw(), handCar);
            if (result.name() != null) {
                return true;
            }
            notes.add(stop.name() + ": " + result.problem());
            return false;
        }
        notes.add(stop.name() + ": no route drawn through it");
        return false;
    }

    private static int consist(ServerLevel level, List<Alignment> routes, int floorY,
                               List<String> notes) {
        Alignment trunk = null;
        for (Alignment route : routes) {
            if (route.name().equals("Trunk Line")) {
                trunk = route;
            }
        }
        if (trunk == null) {
            notes.add("no Trunk Line to park a train on");
            return 0;
        }
        Centreline line = trunk.compile().centreline();
        int placed = 0;
        for (int i = 0; i < CONSIST.size(); i++) {
            String id = definition(CONSIST.get(i));
            if (id == null) {
                notes.add("this install has no " + CONSIST.get(i));
                continue;
            }
            double chainage = PARKED_AT + i * SPACING;
            if (chainage > line.length()) {
                break;
            }
            AlignElement.Sample sample = line.at(chainage);
            float yaw = VecUtil.toWrongYaw(new Vec3d(Math.cos(sample.heading()), 0.0,
                    Math.sin(sample.heading())));
            IrStock.Placed result = IrStock.spawn(level,
                    new Vec3(sample.x(), floorY, sample.z()), yaw, id);
            if (result.name() != null) {
                placed++;
            } else {
                notes.add(String.format(Locale.ROOT, "Trunk Line at %.0f: %s", chainage,
                        result.problem()));
            }
        }
        return placed;
    }

    /** Where a stop is and which way a train leaves it along one route. */
    private record Aim(double x, double z, double heading, float yaw) {
    }

    private static Aim aimAlong(Alignment route, FivePoints.Stop stop) {
        Centreline line = route.compile().centreline();
        if (line.isEmpty() || route.points().isEmpty()) {
            return null;
        }
        FivePoints.Route drawn = FivePoints.route(route.name());
        if (drawn == null) {
            return null;
        }
        double originX = route.points().get(0).x() - drawn.points().get(0)[0];
        double originZ = route.points().get(0).z() - drawn.points().get(0)[1];
        double sx = originX + stop.x();
        double sz = originZ + stop.z();
        AlignElement.Sample start = line.at(0.0);
        AlignElement.Sample finish = line.at(line.length());
        double heading;
        if (Math.hypot(start.x() - sx, start.z() - sz) < 2.0) {
            heading = start.heading();
        } else if (Math.hypot(finish.x() - sx, finish.z() - sz) < 2.0) {
            heading = finish.heading() + Math.PI;
        } else {
            return null;
        }
        return new Aim(sx, sz, heading,
                VecUtil.toWrongYaw(new Vec3d(Math.cos(heading), 0.0, Math.sin(heading))));
    }

    /** The first definition this install has whose id carries this token. */
    private static String definition(String token) {
        for (String id : IrStock.names()) {
            if (id.contains(token)) {
                return id;
            }
        }
        return null;
    }
}
