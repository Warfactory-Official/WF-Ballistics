package com.wf.wflib.audio.client;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * One cockpit alert voice: of the alerts {@link #want}ed since the last {@link #tick}, the highest priority loops as a
 * {@link Cue}; a change of winner swaps the loop. Owner ticks it every client tick.
 */
public final class AlertChannel {
    @Nullable
    private SoundSpec wanted;
    private int wantedPriority = Integer.MIN_VALUE;
    @Nullable
    private Cue playing;

    /** Request {@code alert} this tick. */
    public void want(SoundSpec alert, int priority) {
        if (priority > wantedPriority) {
            wanted = alert;
            wantedPriority = priority;
        }
    }

    public void tick() {
        SoundSpec next = wanted;
        wanted = null;
        wantedPriority = Integer.MIN_VALUE;
        if (playing != null && (playing.isStopped() || playing.spec() != next)) {
            playing.fadeOut(0);
            playing = null;
        }
        if (playing == null && next != null) {
            playing = new Cue(next, AudioCategory.ALERTS, true);
            Minecraft.getInstance().getSoundManager().play(playing);
        }
    }

    public void stop() {
        wanted = null;
        wantedPriority = Integer.MIN_VALUE;
        tick();
    }
}
