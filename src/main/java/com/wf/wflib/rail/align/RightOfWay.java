package com.wf.wflib.rail.align;

import java.util.ArrayList;
import java.util.List;

/**
 * Whose land a route crosses, and where it cannot be built.
 *
 * <p>Held as runs along the line rather than as a set of chunks, because the line is one-dimensional
 * and so is the answer. A 20 km route crosses something like 1250 chunk columns and changes owner a
 * handful of times; sending 1250 entries to say that would be sending the wrong shape, and it is also
 * exactly what has to be drawn: a span of line, not a grid of squares.</p>
 */
public record RightOfWay(List<Span> spans, int chunksCrossed, int chunksBlocked) {

    public static final RightOfWay EMPTY = new RightOfWay(List.of(), 0, 0);

    /** Longest wire form. A route that changes hands more often than this is not a route. */
    public static final int MAX_SPANS = 256;

    /**
     * One run of the line under one owner with one verdict.
     *
     * @param from chainage where the run starts, in blocks
     * @param to chainage where it ends
     * @param ownerName the claiming faction, or empty for unclaimed ground
     * @param ownerColour that faction's colour as 0xRRGGBB, or a neutral grey when unclaimed
     * @param blocked whether the surveyor may not build here, which is the thing worth seeing
     */
    public record Span(double from, double to, String ownerName, int ownerColour, boolean blocked) {

        public double length() {
            return this.to - this.from;
        }
    }

    public boolean isEmpty() {
        return this.spans.isEmpty();
    }

    /** @return blocks of line the surveyor may not build on. */
    public double blockedLength() {
        double total = 0.0;
        for (Span span : this.spans) {
            if (span.blocked()) {
                total += span.length();
            }
        }
        return total;
    }

    /** @return how many distinct factions the route crosses, unclaimed ground excluded. */
    public int ownersCrossed() {
        List<String> seen = new ArrayList<>();
        for (Span span : this.spans) {
            if (!span.ownerName().isEmpty() && !seen.contains(span.ownerName())) {
                seen.add(span.ownerName());
            }
        }
        return seen.size();
    }

    /**
     * Fold a per-sample reading into runs.
     *
     * <p>Split where the owner or the verdict changes, and nowhere else. Kept out of the server class
     * so the folding can be tested on its own: it is the part with an off-by-one in it.</p>
     *
     * @param chainage where each sample sits along the line, ascending
     * @param owners the owning faction's name at each sample, empty for unclaimed
     * @param colours that faction's colour at each sample
     * @param blocked whether the surveyor is refused at each sample
     * @param total length of the whole line, so the last run reaches the end of it
     */
    public static RightOfWay fold(double[] chainage, String[] owners, int[] colours, boolean[] blocked,
                                  double total, int chunksCrossed, int chunksBlocked) {
        int count = chainage.length;
        if (count == 0) {
            return EMPTY;
        }
        List<Span> spans = new ArrayList<>();
        int runStart = 0;
        for (int i = 1; i <= count; i++) {
            boolean last = i == count;
            boolean changed = last
                    || !owners[i].equals(owners[runStart])
                    || blocked[i] != blocked[runStart];
            if (!changed) {
                continue;
            }
            // A run ends where the next sample begins, so the runs tile the line with no gaps. The
            // final one runs to the end of the line rather than to its last sample.
            double to = last ? total : chainage[i];
            if (spans.size() < MAX_SPANS) {
                spans.add(new Span(chainage[runStart], to, owners[runStart], colours[runStart],
                        blocked[runStart]));
            }
            runStart = i;
        }
        return new RightOfWay(List.copyOf(spans), chunksCrossed, chunksBlocked);
    }
}
