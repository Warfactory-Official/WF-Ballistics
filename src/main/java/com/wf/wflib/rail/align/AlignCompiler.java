package com.wf.wflib.rail.align;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a chain of PIs and radii into a centreline of straights and arcs.
 *
 * <p>The classic tangent-and-curve construction. At each interior PI the deflection angle between the
 * incoming and outgoing straights is measured, and the curve of the chosen radius is set back from the
 * PI along both legs by the tangent length {@code T = R tan(d/2)}. The straights then run between
 * consecutive curve ends rather than between the PIs themselves.</p>
 *
 * <p>No Minecraft types on purpose: this is the part most worth testing, and it tests in milliseconds
 * with nothing loaded.</p>
 */
public final class AlignCompiler {

    /**
     * Deflections at or beyond this are treated as doubling back. {@code tan(d/2)} runs away to infinity
     * at 180 degrees, so a PI this sharp produces a tangent length no leg can ever contain, and clamping
     * it would silently draw something nothing like what was asked for.
     */
    private static final double MAX_DEFLECTION = Math.toRadians(170.0);

    /** Below this, the two legs are in line and there is nothing to curve. */
    private static final double MIN_DEFLECTION = Math.toRadians(0.05);

    private AlignCompiler() {
    }

    public static AlignResult compile(List<AlignPoint> points, DesignClass designClass) {
        List<AlignProblem> problems = new ArrayList<>();
        if (points == null || points.size() < 2) {
            return new AlignResult(Centreline.EMPTY, problems);
        }

        int count = points.size();
        double[] deflection = new double[count];
        double[] turn = new double[count];
        double[] tangentLength = new double[count];
        double[] radius = new double[count];

        for (int i = 1; i < count - 1; i++) {
            AlignPoint prev = points.get(i - 1);
            AlignPoint here = points.get(i);
            AlignPoint next = points.get(i + 1);

            double inHeading = Math.atan2(here.z() - prev.z(), here.x() - prev.x());
            double outHeading = Math.atan2(next.z() - here.z(), next.x() - here.x());
            double signed = wrap(outHeading - inHeading);
            double d = Math.abs(signed);

            deflection[i] = d;
            turn[i] = Math.signum(signed);

            if (d < MIN_DEFLECTION) {
                continue;
            }
            if (d >= MAX_DEFLECTION) {
                problems.add(AlignProblem.error(i, String.format(
                        "the line doubles back %.0f degrees here; no curve can take that, so move this point",
                        Math.toDegrees(d))));
                continue;
            }

            double r = here.radius();
            if (r <= 0.0) {
                problems.add(AlignProblem.error(i, "this corner has no radius, so nothing can run through it"));
                continue;
            }
            if (r < designClass.minRadius()) {
                problems.add(AlignProblem.warning(i, String.format(
                        "radius %.0f is tighter than the %.0f this class allows", r, designClass.minRadius())));
            }
            radius[i] = r;
            tangentLength[i] = r * Math.tan(d / 2.0);
        }

        clampOverruns(points, deflection, tangentLength, radius, problems);

        return new AlignResult(build(points, deflection, turn, tangentLength, radius), problems);
    }

    /**
     * Shrink curves that do not fit on their leg.
     *
     * <p>Two adjacent curves share the straight between them, so a leg is only long enough if it can
     * hold both tangent lengths. When it cannot, both are scaled down to fit rather than one being
     * given priority: an alignment where dragging a point silently reshapes a curve two PIs away is
     * much harder to reason about than one where the corner you made too tight is the corner that
     * visibly gives.</p>
     */
    private static void clampOverruns(List<AlignPoint> points, double[] deflection, double[] tangentLength,
                                      double[] radius, List<AlignProblem> problems) {
        for (int i = 0; i < points.size() - 1; i++) {
            double leg = points.get(i).distanceTo(points.get(i + 1));
            double needed = tangentLength[i] + tangentLength[i + 1];
            if (needed <= leg || needed <= 0.0) {
                continue;
            }
            double scale = leg / needed;
            for (int at : new int[]{i, i + 1}) {
                if (tangentLength[at] <= 0.0) {
                    continue;
                }
                tangentLength[at] *= scale;
                // Report the radius actually drawn, not the one asked for, or the message contradicts
                // the picture.
                radius[at] = tangentLength[at] / Math.tan(deflection[at] / 2.0);
                problems.add(AlignProblem.error(at, String.format(
                        "this curve overruns the next one; it will not fit above radius %.0f", radius[at])));
            }
        }
    }

    private static Centreline build(List<AlignPoint> points, double[] deflection, double[] turn,
                                    double[] tangentLength, double[] radius) {
        List<AlignElement> elements = new ArrayList<>();
        int count = points.size();

        double cursorX = points.get(0).x();
        double cursorZ = points.get(0).z();
        double total = 0.0;

        for (int i = 1; i < count - 1; i++) {
            if (tangentLength[i] <= 0.0 || radius[i] <= 0.0) {
                continue;
            }
            AlignPoint prev = points.get(i - 1);
            AlignPoint here = points.get(i);
            AlignPoint next = points.get(i + 1);

            double inX = here.x() - prev.x();
            double inZ = here.z() - prev.z();
            double inLen = Math.hypot(inX, inZ);
            double outX = next.x() - here.x();
            double outZ = next.z() - here.z();
            double outLen = Math.hypot(outX, outZ);
            if (inLen <= 0.0 || outLen <= 0.0) {
                continue;
            }
            inX /= inLen;
            inZ /= inLen;
            outX /= outLen;
            outZ /= outLen;

            double startX = here.x() - inX * tangentLength[i];
            double startZ = here.z() - inZ * tangentLength[i];

            AlignElement.Tangent straight = new AlignElement.Tangent(cursorX, cursorZ, startX, startZ);
            if (straight.length() > 1.0e-6) {
                elements.add(straight);
                total += straight.length();
            }

            // The centre is a radius off the curve's start, square to the incoming heading, on the
            // inside of the turn.
            double sign = turn[i];
            double centreX = startX - inZ * sign * radius[i];
            double centreZ = startZ + inX * sign * radius[i];
            double startAngle = Math.atan2(startZ - centreZ, startX - centreX);

            AlignElement.Curve curve = new AlignElement.Curve(centreX, centreZ, radius[i], startAngle,
                    sign * deflection[i]);
            elements.add(curve);
            total += curve.length();

            AlignElement.Sample end = curve.at(curve.length());
            cursorX = end.x();
            cursorZ = end.z();
        }

        AlignPoint last = points.get(count - 1);
        AlignElement.Tangent run = new AlignElement.Tangent(cursorX, cursorZ, last.x(), last.z());
        if (run.length() > 1.0e-6) {
            elements.add(run);
            total += run.length();
        }

        return new Centreline(List.copyOf(elements), total);
    }

    /** Fold an angle difference into (-pi, pi], so a turn past due west does not read as a turn the other way. */
    private static double wrap(double angle) {
        double a = angle;
        while (a <= -Math.PI) {
            a += 2.0 * Math.PI;
        }
        while (a > Math.PI) {
            a -= 2.0 * Math.PI;
        }
        return a;
    }
}
