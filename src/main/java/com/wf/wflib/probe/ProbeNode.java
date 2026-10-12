package com.wf.wflib.probe;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A point on a target: dot near the crosshair, its probe panel beside it when focused. Panel built only while
 * focused; capture what it reads (the provider's {@link ProbeContext} included).
 *
 * @param key unique per target, stable across frames: selection identity
 */
public final class ProbeNode {

    public static final int COLOUR = 0xFF55FFFF;

    private final String key;
    private final Vec3 at;
    private final Consumer<ProbeInfo> panel;
    private int colour = COLOUR;
    private double radius = 0.35;
    private boolean claimed;
    @Nullable
    private Vec3 normal;
    private double reach = Double.MAX_VALUE;

    public ProbeNode(String key, Vec3 at, Consumer<ProbeInfo> panel) {
        this.key = key;
        this.at = at;
        this.panel = panel;
    }

    public ProbeNode colour(int argb) {
        colour = argb;
        return this;
    }

    /** Focus radius at the node, m; angular floor 3 deg. */
    public ProbeNode radius(double metres) {
        radius = metres;
        return this;
    }

    /** Crosshair is on this node's geometry: focused when no node is inside its radius. */
    public ProbeNode claimed(boolean claimed) {
        this.claimed = claimed;
        return this;
    }

    /** Outward face: hidden from behind (far side of a hull). Need not be unit length. */
    public ProbeNode normal(Vec3 outward) {
        normal = outward;
        return this;
    }

    /** Eye -> node limit, m, below the overlay's; match the action's own reach check. */
    public ProbeNode reach(double metres) {
        reach = metres;
        return this;
    }

    @Nullable
    public Vec3 normal() {
        return normal;
    }

    public double reach() {
        return reach;
    }

    public String key() {
        return key;
    }

    public Vec3 at() {
        return at;
    }

    public int colour() {
        return colour;
    }

    public double radius() {
        return radius;
    }

    public boolean claimed() {
        return claimed;
    }

    public void fill(ProbeInfo info) {
        panel.accept(info);
    }
}
