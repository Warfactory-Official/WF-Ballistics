package com.wf.wflib.ui;

/** Wall-clock seconds for HUD animation; keeps running while the game is paused. */
public final class UiClock {
    private static final long ORIGIN = System.nanoTime();

    private UiClock() {
    }

    public static double seconds() {
        return (System.nanoTime() - ORIGIN) * 1e-9;
    }
}
