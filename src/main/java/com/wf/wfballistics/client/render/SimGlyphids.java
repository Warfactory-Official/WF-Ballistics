package com.wf.wfballistics.client.render;

import com.wf.wfballistics.client.flywheel.FlywheelEffectManager;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.network.SimGlyphidSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * What the client knows about the glyphids that have no entity.
 *
 * <p>The store behind {@link SimGlyphidVisual}. It holds one {@link Ghost} per record in range, updated
 * every {@link SimGlyphidSyncPacket#INTERVAL} ticks and interpolated in between, which is exactly what a
 * client already does with an entity — the difference is that nothing here has a hitbox, a sound, a name
 * tag, a light source or a tick.
 *
 * <p><b>Published as a whole array, never edited in place.</b> Flywheel poses visuals from its own worker
 * pool while the network thread's work is running on the client thread, so a store that mutated a live
 * {@code Ghost} would be a glyphid drawn half at its old position and half at its new one. Each update
 * builds a fresh set and swaps the reference, so a frame either sees the whole of one update or the whole
 * of the last.
 */
public final class SimGlyphids {

    /**
     * Ticks with nothing to draw before the flywheel effect is retired. Not zero, because a swarm's records
     * come and go as they cross the promotion boundary and rebuilding the instancers each time would cost
     * more than keeping an idle effect.
     */
    private static final int IDLE_TICKS = 60;

    /**
     * One glyphid, as the client sees it. Immutable: the interpolation reads it from a render thread.
     *
     * @param walk the walk cycle's accumulated phase, carried across updates from the previous ghost with
     *             the same id. Vanilla keeps this on the entity as {@code walkAnimation}; here it is derived
     *             from how far the record actually moved, which is the same input
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

    /**
     * Volatile because it is written on the client thread and read from flywheel's workers.
     */
    private static volatile Ghost[] ghosts = NONE;
    private static @Nullable Level level;
    /**
     * Client ticks since the last update, for the interpolation between two of them.
     */
    private static volatile int sinceUpdate;
    private static int idle;

    private SimGlyphids() {
    }

    public static Ghost[] ghosts() {
        return ghosts;
    }

    /**
     * @return how far between the last two updates this frame is, clamped so a dropped packet leaves the
     * swarm standing where it was rather than extrapolating it into the distance.
     */
    public static float alpha(float partialTick) {
        return Math.min(1.0f, (sinceUpdate + partialTick) / SimGlyphidSyncPacket.INTERVAL);
    }

    /**
     * Take one update. Called on the client thread from the payload handler.
     */
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
            // Vanilla's walk animation is accumulated horizontal speed; a record's speed is how far it
            // actually went, so the legs cannot get out of step with the walk the way a guessed phase would.
            // Wrapped to one cycle so a record that has been walking for an hour does not lose the fraction
            // the pose table indexes on to float precision.
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

    /**
     * Advance the interpolation clock. Driven off the effect's tick so it stops with the game rather than
     * running on through a pause.
     */
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
     * @return true if flywheel can draw these at all. With the backend off they are not drawn, which is the
     * same trade the entity tier already makes: {@code skipVanillaRender} is set there too.
     */
    public static boolean drawable(Level candidate) {
        return FlywheelEffectManager.isAvailable(candidate);
    }
}
