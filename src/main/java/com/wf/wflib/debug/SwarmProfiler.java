package com.wf.wflib.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Per-tick cost accounting for a swarm, broken down by what the entities were doing. */
public final class SwarmProfiler {

    /** Ticks kept for the windowed report: ten seconds at 20 tps. */
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
        DIG("digging"),
        COLLIDE("collision sweep"),
        ENTCOL("entity collisions"),
        INSIDE("blocks inside"),
        SCAN("fire scan"),
        PATH_MELEE("- for melee"),
        PATH_MARCH("- for the march"),
        BASE("base tick"),
        FLUID("fluid push"),
        // Level passes rather than per-entity ones, so they are not inside TICK and cannot hang off it.
        SEPARATE("separation"),
        FLOW("flow field build"),
        SIM("sim tier"),
        SIM_WAIT("- waiting for the pass"),
        SQUAD("squad split"),
        SIM_ASYNC("sim tier pass"),
        PATH_ASTAR("the A* itself"),
        PATH_NEIGHBORS("node expansion");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Things worth counting rather than timing. */
    public enum Counter {
        SEARCHES("path searches"),
        NODES("nodes expanded"),
        BYTES("bytes allocated"),
        BLOCK_READS("block reads"),
        PATH_HIT("shared paths reused"),
        PATH_MISS("paths searched"),
        TYPE_QUERY("path-type queries"),
        TYPE_MISS("path-type misses");

        private final String label;

        Counter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Phase[] PHASES = Phase.values();
    private static final int PHASE_COUNT = PHASES.length;

    /** Parent of each phase by ordinal, {@code -1} for the root. */
    private static final int[] PARENT = {
            -1,                     // TICK
            Phase.TICK.ordinal(),   // AI
            Phase.AI.ordinal(),     // PATH
            Phase.AI.ordinal(),     // NAV
            Phase.TICK.ordinal(),   // PUSH
            Phase.TICK.ordinal(),   // MOVE
            Phase.AI.ordinal(),     // DIG: reached from customServerAiStep and from a goal, both inside
                                    // serverAiStep, so it is a sibling of the navigation phases, not of them.
            Phase.MOVE.ordinal(),   // COLLIDE
            Phase.COLLIDE.ordinal(),// ENTCOL
            Phase.MOVE.ordinal(),   // INSIDE
            Phase.MOVE.ordinal(),   // SCAN
            Phase.PATH.ordinal(),   // PATH_MELEE
            Phase.PATH.ordinal(),   // PATH_MARCH
            Phase.TICK.ordinal(),   // BASE
            Phase.BASE.ordinal(),   // FLUID
            -1,                     // SEPARATE (a level pass, reported beside the tree rather than in it
            -1,                     // FLOW), likewise
            -1,                     // SIM (likewise
            Phase.SIM.ordinal(),    // SIM_WAIT) the part of SIM that is the world thread standing still
            -1,                     // SQUAD (likewise
            -1,                     // SIM_ASYNC), not the world thread's at all, see the enum
            -1,                     // PATH_ASTAR: reported in its own section, see searchReport()
            -1,                     // PATH_NEIGHBORS
    };

    private static final Counter[] COUNTERS = Counter.values();
    private static final int COUNTER_COUNT = COUNTERS.length;

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

    private static final long[] currentCounts = new long[COUNTER_COUNT];
    private static final long[][] countHistory = new long[WINDOW][COUNTER_COUNT];

    private static boolean enabled;
    private static int cursor;
    private static int filled;
    private static int population;

