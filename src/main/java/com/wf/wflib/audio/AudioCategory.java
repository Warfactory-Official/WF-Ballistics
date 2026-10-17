package com.wf.wflib.audio;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Volume slider (client config, Sound options screen) and cabin muffle share of a wflib-managed sound. Engine
 * source = MASTER: vanilla category sliders do not apply.
 */
public enum AudioCategory implements StringRepresentable {
    VEHICLES("vehicles", 1),
    WEAPONS("weapons", 1),
    /** Muffle 0.4: a blast keeps its punch inside a hull. */
    EXPLOSIONS("explosions", 0.4F),
    /** Head-locked cockpit cues: never muffled. */
    ALERTS("alerts", 0),
    /** Own vehicle's set unmuffled ({@code RadioEmitter}). */
    RADIO("radio", 1);

    public static final Codec<AudioCategory> CODEC = StringRepresentable.fromEnum(AudioCategory::values);

    private final String key;
    private final float muffle;

    AudioCategory(String key, float muffle) {
        this.key = key;
        this.muffle = muffle;
    }

    @Override
    public String getSerializedName() {
        return key;
    }

    /** Share of the cabin muffle applied, 0..1. */
    public float muffle() {
        return muffle;
    }
}
