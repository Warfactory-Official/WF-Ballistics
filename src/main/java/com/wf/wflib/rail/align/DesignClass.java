package com.wf.wflib.rail.align;

/**
 * What kind of line this is. Chosen once, and everything else defaults from it.
 *
 * <p>The clearance is deliberately the same number the bore will use as its tunnel profile, so a tunnel
 * cannot end up too small for the stock the line was designed for. That was an open worry in RAIL-PLAN
 * §5.2 and this is the answer to it: the profile is a property of the line, not of the machine.</p>
 */
public enum DesignClass {

    /** Tight, slow, and allowed to be steep. Throats, spurs, anything inside a yard. */
    YARD(30.0, 0.05, 5, 6),
    /** The default. */
    BRANCH(80.0, 0.03, 6, 7),
    /** Fast and expensive: wide curves, shallow grades, a big hole. */
    MAIN(200.0, 0.015, 8, 8);

    private final double minRadius;
    private final double maxGrade;
    private final int clearanceWidth;
    private final int clearanceHeight;

    DesignClass(double minRadius, double maxGrade, int clearanceWidth, int clearanceHeight) {
        this.minRadius = minRadius;
        this.maxGrade = maxGrade;
        this.clearanceWidth = clearanceWidth;
        this.clearanceHeight = clearanceHeight;
    }

    /** Tightest curve this class allows, in blocks. */
    public double minRadius() {
        return this.minRadius;
    }

    /** Steepest grade this class allows, as a rise over run fraction. */
    public double maxGrade() {
        return this.maxGrade;
    }

    /** Width of the cleared envelope, in blocks. */
    public int clearanceWidth() {
        return this.clearanceWidth;
    }

    /** Height of the cleared envelope above rail level, in blocks. */
    public int clearanceHeight() {
        return this.clearanceHeight;
    }

    /** The radius a new PI gets before anyone touches it: comfortably above the floor, not at it. */
    public double defaultRadius() {
        return this.minRadius * 1.5;
    }
}
