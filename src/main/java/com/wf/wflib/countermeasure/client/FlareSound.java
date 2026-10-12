package com.wf.wflib.countermeasure.client;

import com.wf.wflib.WFSounds;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.client.Emitter;
import com.wf.wflib.countermeasure.FlareEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class FlareSound {
    private static final SoundSpec BURN = new SoundSpec(WFSounds.FLARE_BURN.getId(), 1, 1, 0.1F, 96, 0);

    private FlareSound() {
    }

    public static void play(FlareEntity flare) {
        Emitter e = Emitter.following(BURN, AudioCategory.WEAPONS, flare, Vec3.ZERO, false);
        if (e.refresh()) {
            Minecraft.getInstance().getSoundManager().play(e);
        }
    }
}
