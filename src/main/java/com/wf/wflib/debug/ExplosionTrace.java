package com.wf.wflib.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records what an {@link com.wf.wflib.aef.ExplosionAEF} actually did, so it can be drawn instead of guessed
 * at: every ray the block allocator marched and where it ran out of power, the jet axis and cone of a directional
 * charge, and (the part that matters when a warhead "does no damage") every entity the blast considered together
 * with the reason it was spared.
 */
public final class ExplosionTrace {

    /** How many finished blasts to keep. */
    public static final int HISTORY = 4;
    /** Ticks a recorded blast stays drawable, so the view clears itself instead of accumulating. */
    public static final int TTL_TICKS = 600;
    /** Ceiling on rays kept per blast: a spherical allocator marches thousands and they draw as mush. */
    private static final int MAX_RAYS = 640;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean enabled = false;
    private static volatile List<Trace> recent = List.of();

    @Nullable
    private static Builder current;

    private ExplosionTrace() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            current = null;
            recent = List.of();
        }
    }

    /** @return the finished traces, newest last. Safe to iterate from any thread. */
    public static List<Trace> recent() {
        return recent;
    }

    public static void clear() {
        recent = List.of();
    }

    public static void begin(Vec3 origin, float size, long gameTime) {
        if (!enabled) {
            return;
        }
        current = new Builder(origin, size, gameTime);
    }

    /** Declares the blast directional: a jet {@code axis} and the cone half-angle around it. */
    public static void cone(Vec3 axis, float halfAngleDeg) {
        Builder builder = current;
        if (builder != null) {
            builder.axis = axis;
            builder.halfAngleDeg = halfAngleDeg;
        }
    }

    /**
     * One marched ray.
     *
     * @param broke whether it destroyed at least one block, which is what separates a ray that did work
     *      from one that spent its power on air
     */
    public static void ray(double fromX, double fromY, double fromZ,
                           double toX, double toY, double toZ, boolean broke) {
        Builder builder = current;
        if (builder == null) {
            return;
        }
        // Keep a spread of rays rather than the first N, so a truncated trace still shows the whole shape.
        builder.rayCount++;
        if (builder.rays.size() < MAX_RAYS) {
            builder.rays.add(new Ray(new Vec3(fromX, fromY, fromZ), new Vec3(toX, toY, toZ), broke));
        } else if (builder.rayCount % builder.stride() == 0) {
            builder.rays.set((builder.rayCount / builder.stride()) % MAX_RAYS,
                    new Ray(new Vec3(fromX, fromY, fromZ), new Vec3(toX, toY, toZ), broke));
        }
    }

    /** One entity the blast looked at, and what it decided. */
    public static void victim(Entity entity, Verdict verdict, float damage) {
        Builder builder = current;
        if (builder == null) {
            return;
        }
        builder.victims.add(new Victim(entity.getBoundingBox()
                .getCenter(), verdict, damage,
                entity.getName()
                        .getString()));
    }

    public static void end() {
        Builder builder = current;
        current = null;
        if (builder == null) {
            return;
        }
        List<Trace> next = new ArrayList<>(recent);
        next.add(new Trace(builder.gameTime, builder.origin, builder.size, builder.axis,
                builder.halfAngleDeg, List.copyOf(builder.rays), List.copyOf(builder.victims),
                builder.rayCount));
        while (next.size() > HISTORY) {
            next.remove(0);
        }
        recent = Collections.unmodifiableList(next);
        LOGGER.info("[wf-debug] blast at {} size {}: {} rays ({} kept), {} entities considered, t={}",
                builder.origin, builder.size, builder.rayCount, builder.rays.size(),
                builder.victims.size(), builder.gameTime);
    }

    /** Why an entity in the blast's search box did or did not take damage. */
    public enum Verdict {
        /** Took damage. */
        HIT,
        /** Outside the blast's radius. */
        OUT_OF_RANGE,
        /** In range, but outside the blast's shape: a shaped charge's cone spares it. */
        OUTSIDE_CONE
    }

    public record Ray(Vec3 from, Vec3 to, boolean broke) {
    }

    public record Victim(Vec3 pos, Verdict verdict, float damage, String name) {
    }

    /**
     * @param axis null for a blast with no direction to it
     * @param recordedRays what {@link #rays} holds, which is capped; {@code totalRays} is what was marched
     */
    public record Trace(long gameTime, Vec3 origin, float size, @Nullable Vec3 axis, float halfAngleDeg,
                        List<Ray> rays, List<Victim> victims, int totalRays) {
    }

    private static final class Builder {

        private final Vec3 origin;
        private final float size;
        private final long gameTime;
        private final List<Ray> rays = new ArrayList<>();
        private final List<Victim> victims = new ArrayList<>();

        @Nullable
        private Vec3 axis;
        private float halfAngleDeg;
        private int rayCount;

        private Builder(Vec3 origin, float size, long gameTime) {
            this.origin = origin;
            this.size = size;
            this.gameTime = gameTime;
        }

        private int stride() {
            return Math.max(1, rayCount / MAX_RAYS);
        }
    }
}
