package com.wf.wfballistics.entity.mist;

import com.wf.wfballistics.entity.MistEntity;
import net.minecraft.world.entity.Entity;

/**
 * What a fluid does when it hangs in the air as mist. Registered against a {@code Fluid} or a fluid
 * {@code TagKey} in {@link MistEffects}; the {@link MistEntity} looks one up from the fluid it was imbued
 * with and runs it every tick.
 *
 * <p>An effect has three hooks, all optional to override beyond {@link #affect}:
 * <ul>
 *   <li>{@link #affect} — per entity standing in the cloud, every tick.</li>
 *   <li>{@link #areaTick} — once per tick for the whole cloud (radiation fields, igniting the cloud, ...).</li>
 *   <li>{@link #color} — the ARGB tint for the cloud's particles.</li>
 * </ul>
 *
 * <p>{@code intensity} is supplied to the per-tick hooks and falls linearly from 1 at spawn to 0 at the end
 * of the cloud's life, so effects naturally taper off as the mist dissipates.
 */
public interface MistEffect {

    /**
     * Applied to one entity inside the cloud. Runs server-side.
     */
    void affect(MistEntity mist, Entity target, double intensity);

    /**
     * Whole-cloud tick, before per-entity processing. Runs server-side. Default: nothing.
     */
    default void areaTick(MistEntity mist, double intensity) {
    }

    /**
     * ARGB particle tint. Default {@code -1} means "use the fluid's own render colour".
     */
    default int color(MistEntity mist) {
        return -1;
    }
}
