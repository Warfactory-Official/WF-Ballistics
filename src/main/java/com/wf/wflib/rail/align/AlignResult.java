package com.wf.wflib.rail.align;

import java.util.List;

/**
 * What compiling an alignment produced: the geometry, and everything wrong with it.
 *
 * <p>Both, always. The compiler never throws and never refuses to produce a centreline, because during
 * an edit the alignment is invalid most of the time: a PI being dragged spends every intermediate frame
 * somewhere impossible. Drawing nothing until the player lets go would make the tool unusable, so an
 * over-tight corner is clamped to what will fit, drawn, and reported.</p>
 *
 * @param centreline the geometry, as close to what was asked for as could be built
 * @param problems what was wrong, in PI order
 */
public record AlignResult(Centreline centreline, List<AlignProblem> problems) {

    public static final AlignResult EMPTY = new AlignResult(Centreline.EMPTY, List.of());

    /** Whether this alignment could be built as drawn. */
    public boolean buildable() {
        return this.problems.stream().noneMatch(AlignProblem::isError);
    }

    public List<AlignProblem> errors() {
        return this.problems.stream().filter(AlignProblem::isError).toList();
    }

    /** The worst problem attached to one PI, or null when that PI is fine. */
    public AlignProblem worstAt(int pointIndex) {
        AlignProblem worst = null;
        for (AlignProblem problem : this.problems) {
            if (problem.pointIndex() != pointIndex) {
                continue;
            }
            if (worst == null || (problem.isError() && !worst.isError())) {
                worst = problem;
            }
        }
        return worst;
    }
}
