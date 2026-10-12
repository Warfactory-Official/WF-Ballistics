package com.wf.wflib.audio.client;

import com.wf.wflib.audio.Acoustics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.PriorityQueue;

/**
 * One-shots started at emission + distance / {@link Acoustics#C} from the camera. Clock {@link #now}: client ticks
 * (counted at {@code ClientTickEvent.Pre}, paused excluded) + frame partial tick, seconds; started on
 * {@code RenderFrameEvent.Pre}. Full => latest due of queue + new dropped. Client thread.
 */
public final class SoundScheduler {
    static final int MAX_QUEUED = 512;

    /** Called as a due sound starts; {@code emitted}, {@code due}, {@code now}: client seconds. */
    public interface Started {
        void started(SoundInstance sound, double emitted, double due, double now, double distance);
    }

    record Due(double at, double emitted, SoundInstance sound, double distance, @Nullable Started hook)
            implements Comparable<Due> {
        @Override
        public int compareTo(Due o) {
            return Double.compare(at, o.at);
        }
    }

    static final PriorityQueue<Due> QUEUE = new PriorityQueue<>();
    private static long ticks;
    /** Harness: one-shots started. */
    public static volatile int started;

    private SoundScheduler() {
    }

    /** Client seconds at partial tick {@code pt} of the current tick. */
    public static double now(float pt) {
        return (ticks + pt) / 20.0;
    }

    /** Emitted {@code after} s from now (tick start); + travel time. */
    public static void at(SoundInstance sound, double after) {
        atTime(sound, now(0) + after, null);
    }

    /** Emitted at client second {@code emitted}. */
    public static void atTime(SoundInstance sound, double emitted, @Nullable Started hook) {
        if (sound instanceof Emitter e && !e.refresh()) {
            return;
        }
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        schedule(sound, emitted, ear.distanceTo(new Vec3(sound.getX(), sound.getY(), sound.getZ())), hook);
    }

    /** {@code distance}: travel; {@code emitted}: client seconds. */
    public static void schedule(SoundInstance sound, double emitted, double distance, @Nullable Started hook) {
        Due d = new Due(emitted + Acoustics.delay(distance), emitted, sound, distance, hook);
        if (QUEUE.size() >= MAX_QUEUED) {
            Due last = Collections.max(QUEUE);
            if (last.at <= d.at) {
                return;
            }
            QUEUE.remove(last);
        }
        QUEUE.add(d);
    }

    /** {@code sound} still queued => dequeued, true. */
    public static boolean cancel(SoundInstance sound) {
        return QUEUE.removeIf(d -> d.sound == sound);
    }

    /** Started now, no travel (the local player's own sounds). */
    public static void startNow(SoundInstance sound, @Nullable Started hook) {
        double now = now(0);
        start(sound, now, now, now, 0, hook);
    }

    static void tick(boolean paused) {
        if (Minecraft.getInstance().level == null) {
            QUEUE.clear();
        } else if (!paused) {
            ticks++;
        }
    }

    static void frame(float pt) {
        if (QUEUE.isEmpty()) {
            return;
        }
        double now = now(pt);
        while (!QUEUE.isEmpty() && QUEUE.peek().at <= now) {
            Due d = QUEUE.poll();
            start(d.sound, d.emitted, d.at, now, d.distance, d.hook);
        }
    }

    private static void start(SoundInstance sound, double emitted, double due, double now, double distance,
                              @Nullable Started hook) {
        if (sound instanceof Emitter e && !e.refresh()) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(sound);
        started++;
        if (hook != null) {
            hook.started(sound, emitted, due, now, distance);
        }
    }

    public static void reset() {
        QUEUE.clear();
    }
}
