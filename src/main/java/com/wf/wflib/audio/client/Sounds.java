package com.wf.wflib.audio.client;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.network.SoundAtPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Client one-shots: layers at a point, each started after its {@code delay} + travel time; out-of-band layers skipped. */
public final class Sounds {
    private Sounds() {
    }

    public static void at(List<SoundSpec> layers, double x, double y, double z, AudioCategory category, long seed) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = ear.distanceTo(new Vec3(x, y, z));
        double now = SoundScheduler.now(0);
        for (int i = 0; i < layers.size(); i++) {
            SoundSpec s = layers.get(i);
            if (d < s.range() && d > s.audibleFrom()) {
                SoundScheduler.schedule(Emitter.at(s, category, x, y, z, seed + i), now + s.delay(), d, null);
            }
        }
    }

    public static void at(SoundSpec spec, double x, double y, double z, AudioCategory category, long seed) {
        at(List.of(spec), x, y, z, category, seed);
    }

    public static void handle(SoundAtPacket p) {
        if (Minecraft.getInstance().level != null) {
            at(p.layers(), p.x(), p.y(), p.z(), p.category(), p.seed());
        }
    }
}
