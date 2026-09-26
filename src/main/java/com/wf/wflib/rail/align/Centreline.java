package com.wf.wflib.rail.align;

import java.util.ArrayList;
import java.util.List;

/** A compiled horizontal alignment: the elements in order, and the chainage that addresses them. */
public record Centreline(List<AlignElement> elements, double length) {

    public static final Centreline EMPTY = new Centreline(List.of(), 0.0);

    public boolean isEmpty() {
        return this.elements.isEmpty();
    }

    /**
     * Position and heading at a distance along the line.
     *
     * <p>Chainage outside the line clamps to its ends rather than throwing. Callers are usually drawing
     * something, and a tooltip that reads the wrong end is better than an exception mid-frame.</p>
     */
    public AlignElement.Sample at(double chainage) {
        if (this.elements.isEmpty()) {
            return new AlignElement.Sample(0.0, 0.0, 0.0);
        }
        double s = Math.max(0.0, Math.min(chainage, this.length));
        for (AlignElement element : this.elements) {
            double len = element.length();
            if (s <= len) {
                return element.at(s);
            }
            s -= len;
        }
        AlignElement last = this.elements.get(this.elements.size() - 1);
        return last.at(last.length());
    }

    /**
     * The stretch between two chainages, as a centreline in its own right.
     *
     * <p>What a train building part of a route works on. A leg of a journey runs from one junction to
     * the next, which is rarely a whole survey, and everything downstream of here - the corridor, the
     * track pieces, the lining - measures from zero. Handing it a clipped line rather than a line and
     * a pair of offsets is what keeps that arithmetic in one place.</p>
     */
    public Centreline part(double from, double to) {
        double a = Math.max(0.0, Math.min(from, to));
        double b = Math.min(this.length, Math.max(from, to));
        if (b - a <= 0.0) {
            return EMPTY;
        }
        List<AlignElement> out = new ArrayList<>();
        double start = 0.0;
        for (AlignElement element : this.elements) {
            double len = element.length();
            if (len > 0.0) {
                double lo = Math.max(a, start) - start;
                double hi = Math.min(b, start + len) - start;
                if (hi - lo > 1.0E-9) {
                    out.add(element.part(lo, hi));
                }
            }
            start += len;
        }
        return of(out);
    }

    /**
     * The same line, measured from the other end.
     *
     * <p>A route is surveyed in one direction and travelled in both. Rather than every consumer of a
     * centreline learning to count backwards, a leg that runs against the survey is handed a line that
     * already does: chainage zero is where the train is, and the headings point the way it is going.</p>
     */
    public Centreline reversed() {
        List<AlignElement> out = new ArrayList<>(this.elements.size());
        for (int i = this.elements.size() - 1; i >= 0; i--) {
            out.add(this.elements.get(i).reversed());
        }
        return new Centreline(out, this.length);
    }

    /** A centreline from elements whose total length has to be measured rather than assumed. */
    public static Centreline of(List<AlignElement> elements) {
        double total = 0.0;
        for (AlignElement element : elements) {
            total += element.length();
        }
        return new Centreline(List.copyOf(elements), total);
    }

    /**
     * Sample the whole line at roughly {@code step} blocks.
     *
     * <p>Each element is divided into a whole number of equal parts, so a sample always lands exactly on
     * an element boundary. A fixed global step would drift past the boundaries and put a visible kink in
     * a drawn curve where an arc is a little shorter than the step.</p>
     */
    public List<AlignElement.Sample> sample(double step) {
        List<AlignElement.Sample> out = new ArrayList<>();
        if (this.elements.isEmpty()) {
            return out;
        }
        double chord = Math.max(0.5, step);
        out.add(this.elements.get(0).at(0.0));
        for (AlignElement element : this.elements) {
            double len = element.length();
            if (len <= 0.0) {
                continue;
            }
            int parts = Math.max(1, (int) Math.ceil(len / chord));
            for (int i = 1; i <= parts; i++) {
                out.add(element.at(len * i / parts));
            }
        }
        return out;
    }
}
