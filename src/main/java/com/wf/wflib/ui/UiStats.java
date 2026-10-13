package com.wf.wflib.ui;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;

/**
 * HUD + {@link UiSurfaces} wall time and bytes allocated on the render thread per stage, averaged and logged every
 * 600 frames that drew; {@code /wfui stats} or {@code -Dwflib.ui.stats=true}. Off => one branch per mark.
 */
public final class UiStats {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WINDOW = 600;
    static final int COLLECT = 0;
    static final int BEGIN = 1;
    static final int EVAL = 2;
    static final int MEASURE = 3;
    static final int DRAW = 4;
    static final int END = 5;
    private static final String[] NAMES = {"collect", "begin", "eval", "measure", "draw", "end"};

    private static boolean enabled = Boolean.getBoolean("wflib.ui.stats");
    /** Per-signal allocation attribution ({@code /wfui stats detail}); its own bookkeeping allocates. */
    static boolean detail;
    private static final java.util.Map<Signal<?>, long[]> SIGNAL_BYTES = new java.util.IdentityHashMap<>();
    private static boolean active;
    private static long markNanos;
    private static long markBytes;
    private static final long[] nanos = new long[NAMES.length];
    private static final long[] bytes = new long[NAMES.length];
    private static int frames;
    private static long lastFrame = -1;
    private static final double[] last = {Double.NaN, Double.NaN};

    private UiStats() {
    }

    public static boolean toggle(boolean detailed) {
        enabled = !enabled;
        detail = enabled && detailed;
        SIGNAL_BYTES.clear();
        java.util.Arrays.fill(nanos, 0);
        java.util.Arrays.fill(bytes, 0);
        frames = 0;
        return enabled;
    }

    /** Last window's averages, NaN before the first window: {us/frame, B/frame}. */
    public static double[] last() {
        return last.clone();
    }

    static long allocated() {
        return THREADS.getCurrentThreadAllocatedBytes();
    }

    static void signal(Signal<?> signal, long before) {
        long bytes = THREADS.getCurrentThreadAllocatedBytes() - before;
        if (bytes > 0) {
            SIGNAL_BYTES.computeIfAbsent(signal, k -> new long[1])[0] += bytes;
        }
    }

    static void begin() {
        if (!enabled) {
            return;
        }
        active = true;
        markBytes = THREADS.getCurrentThreadAllocatedBytes();
        markNanos = System.nanoTime();
    }

    /** Charges everything since the previous mark to {@code stage}. No-op outside a HUD layer / surface. */
    static void mark(int stage) {
        if (!active) {
            return;
        }
        long now = System.nanoTime();
        long allocated = THREADS.getCurrentThreadAllocatedBytes();
        nanos[stage] += now - markNanos;
        bytes[stage] += allocated - markBytes;
        markBytes = THREADS.getCurrentThreadAllocatedBytes();
        markNanos = System.nanoTime();
    }

    static void end() {
        if (!active) {
            return;
        }
        mark(END);
        active = false;
        long frame = UiHud.frame();
        if (frame != lastFrame) {
            lastFrame = frame;
            frames++;
        }
        if (frames < WINDOW) {
            return;
        }
        long totalNanos = 0;
        long totalBytes = 0;
        StringBuilder split = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            totalNanos += nanos[i];
            totalBytes += bytes[i];
            split.append(i == 0 ? "" : ", ").append(NAMES[i]).append(' ')
                    .append(String.format("%.1f us %.0f B", nanos[i] / 1000.0 / frames, (double) bytes[i] / frames));
        }
        last[0] = totalNanos / 1000.0 / frames;
        last[1] = (double) totalBytes / frames;
        LOGGER.info("[wflib ui] UI {} us/frame, {} B/frame over {} frames ({})",
                String.format("%.1f", last[0]), String.format("%.1f", last[1]), frames, split);
        if (detail) {
            int frameCount = frames;
            SIGNAL_BYTES.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(12)
                    .forEach(e -> LOGGER.info("[wflib ui]   {} {} B/frame", e.getKey().fullName(),
                            String.format("%.1f", (double) e.getValue()[0] / frameCount)));
            SIGNAL_BYTES.clear();
        }
        java.util.Arrays.fill(nanos, 0);
        java.util.Arrays.fill(bytes, 0);
        frames = 0;
    }
}
