package com.wf.wflib.rail.align;

import java.util.ArrayList;
import java.util.List;

/**
 * Which parts of a route actually have track on them.
 *
 * <p>Held as runs of chainage for the same reason {@link RightOfWay} is: a railway is built in sections,
 * from both ends and from a railhead in the middle, and "the first 3 km and the last 800 m" is two
 * numbers a side rather than a list of every block. It is also exactly the shape that has to be drawn.</p>
 *
 * <p>Chainage rather than world positions, because the route can be re-aligned under its own track. When
 * a PI moves, the built length stays where it is along the line, which is what a surveyor means when they
 * say the first 3 km are done: they are done from the start, wherever the start now runs.</p>
 */
public record BuildProgress(List<Span> spans) {

    public static final BuildProgress NONE = new BuildProgress(List.of());

    /**
     * Most runs one route may carry.
     *
     * <p>A wire and save bound, not a design limit: a route built forward from one railhead is one run.
     * Over the cap the smallest <em>gaps</em> are closed rather than the smallest runs dropped, so a
     * shattered line keeps its overall shape and loses the detail of individual craters. A route in more
     * than sixty-four pieces is rubble, and the map saying so approximately is the right answer.</p>
     */
    public static final int MAX_SPANS = 64;

    /** Runs closer together than this are one run. Half a block is below anything worth drawing. */
    public static final double EPSILON = 0.5;

    /**
     * One run of track.
     *
     * @param from chainage where it starts, in blocks
     * @param to chainage where it ends
     */
    public record Span(double from, double to) {

        public double length() {
            return this.to - this.from;
        }

        public boolean contains(double chainage) {
            return chainage >= this.from && chainage <= this.to;
        }
    }

    public BuildProgress {
        spans = normalise(spans);
    }

    public boolean isEmpty() {
        return this.spans.isEmpty();
    }

    /** Record track laid between two chainages, merging it into what is already there. */
    public BuildProgress with(double from, double to) {
        double lo = Math.min(from, to);
        double hi = Math.max(from, to);
        if (hi - lo <= 0.0) {
            return this;
        }
        List<Span> next = new ArrayList<>(this.spans);
        next.add(new Span(lo, hi));
        return new BuildProgress(next);
    }

    /** Record track gone between two chainages, splitting a run that straddles it. */
    public BuildProgress without(double from, double to) {
        double lo = Math.min(from, to);
        double hi = Math.max(from, to);
        if (hi - lo <= 0.0 || this.spans.isEmpty()) {
            return this;
        }
        List<Span> next = new ArrayList<>();
        for (Span span : this.spans) {
            if (span.to() <= lo || span.from() >= hi) {
                next.add(span);
                continue;
            }
            if (span.from() < lo) {
                next.add(new Span(span.from(), lo));
            }
            if (span.to() > hi) {
                next.add(new Span(hi, span.to()));
            }
        }
        return new BuildProgress(next);
    }

    /** @return blocks of route with track on them. */
    public double builtLength() {
        double total = 0.0;
        for (Span span : this.spans) {
            total += span.length();
        }
        return total;
    }

    /** @return how much of a route of this length is built, 0 to 1. Zero for a route of no length. */
    public double fractionOf(double total) {
        if (total <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, builtLength() / total);
    }

    public boolean isBuiltAt(double chainage) {
        for (Span span : this.spans) {
            if (span.contains(chainage)) {
                return true;
            }
        }
        return false;
    }

    /** Whether track runs the whole way, give or take the merge tolerance at each end. */
    public boolean covers(double total) {
        return total > 0.0 && this.spans.size() == 1
                && this.spans.get(0).from() <= EPSILON
                && this.spans.get(0).to() >= total - EPSILON;
    }

    /** The runs with no track on them, which is what a plan still owes. */
    public List<Span> gaps(double total) {
        List<Span> out = new ArrayList<>();
        double at = 0.0;
        for (Span span : this.spans) {
            if (span.from() > at + EPSILON) {
                out.add(new Span(at, Math.min(span.from(), total)));
            }
            at = Math.max(at, span.to());
            if (at >= total) {
                break;
            }
        }
        if (at < total - EPSILON) {
            out.add(new Span(at, total));
        }
        return out;
    }

    /** Clip to a route that has become shorter, so a re-alignment cannot leave track past the end. */
    public BuildProgress clampTo(double total) {
        if (total <= 0.0) {
            return NONE;
        }
        List<Span> next = new ArrayList<>();
        for (Span span : this.spans) {
            if (span.from() >= total) {
                continue;
            }
            next.add(span.to() <= total ? span : new Span(span.from(), total));
        }
        return next.size() == this.spans.size() && next.equals(this.spans) ? this : new BuildProgress(next);
    }

    // -- normalising ------------------------------------------------------------------------------

    private static List<Span> normalise(List<Span> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<Span> sorted = new ArrayList<>(input.size());
        for (Span span : input) {
            if (span != null && span.length() > 0.0) {
                sorted.add(new Span(Math.max(0.0, span.from()), Math.max(0.0, span.to())));
            }
        }
        sorted.sort((a, b) -> Double.compare(a.from(), b.from()));

        List<Span> merged = new ArrayList<>(sorted.size());
        for (Span span : sorted) {
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            Span last = merged.get(merged.size() - 1);
            if (span.from() <= last.to() + EPSILON) {
                merged.set(merged.size() - 1, new Span(last.from(), Math.max(last.to(), span.to())));
            } else {
                merged.add(span);
            }
        }
        return List.copyOf(closeSmallestGaps(merged));
    }

    /** Over the cap, close the narrowest gap repeatedly until it fits. */
    private static List<Span> closeSmallestGaps(List<Span> spans) {
        List<Span> out = new ArrayList<>(spans);
        while (out.size() > MAX_SPANS) {
            int narrowest = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < out.size() - 1; i++) {
                double gap = out.get(i + 1).from() - out.get(i).to();
                if (gap < best) {
                    best = gap;
                    narrowest = i;
                }
            }
            Span a = out.get(narrowest);
            Span b = out.remove(narrowest + 1);
            out.set(narrowest, new Span(a.from(), Math.max(a.to(), b.to())));
        }
        return out;
    }
}
