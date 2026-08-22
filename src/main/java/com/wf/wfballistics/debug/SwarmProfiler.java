package com.wf.wfballistics.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Per-tick cost accounting for a swarm, broken down by what the entities were doing.
 *
 * <p>Exists because "entities are slow" is not an actionable statement. A swarm's tick cost splits between
 * pathfinding, entity-vs-entity collision, movement physics and the goal layer, and which of those dominates
 * decides which optimisation is worth writing. This measures them separately so the answer comes from a
 * number rather than from an assumption.
 *
 * <p>Phases nest: {@link Phase#PATH} happens inside {@link Phase#AI}, which happens inside
 * {@link Phase#TICK}. The tree is declared in {@link #PARENT} and the report subtracts children from their
 * parent, so every line is exclusive of the lines indented under it and the residual on each parent is
 * labelled rather than silently dropped. A large residual means the cost is somewhere no call site has been
 * placed yet, which is itself worth knowing.
 *
 * <p>Written from the server thread only, and deliberately unsynchronised: a lock here would perturb the very
 * cost being measured. {@link #enabled} is checked before every timestamp, so the cost when profiling is off
 * is one predictable branch per call site.
 */
public final class SwarmProfiler {

    /**
     * Ticks kept for the windowed report: ten seconds at 20 tps. Long enough to ride out a GC pause without
     * the mean hiding it, since the p95 and max are reported alongside.
     */
    public static final int WINDOW = 200;

    /**
     * What a swarm spends its tick on.
     */
    public enum Phase {
        TICK("entity tick"),
        AI("ai step"),
        PATH("path search"),
        NAV("path following"),
        PUSH("collision push"),
        MOVE("movement"),
        DIG("digging");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Phase[] PHASES = Phase.values();
    private static final int PHASE_COUNT = PHASES.length;

    /**
     * Parent of each phase by ordinal, {@code -1} for the root. Declared here rather than in the enum
     * because a constant may not reference a sibling from its own constructor.
     */
    private static final int[] PARENT = {
            -1,                     // TICK
            Phase.TICK.ordinal(),   // AI
            Phase.AI.ordinal(),     // PATH
            Phase.AI.ordinal(),     // NAV
            Phase.TICK.ordinal(),   // PUSH
            Phase.TICK.ordinal(),   // MOVE
            Phase.TICK.ordinal(),   // DIG
    };

    /**
     * Nanos accumulated so far this tick, indexed by phase ordinal.
     */
    private static final long[] current = new long[PHASE_COUNT];
    /**
     * The last {@link #WINDOW} completed ticks, as a ring of per-phase nano totals.
     */
    private static final long[][] history = new long[WINDOW][PHASE_COUNT];
    /**
     * Population sampled on each completed tick, so cost can be reported per entity as well as per tick.
     */
    private static final int[] populations = new int[WINDOW];

    private static boolean enabled;
    private static int cursor;
    private static int filled;
    private static int population;

    private SwarmProfiler() {
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * Turn profiling on or off. Always clears the window: a report that straddled the switch would average
     * ticks that were measured against ticks that were not.
     */
    public static void setEnabled(boolean value) {
        enabled = value;
        reset();
    }

    public static void reset() {
        Arrays.fill(current, 0L);
        for (long[] tick : history) {
            Arrays.fill(tick, 0L);
        }
        Arrays.fill(populations, 0);
        cursor = 0;
        filled = 0;
        population = 0;
    }

    /**
     * @return a timestamp to hand back to {@link #end}, or 0 when profiling is off.
     */
    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    /**
     * Charge the time since {@code start} to a phase. Ignores a zero start so that toggling profiling on
     * midway through a tick cannot book the epoch as a phase cost.
     */
    public static void end(Phase phase, long start) {
        if (enabled && start != 0L) {
            current[phase.ordinal()] += System.nanoTime() - start;
        }
    }

    /**
     * Record how many swarm members were ticked. Called once per tick, before {@link #endTick()}.
     */
    public static void sample(int entities) {
        if (enabled) {
            population = entities;
        }
    }

    /**
     * Close the current tick and roll it into the window. Called from the level tick hook.
     */
    public static void endTick() {
        if (!enabled) {
            return;
        }
        System.arraycopy(current, 0, history[cursor], 0, PHASE_COUNT);
        populations[cursor] = population;
        cursor = (cursor + 1) % WINDOW;
        filled = Math.min(filled + 1, WINDOW);
        Arrays.fill(current, 0L);
        population = 0;
    }

    public static int samples() {
        return filled;
    }

    /**
     * @return the mean population over the window, or 0 with no samples.
     */
    public static double meanPopulation() {
        if (filled == 0) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < filled; i++) {
            total += populations[i];
        }
        return (double) total / filled;
    }

    /**
     * @return mean milliseconds per tick charged to a phase, inclusive of its children.
     */
    public static double meanMillis(Phase phase) {
        if (filled == 0) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < filled; i++) {
            total += history[i][phase.ordinal()];
        }
        return total / (double) filled / 1.0E6;
    }

    /**
     * @return mean milliseconds charged to a phase but to none of its children: the phase's own cost.
     */
    public static double exclusiveMillis(Phase phase) {
        double own = meanMillis(phase);
        for (Phase child : PHASES) {
            if (PARENT[child.ordinal()] == phase.ordinal()) {
                own -= meanMillis(child);
            }
        }
        return own;
    }

    /**
     * @return the 95th-percentile millisecond cost of a phase over the window. Reported alongside the mean
     * because a swarm that is fine on average and spikes past the 50 ms tick budget is not fine.
     */
    public static double p95Millis(Phase phase) {
        if (filled == 0) {
            return 0.0;
        }
        long[] sorted = new long[filled];
        for (int i = 0; i < filled; i++) {
            sorted[i] = history[i][phase.ordinal()];
        }
        Arrays.sort(sorted);
        return sorted[Math.min(filled - 1, (int) Math.ceil(filled * 0.95) - 1)] / 1.0E6;
    }

    public static double maxMillis(Phase phase) {
        if (filled == 0) {
            return 0.0;
        }
        long worst = 0L;
        for (int i = 0; i < filled; i++) {
            worst = Math.max(worst, history[i][phase.ordinal()]);
        }
        return worst / 1.0E6;
    }

    /**
     * @return the window as a tree of lines, each phase's children indented beneath it and the heaviest
     * child first.
     */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        if (filled == 0) {
            lines.add("No samples. Profiling " + (enabled ? "is on, but nothing has ticked yet." : "is off."));
            return lines;
        }
        double total = meanMillis(Phase.TICK);
        double mean = meanPopulation();
        lines.add(String.format(Locale.ROOT,
                "%d ticks, %.1f entities: %.3f ms/tick mean, %.3f p95, %.3f max%s",
                filled, mean, total, p95Millis(Phase.TICK), maxMillis(Phase.TICK),
                mean > 0.0 ? String.format(Locale.ROOT, " (%.1f us/entity)", total * 1000.0 / mean) : ""));
        append(lines, Phase.TICK, 0, total);
        return lines;
    }

    private static void append(List<String> lines, Phase phase, int depth, double total) {
        lines.add(line(phase.label(), depth, meanMillis(phase), p95Millis(phase), total));

        List<Phase> children = new ArrayList<>();
        for (Phase child : PHASES) {
            if (PARENT[child.ordinal()] == phase.ordinal()) {
                children.add(child);
            }
        }
        if (children.isEmpty()) {
            return;
        }
        children.sort((a, b) -> Double.compare(meanMillis(b), meanMillis(a)));
        for (Phase child : children) {
            append(lines, child, depth + 1, total);
        }
        // Whatever the parent spent outside any child. Named rather than dropped: a large residual means
        // the dominant cost has no call site yet, not that the phase is cheap.
        lines.add(line("(unattributed)", depth + 1, exclusiveMillis(phase), Double.NaN, total));
    }

    private static String line(String label, int depth, double millis, double p95, double total) {
        String indented = "  ".repeat(depth + 1) + label;
        String share = total > 0.0 ? String.format(Locale.ROOT, "%5.1f%%", millis / total * 100.0) : "    -";
        String tail = Double.isNaN(p95) ? "" : String.format(Locale.ROOT, "  p95 %.3f", p95);
        return String.format(Locale.ROOT, "%-26s %8.3f ms  %s%s", indented, millis, share, tail);
    }
}
