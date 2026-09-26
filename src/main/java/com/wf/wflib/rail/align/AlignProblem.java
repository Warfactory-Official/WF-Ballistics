package com.wf.wflib.rail.align;

/**
 * Something wrong with an alignment, attached to the PI that caused it.
 *
 * @param pointIndex index of the offending PI, or -1 for a problem with the line as a whole
 * @param severity whether this stops the line being built or merely makes it a bad line
 * @param message one sentence, phrased as what to do about it
 */
public record AlignProblem(int pointIndex, Severity severity, String message) {

    public enum Severity {
        /** The line cannot be built as drawn. */
        ERROR,
        /** The line can be built, but it is off-class and trains will suffer for it. */
        WARNING
    }

    public static AlignProblem error(int index, String message) {
        return new AlignProblem(index, Severity.ERROR, message);
    }

    public static AlignProblem warning(int index, String message) {
        return new AlignProblem(index, Severity.WARNING, message);
    }

    public boolean isError() {
        return this.severity == Severity.ERROR;
    }
}
