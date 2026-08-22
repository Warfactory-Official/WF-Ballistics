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
 * {@link Phase#TICK}, and {@link Phase#COLLIDE} inside {@link Phase#MOVE}. The movement phases exist because
 * once collision push was fixed, movement became the largest single line in the report, and "movement" is not
 * an actionable answer either: vanilla's {@code move} is a collision sweep, a block-volume walk and a handful
 * of block-state lookups, and only measuring says which of them the swarm is actually paying for.
 * The tree is declared in {@link #PARENT} and the report subtracts children from their
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
        DIG("digging"),
        COLLIDE("collision sweep"),
        ENTCOL("entity collisions"),
        INSIDE("blocks inside"),
        SCAN("fire scan"),
        PATH_MELEE("- for melee"),
        PATH_MARCH("- for the march"),
        BASE("base tick"),
        FLUID("fluid push"),
        // The two below are a second cut of PATH -- by what it is doing rather than by who asked. They sit
        // outside the tree, because a phase can only be subtracted from its parent once and the by-caller
        // split already accounts for all of PATH.
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

    /**
     * Things worth counting rather than timing.
     *
     * <p>Milliseconds say a phase is expensive; they never say <em>why</em>. A tick that spends twice as long
     * pathfinding either ran twice as many searches or ran searches that were twice as deep, and those are
     * different bugs with different fixes.
     */
    public enum Counter {
        SEARCHES("path searches"),
        NODES("nodes expanded"),
        BYTES("bytes allocated"),
        BLOCK_READS("block reads");

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
            Phase.AI.ordinal(),     // DIG -- reached from customServerAiStep and from a goal, both inside
                                    // serverAiStep, so it is a sibling of the navigation phases, not of them.
            Phase.MOVE.ordinal(),   // COLLIDE
            Phase.COLLIDE.ordinal(),// ENTCOL
            Phase.MOVE.ordinal(),   // INSIDE
            Phase.MOVE.ordinal(),   // SCAN
            Phase.PATH.ordinal(),   // PATH_MELEE
            Phase.PATH.ordinal(),   // PATH_MARCH
            Phase.TICK.ordinal(),   // BASE
            Phase.BASE.ordinal(),   // FLUID
            -1,                     // PATH_ASTAR -- reported in its own section, see searchReport()
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
     * @return nanos charged to a phase so far this tick, for callers that have to net out a nested phase.
     */
    public static long accrued(Phase phase) {
        return current[phase.ordinal()];
    }

    /**
     * Which goal, if any, is currently on the stack and should be blamed for any path search underneath it.
     *
     * <p>A static because the search happens several frames down, inside the navigator, on an object that has
     * no idea which goal asked. Same thread-safety story as the rest of this class: server thread only, and
     * only ever set while profiling is on.
     */
    private static Phase caller;

    /**
     * Claim responsibility for whatever searching happens until {@link #exitCaller}. Returns the previous
     * claimant, which the caller must hand back — goals nest.
     */
    public static Phase enterCaller(Phase phase) {
        Phase previous = caller;
        caller = phase;
        return previous;
    }

    public static void exitCaller(Phase previous) {
        caller = previous;
    }

    /**
     * True while a search a glyphid asked for is on the stack.
     *
     * <p>The pathfinder's internals are shared by every mob on the server and have no idea whose search they
     * are running, so the gate has to be opened from the one place that does know.
     */
    private static boolean searching;

    public static void setSearching(boolean value) {
        searching = value;
    }

    public static boolean searching() {
        return enabled && searching;
    }

    /**
     * Charge a path search, to {@link Phase#PATH} and to whichever goal claimed it.
     *
     * <p>Separate from {@link #end} because "pathfinding is a third of the tick" stopped being useful the
     * moment it was true: what decides whether that is a bug or a cost is <em>who keeps asking</em>.
     */
    public static void endPath(long start) {
        if (enabled && start != 0L) {
            long elapsed = System.nanoTime() - start;
            current[Phase.PATH.ordinal()] += elapsed;
            if (caller != null) {
                current[caller.ordinal()] += elapsed;
            }
        }
    }

    /**
     * Charge the time since {@code start} to a phase, minus whatever {@code nested} accrued in the meantime.
     *
     * <p>For phases that contain a sibling. Path searching happens both inside path following, when the
     * navigator recomputes, and outside it, when a goal asks for a route directly — so the two cannot simply
     * be parent and child. Netting the overlap out here keeps every reported line exclusive, which is the
     * difference between "following a path costs this much" and a number that silently includes the search.
     */
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
        searchReport(lines);
        spikeReport(lines);
        return lines;
    }

    /**
     * What a path search actually spends itself on, as opposed to who asked for it.
     *
     * <p>Split three ways because they fail differently. Node expansion is block-state reads through a chunk
     * snapshot — memory-bound, and the only part that scales with how much terrain the search has to look at.
     * The rest of the A* is heap operations and node bookkeeping. Setup is per-search overhead paid whether
     * the search visits one node or five hundred, which is what makes cheap failed searches expensive.
     */
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
        }
    }

    /**
     * What is different about the ticks that hurt.
     *
     * <p>A mean hides the thing players actually feel. Comparing the worst 5% of ticks against the rest says
     * whether a spike is <em>more work</em> — searches clumping onto one tick, which staggering fixes — or
     * <em>harder work</em>, searches that each visit far more nodes, which staggering cannot fix.
     */
    private static void spikeReport(List<String> lines) {
        int spikes = Math.max(1, filled / 20);
        Integer[] order = new Integer[filled];
        for (int i = 0; i < filled; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(history[b][Phase.TICK.ordinal()], history[a][Phase.TICK.ordinal()]));

        lines.add("");
        lines.add(String.format(Locale.ROOT, "worst %d ticks vs the other %d", spikes, filled - spikes));
        lines.add(String.format(Locale.ROOT, "  %-18s %10s %10s %8s", "", "worst", "rest", "ratio"));
        spikeLine(lines, order, spikes, "tick ms", i -> history[i][Phase.TICK.ordinal()] / 1.0E6);
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
