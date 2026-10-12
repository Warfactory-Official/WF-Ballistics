package com.wf.wflib.audio.client;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.EngineSound;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Plays one {@link EngineSound} for an owner that ticks it every client tick; {@link #stop} on owner removal. */
public final class EnginePlayer {
    /** Owner state, sampled each tick. */
    public interface Source {
        /** World position of the engine; null => gone. */
        Vec3 position();

        /** Unit forward, world axes. */
        Vec3 forward();

        boolean running();

        /** 0..1. */
        float input(EngineSound.Input input);
    }

    private final EngineSound def;
    private final AudioCategory category;
    private final Source source;
    private final Emitter[] loops;
    private final float[] gain;
    private final float reach;
    private boolean wasRunning;
    private boolean primed;

    public EnginePlayer(EngineSound def, AudioCategory category, Source source) {
        this.def = def;
        this.category = category;
        this.source = source;
        this.loops = new Emitter[def.loops().size()];
        this.gain = new float[loops.length];
        this.reach = def.reach();
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        Vec3 at = source.position();
        if (at == null) {
            stop();
            return;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        double d = ear.distanceTo(at);
        boolean running = source.running();
        if (primed && running != wasRunning && d < reach) {
            (running ? def.start() : def.stop()).ifPresent(s -> SoundScheduler.at(
                    Emitter.tracking(s, category, source::position, false), 0));
        }
        wasRunning = running;
        primed = true;
        Vec3 toEar = ear.subtract(at);
        float aspect = toEar.lengthSqr() < 1e-6 ? 0
                : (float) Math.toDegrees(Math.acos(Math.clamp(toEar.normalize().dot(source.forward()), -1, 1)));
        for (int i = 0; i < loops.length; i++) {
            EngineSound.Loop l = def.loops().get(i);
            float x = source.input(l.input());
            float target = l.volume().at(x) * l.aspect().at(aspect)
                    * l.gate().at(source.input(EngineSound.Input.POWER));
            gain[i] += Math.clamp(target - gain[i], -l.fade(), l.fade());
            Emitter e = loops[i];
            boolean audible = gain[i] > EngineSound.SILENT && d < l.sound().range();
            if (e != null && (e.isStopped() || !audible)) {
                e.end();
                loops[i] = e = null;
            }
            if (audible) {
                if (e == null) {
                    e = loops[i] = Emitter.tracking(l.sound(), category, source::position, true);
                    e.setLevel(gain[i]);
                    e.setPitchScale(l.pitch().at(x));
                    e.refresh();
                    mc.getSoundManager().play(e);
                }
                e.setLevel(gain[i]);
                e.setPitchScale(l.pitch().at(x));
            }
        }
    }

    public void stop() {
        for (int i = 0; i < loops.length; i++) {
            if (loops[i] != null) {
                loops[i].end();
                loops[i] = null;
            }
            gain[i] = 0;
        }
    }
}
