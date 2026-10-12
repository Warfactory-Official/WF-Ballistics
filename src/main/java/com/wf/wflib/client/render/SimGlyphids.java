package com.wf.wflib.client.render;

import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.network.SimGlyphidSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Client-side state for sim-tier (entity-less) glyphids. */
public final class SimGlyphids {

    /** Idle ticks before flywheel effect retires. */
    private static final int IDLE_TICKS = 60;

    /**
     * @param walk accumulated walk phase, carried across updates by id. Vanilla: entity field
     *      {@code walkAnimation}; here derived from distance moved (same input).
     */
    public record Ghost(int id, GlyphidCaste caste,
                        double prevX, double prevY, double prevZ,
                        double x, double y, double z,
                        float prevYaw, float yaw, float walk, int light) {

        public double lerpX(float alpha) {
            return prevX + (x - prevX) * alpha;
        }

        public double lerpY(float alpha) {
            return prevY + (y - prevY) * alpha;
        }

        public double lerpZ(float alpha) {
            return prevZ + (z - prevZ) * alpha;
        }

        public float lerpYaw(float alpha) {
            return prevYaw + Mth.wrapDegrees(yaw - prevYaw) * alpha;
        }
    }

    private static final Ghost[] NONE = new Ghost[0];

    /** Volatile: written on client thread, read by flywheel workers. */
    private static volatile Ghost[] ghosts = NONE;
    private static @Nullable Level level;
    /** Ticks since last update; interpolation denominator. */
    private static volatile int sinceUpdate;
    private static int idle;

    private SimGlyphids() {
    }

    public static Ghost[] ghosts() {
        return ghosts;
    }

    /** @return interpolation frac, clamped to 1: dropped packet freezes swarm instead of extrapolating. */
    public static float alpha(float partialTick) {
        return Math.min(1.0f, (sinceUpdate + partialTick) / SimGlyphidSyncPacket.INTERVAL);
    }

    /** Client thread only; called from payload handler. */
    public static void accept(SimGlyphidSyncPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        Level clientLevel = mc.level;
        if (clientLevel == null) {
            clear();
            return;
        }
        if (clientLevel != level) {
            level = clientLevel;
            ghosts = NONE;
        }

        Map<Integer, Ghost> previous = index(ghosts);
        Ghost[] next = new Ghost[packet.glyphids().size()];
        int i = 0;
        for (SimGlyphidSyncPacket.Entry entry : packet.glyphids()) {
            int caste = entry.caste() & 0xFF;
            if (caste >= GlyphidCaste.VALUES.length) {
                continue;
            }
            double x = packet.originX() + entry.dx() * SimGlyphidSyncPacket.QUANTUM;
            double y = packet.originY() + entry.dy() * SimGlyphidSyncPacket.QUANTUM;
            double z = packet.originZ() + entry.dz() * SimGlyphidSyncPacket.QUANTUM;
            float yaw = entry.yaw() * 360.0f / 256.0f;

            Ghost was = previous.get(entry.id());
            double fromX = was == null ? x : was.x;
            double fromY = was == null ? y : was.y;
            double fromZ = was == null ? z : was.z;
            float fromYaw = was == null ? yaw : was.yaw;
            float walk = was == null ? 0.0f
                    : (float) ((was.walk + Math.hypot(x - fromX, z - fromZ) * 4.0) % (Math.PI * 2.0));

            next[i++] = new Ghost(entry.id(), GlyphidCaste.VALUES[caste],
                    fromX, fromY, fromZ, x, y, z, fromYaw, yaw, walk,
                    LevelRenderer.getLightColor(clientLevel, BlockPos.containing(x, y, z)));
        }

        ghosts = i == next.length ? next : java.util.Arrays.copyOf(next, i);
        sinceUpdate = 0;
        if (ghosts.length > 0) {
            idle = 0;
            SimGlyphidEffect.ensureLive(clientLevel);
        }
    }

    private static Map<Integer, Ghost> index(Ghost[] from) {
        Map<Integer, Ghost> map = new HashMap<>(from.length * 2);
        for (Ghost ghost : from) {
            map.put(ghost.id(), ghost);
        }
        return map;
    }

    public static void tick() {
        sinceUpdate++;
        if (ghosts.length == 0) {
            idle++;
        } else {
            idle = 0;
        }
    }

    public static boolean idleTooLong() {
        return idle > IDLE_TICKS;
    }

    public static void clear() {
        ghosts = NONE;
        level = null;
        idle = IDLE_TICKS + 1;
    }

    /**
     * @return false with flywheel backend off (not drawn); entity tier makes same trade via
     *      {@code skipVanillaRender}.
     */
    public static boolean drawable(Level candidate) {
        return FlywheelEffectManager.isAvailable(candidate);
    }
}
