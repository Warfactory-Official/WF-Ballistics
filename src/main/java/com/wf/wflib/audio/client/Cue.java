package com.wf.wflib.audio.client;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Head-locked (relative) sound: cockpit alerts, lock tones. Never muffled, no range. */
public final class Cue extends AbstractTickableSoundInstance {
    private final SoundSpec spec;
    private final AudioCategory category;
    private int fadeTicks;
    private int fadeLeft = -1;
    private boolean ignoresHearing;

    public Cue(SoundSpec spec, AudioCategory category, boolean loop) {
        super(SoundEvent.createVariableRangeEvent(spec.event()), SoundSource.MASTER, RandomSource.create());
        this.spec = spec;
        this.category = category;
        this.relative = true;
        this.attenuation = Attenuation.NONE;
        this.looping = loop;
        this.delay = 0;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.pitch = spec.rollPitch(random);
        this.volume = spec.volume() * AudioOptions.volume(category);
    }

    /** Unaffected by {@link AudioMixer.Hearing} loss (the ringing itself). */
    public Cue ignoreHearing() {
        ignoresHearing = true;
        return this;
    }

    public boolean ignoresHearing() {
        return ignoresHearing;
    }

    public SoundSpec spec() {
        return spec;
    }

    /** Linear fade to silence over {@code ticks}, then stop; 0 => now. */
    public void fadeOut(int ticks) {
        if (ticks <= 0) {
            stop();
        } else if (fadeLeft < 0) {
            fadeTicks = ticks;
            fadeLeft = ticks;
        }
    }

    @Override
    public void tick() {
        float v = spec.volume() * AudioOptions.volume(category);
        if (fadeLeft >= 0) {
            if (fadeLeft == 0) {
                stop();
                return;
            }
            v *= (float) fadeLeft / fadeTicks;
            fadeLeft--;
        }
        volume = v;
    }
}
