package com.wf.wflib.client.fx;

import com.wf.gemrender.light.LightSink;
import com.wf.gemrender.light.Lights;
import com.wf.wflib.entity.FireLingeringEntity;
import com.wf.wflib.fire.FireType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Blast/muzzle flashes + lingering fire -> GemRender shadowed point lights. {@link Lights#shading()}==false:
 * fire keeps the {@link WFDynamicLight} lightmap boost instead, flashes just don't happen.
 */
public final class WFLights {
    static final int FIRE_RGB = 0xFF8A3A, PHOSPHORUS_RGB = 0xFFF2DC, FLASH_RGB = 0xFFC27A, EMP_RGB = 0x9FD0FF,
            MUZZLE_RGB = 0xFFB860;
    /** Muzzle range cap, blocks. */
    static final float MUZZLE_RANGE = 48;
    /** Fire: range = this + footprint radius; flicker depth. Source {@link #LIFT} over the flame tops: 1 block off
     * the floor => E ~ I/2 there, floor saturates white. */
    static final float FIRE_RANGE = 10, FIRE_INTENSITY = 13, PHOSPHORUS_INTENSITY = 30, FLICKER = 0.2F, LIFT = 0.5F;
    /** Flash source this far over the blast point (usually on the ground). */
    static final double FLASH_LIFT = 1.5;

    /** {@code start}: game ticks. */
    private record Flash(double x, double y, double z, float range, int rgb, float peak, double start, float life) {
    }

    private static final List<Flash> FLASHES = new ArrayList<>();
    private static final Set<FireLingeringEntity> FIRES = new LinkedHashSet<>();
    /** Harness: lights submitted by the last collect. */
    public static int flashes, fires;

    private WFLights() {
    }

    public static void register() {
        Lights.register(WFLights::collect);
    }

    /** Peak irradiance ~ {@code peak / (d^2 + 1)}, quadratic fade to 0 over {@code life} ticks. */
    public static void flash(double x, double y, double z, float range, int rgb, float peak, float life) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && Lights.shading()) {
            FLASHES.add(new Flash(x, y, z, range, rgb, peak, mc.level.getGameTime(), life));
        }
    }

    /** Blast of reach {@code range} blocks: E ~ 2 at half range. */
    public static void blast(double x, double y, double z, float range, float life) {
        flash(x, y + FLASH_LIFT, z, range, FLASH_RGB, range * range * 0.5F, life);
    }

    /** Lift along the vent normal ({@link BlastFrame}): wall blast centre => light inside the wall. */
    public static void blast(double x, double y, double z, org.joml.Quaternionf vent, float range, float life) {
        org.joml.Vector3f lift = vent.transform(new org.joml.Vector3f(0, (float) FLASH_LIFT, 0));
        flash(x + lift.x, y + lift.y, z + lift.z, range, FLASH_RGB, range * range * 0.5F, life);
    }

    /** Gun / cannon flash at the muzzle; range: E ~ 0.25 at the edge. */
    public static void muzzle(double x, double y, double z, float peak, float life) {
        flash(x, y, z, Math.min(MUZZLE_RANGE, 2 * (float) Math.sqrt(peak) + 2), MUZZLE_RGB, peak, life);
    }

    /** @return false => caller must light the fire another way. */
    public static boolean fire(FireLingeringEntity fire) {
        if (!Lights.shading()) {
            return false;
        }
        FIRES.add(fire);
        return true;
    }

    public static void clear() {
        FLASHES.clear();
        FIRES.clear();
    }

    private static void collect(LightSink sink, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        flashes = fires = 0;
        if (mc.level == null) {
            clear();
            return;
        }
        double now = mc.level.getGameTime() + partialTick;
        FLASHES.removeIf(f -> now - f.start >= f.life);
        for (Flash f : FLASHES) {
            float k = 1 - (float) ((now - f.start) / f.life);
            sink.point(f.x, f.y, f.z, f.range, f.rgb, f.peak * k * k, true);
            flashes++;
        }
        FIRES.removeIf(f -> f.isRemoved() || f.level() != mc.level);
        for (FireLingeringEntity f : FIRES) {
            AABB box = f.getBoundingBox();
            boolean phosphorus = f.getFireType() == FireType.PHOSPHORUS;
            // Two incommensurate sines + per-fire phase: no visible period, no shared beat between fires.
            double t = now * 0.35 + f.getId() * 1.7;
            float flicker = 1 - FLICKER * (0.5F + 0.3F * (float) Math.sin(t) + 0.2F * (float) Math.sin(t * 2.71));
            float radius = (float) Math.max(box.getXsize(), box.getZsize()) * 0.5F;
            sink.point((box.minX + box.maxX) * 0.5, box.maxY + LIFT,
                    (box.minZ + box.maxZ) * 0.5, FIRE_RANGE + radius, phosphorus ? PHOSPHORUS_RGB : FIRE_RGB,
                    (phosphorus ? PHOSPHORUS_INTENSITY : FIRE_INTENSITY) * Mth.clamp(flicker, 0, 1)
                            * Math.max(1, radius), true);
            fires++;
        }
    }

    /** For the {@link WFDynamicLight} lightmap fallback only. */
    static BlockPos lightPos(FireLingeringEntity fire) {
        AABB box = fire.getBoundingBox();
        return BlockPos.containing((box.minX + box.maxX) * 0.5, box.minY + Math.min(1.0, box.getYsize() * 0.5),
                (box.minZ + box.maxZ) * 0.5);
    }
}