    private SwarmProfiler() {
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Turn profiling on or off. */
    public static void setEnabled(boolean value) {
        enabled = value;
        reset();
    }

    public static void reset() {
        Arrays.fill(current, 0L);
        for (long[] tick : history) {
            Arrays.fill(tick, 0L);
        }
        Arrays.fill(currentCounts, 0L);
        for (long[] tick : countHistory) {
            Arrays.fill(tick, 0L);
        }
        Arrays.fill(populations, 0);
        cursor = 0;
        filled = 0;
        population = 0;
    }

    /**
     * Add to a per-tick counter.
     */
    public static void count(Counter counter, long amount) {
        if (enabled) {
            currentCounts[counter.ordinal()] += amount;
        }
    }

    /**
     * @return a timestamp to hand back to {@link #end}, or 0 when profiling is off.
     */
    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** Charge the time since {@code start} to a phase. */
    public static void end(Phase phase, long start) {
        if (enabled && start != 0L) {
            current[phase.ordinal()] += System.nanoTime() - start;
        }
    }

    /** Charge nanos measured somewhere else to a phase. */
    public static void charge(Phase phase, long nanos) {
        if (enabled && nanos > 0L) {
            current[phase.ordinal()] += nanos;
        }
    }

    /**
     * @return nanos charged to a phase so far this tick, for callers that have to net out a nested phase.
     */
    public static long accrued(Phase phase) {
        return current[phase.ordinal()];
    }

    /** Which goal, if any, is currently on the stack and should be blamed for any path search underneath it. */
    private static Phase caller;

    /** Claim responsibility for whatever searching happens until {@link #exitCaller}. */
    public static Phase enterCaller(Phase phase) {
        Phase previous = caller;
        caller = phase;
        return previous;
    }

    public static void exitCaller(Phase previous) {
        caller = previous;
    }

    /** True while a search a glyphid asked for is on the stack. */
    private static boolean searching;

    public static void setSearching(boolean value) {
        searching = value;
    }

    public static boolean searching() {
        return enabled && searching;
    }

    /** Charge a path search, to {@link Phase#PATH} and to whichever goal claimed it. */
    public static void endPath(long start) {
        if (enabled && start != 0L) {
            long elapsed = System.nanoTime() - start;
            current[Phase.PATH.ordinal()] += elapsed;
            if (caller != null) {
                current[caller.ordinal()] += elapsed;
            }
        }
    }

    /** Charge the time since {@code start} to a phase, minus whatever {@code nested} accrued in the meantime. */
    public static void endExcluding(Phase phase, long start, Phase nested, long nestedBefore) {
        if (enabled && start != 0L) {
            long overlap = current[nested.ordinal()] - nestedBefore;
            current[phase.ordinal()] += System.nanoTime() - start - overlap;
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
        System.arraycopy(currentCounts, 0, countHistory[cursor], 0, COUNTER_COUNT);
        populations[cursor] = population;
        cursor = (cursor + 1) % WINDOW;
        filled = Math.min(filled + 1, WINDOW);
        Arrays.fill(current, 0L);
        Arrays.fill(currentCounts, 0L);
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
     *      because a swarm that is fine on average and spikes past the 50 ms tick budget is not fine.
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

    /**
     * The phases that make up a tick of swarm, whichever tier paid for them: the entity tick plus the level passes
     * that are not inside anybody's tick.
     */
    private static final Phase[] SWARM =
            {Phase.TICK, Phase.SEPARATE, Phase.FLOW, Phase.SIM, Phase.SQUAD};

    private static long swarmNanos(int sample) {
        long total = 0L;
        for (Phase phase : SWARM) {
            total += history[sample][phase.ordinal()];
        }
        return total;
    }

    public static double swarmMillis() {
        if (filled == 0) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < filled; i++) {
            total += swarmNanos(i);
        }
        return total / (double) filled / 1.0E6;
    }

    private static double swarmPercentile(double quantile) {
        if (filled == 0) {
            return 0.0;
        }
        long[] sorted = new long[filled];
        for (int i = 0; i < filled; i++) {
            sorted[i] = swarmNanos(i);
        }
        Arrays.sort(sorted);
        return sorted[Math.min(filled - 1, (int) Math.ceil(filled * quantile) - 1)] / 1.0E6;
    }

    private static double swarmMax() {
        long worst = 0L;
        for (int i = 0; i < filled; i++) {
            worst = Math.max(worst, swarmNanos(i));
        }
        return worst / 1.0E6;
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
     *      child first.
     */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        if (filled == 0) {
            lines.add("No samples. Profiling " + (enabled ? "is on, but nothing has ticked yet." : "is off."));
            return lines;
        }
        double total = swarmMillis();
        double mean = meanPopulation();
        lines.add(String.format(Locale.ROOT,
                "%d ticks, %.1f glyphids: %.3f ms/tick mean, %.3f p95, %.3f max%s",
                filled, mean, total, swarmPercentile(0.95), swarmMax(),
                mean > 0.0 ? String.format(Locale.ROOT, " (%.1f us/glyphid)", total * 1000.0 / mean) : ""));
        append(lines, Phase.TICK, 0, total);
        for (Phase pass : new Phase[]{Phase.SEPARATE, Phase.FLOW, Phase.SIM, Phase.SQUAD}) {
            double millis = meanMillis(pass);
            if (millis > 0.0) {
                lines.add(line(pass.label() + " (level pass)", 0, millis, p95Millis(pass), total));
                if (pass == Phase.SIM && meanMillis(Phase.SIM_WAIT) > 0.0) {
                    lines.add(line(Phase.SIM_WAIT.label(), 1, meanMillis(Phase.SIM_WAIT),
                            p95Millis(Phase.SIM_WAIT), total));
                }
            }
        }
        offThreadReport(lines);
        searchReport(lines);
        spikeReport(lines);
        return lines;
    }

    /**
     * What the swarm costs somewhere other than the world thread, and how much of that the world thread ended up
     * paying for anyway.
     */
    private static void offThreadReport(List<String> lines) {
        double async = meanMillis(Phase.SIM_ASYNC);
        if (async <= 0.0) {
            return;
        }
        double waited = meanMillis(Phase.SIM_WAIT);
        lines.add("");
        lines.add(String.format(Locale.ROOT, "off the world thread (not in the total above)"));
        lines.add(String.format(Locale.ROOT, "  %-24s %8.3f ms  p95 %.3f, max %.3f",
                Phase.SIM_ASYNC.label(), async, p95Millis(Phase.SIM_ASYNC), maxMillis(Phase.SIM_ASYNC)));
        lines.add(String.format(Locale.ROOT, "  %-24s %8.3f ms  %.0f%% of it hidden behind the vanilla tick",
                "world thread waited", waited, 100.0 * (1.0 - Math.min(1.0, waited / async))));
    }

    /** What a path search actually spends itself on, as opposed to who asked for it. */
    private static void searchReport(List<String> lines) {
        double path = meanMillis(Phase.PATH);
        if (path <= 0.0) {
            return;
        }
        double astar = meanMillis(Phase.PATH_ASTAR);
        double neighbors = meanMillis(Phase.PATH_NEIGHBORS);
        double searches = meanCount(Counter.SEARCHES);
        double nodes = meanCount(Counter.NODES);

        lines.add("");
        lines.add(String.format(Locale.ROOT, "inside a path search (%.1f searches/tick, %.0f nodes/tick)",
                searches, nodes));
        lines.add(line("setup + chunk snapshot", 0, path - astar, Double.NaN, path));
        lines.add(line("the A* itself", 0, astar, Double.NaN, path));
        lines.add(line("- node expansion", 1, neighbors, Double.NaN, path));
        lines.add(line("- heap + bookkeeping", 1, astar - neighbors, Double.NaN, path));
        if (searches > 0.0) {
            lines.add(String.format(Locale.ROOT, "  %.0f us per search, %.1f nodes per search%s",
                    path * 1000.0 / searches, nodes / searches,
                    nodes > 0.0 ? String.format(Locale.ROOT, ", %.0f ns per node expanded",
                            neighbors * 1.0E6 / nodes) : ""));
            double reads = meanCount(Counter.BLOCK_READS);
            if (reads > 0.0) {
                lines.add(String.format(Locale.ROOT,
                        "  %.0f block reads per search, %.1f per node, %.1f ns per read",
                        reads / searches, nodes > 0.0 ? reads / nodes : 0.0, neighbors * 1.0E6 / reads));
            }
            double queries = meanCount(Counter.TYPE_QUERY);
            if (queries > 0.0) {
                double misses = meanCount(Counter.TYPE_MISS);
                lines.add(String.format(Locale.ROOT,
                        "  %.1f path-type queries per node, %.0f%% hit; they explain %.0f%% of block reads",
                        nodes > 0.0 ? queries / nodes : 0.0, 100.0 * (queries - misses) / queries,
                        reads > 0.0 ? 100.0 * misses / reads : 0.0));
            }
        }
    }

    /** What is different about the ticks that hurt. */
    private static void spikeReport(List<String> lines) {
        int spikes = Math.max(1, filled / 20);
        Integer[] order = new Integer[filled];
        for (int i = 0; i < filled; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(swarmNanos(b), swarmNanos(a)));

        lines.add("");
        lines.add(String.format(Locale.ROOT, "worst %d ticks vs the other %d", spikes, filled - spikes));
        lines.add(String.format(Locale.ROOT, "  %-18s %10s %10s %8s", "", "worst", "rest", "ratio"));
        spikeLine(lines, order, spikes, "tick ms", i -> swarmNanos(i) / 1.0E6);
        spikeLine(lines, order, spikes, "path search ms", i -> history[i][Phase.PATH.ordinal()] / 1.0E6);
        for (Counter counter : COUNTERS) {
            int ordinal = counter.ordinal();
            spikeLine(lines, order, spikes, counter.label(), i -> (double) countHistory[i][ordinal]);
        }
    }

    private static void spikeLine(List<String> lines, Integer[] order, int spikes, String label,
                                  java.util.function.IntToDoubleFunction value) {
        double worst = 0.0;
        for (int i = 0; i < spikes; i++) {
            worst += value.applyAsDouble(order[i]);
        }
        worst /= spikes;
        double rest = 0.0;
        int restCount = filled - spikes;
        for (int i = spikes; i < filled; i++) {
            rest += value.applyAsDouble(order[i]);
        }
        rest = restCount > 0 ? rest / restCount : 0.0;
        lines.add(String.format(Locale.ROOT, "  %-18s %10.1f %10.1f %7.1fx",
                label, worst, rest, rest > 0.0 ? worst / rest : 0.0));
    }

    public static double meanCount(Counter counter) {
        if (filled == 0) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < filled; i++) {
            total += countHistory[i][counter.ordinal()];
        }
        return (double) total / filled;
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
        lines.add(line("(unattributed)", depth + 1, exclusiveMillis(phase), Double.NaN, total));
    }

    private static String line(String label, int depth, double millis, double p95, double total) {
        String indented = "  ".repeat(depth + 1) + label;
        String share = total > 0.0 ? String.format(Locale.ROOT, "%5.1f%%", millis / total * 100.0) : "    -";
        String tail = Double.isNaN(p95) ? "" : String.format(Locale.ROOT, "  p95 %.3f", p95);
        return String.format(Locale.ROOT, "%-26s %8.3f ms  %s%s", indented, millis, share, tail);
    }
}
