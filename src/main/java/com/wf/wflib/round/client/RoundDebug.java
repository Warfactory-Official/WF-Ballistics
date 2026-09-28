package com.wf.wflib.round.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.render.DebugLines;
import com.wf.wflib.round.RoundArc;
import com.wf.wflib.round.RoundEnd;
import com.wf.wflib.round.RoundNetwork;
import com.wf.wflib.round.Rounds;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/** {@code /wfdebug rounds}: every client round's flown path, predicted arc, resyncs, pierces and end. Client thread. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class RoundDebug implements RoundObserver {

    /** Flown points kept per round; older dropped (origin kept). */
    static final int PATH = 512;
    static final int ARC_TICKS = 200;
    static final int MARKS = 16;
    static final int ENDED = 32;
    static final long FADE_MS = 4000L;

    private static final byte RESYNC = 0;
    private static final byte PIERCE_EXIT = 1;
    private static final byte PIERCE_STOP = 2;

    private static final RoundDebug INSTANCE = new RoundDebug();
    private static final Reference2ObjectOpenHashMap<ClientRounds.Round, Track> TRACKS =
            new Reference2ObjectOpenHashMap<>();
    /** {@link #TRACKS} values, indexed: frames iterate without an iterator. */
    private static final ArrayList<Track> LIVE_TRACKS = new ArrayList<>();
    /** Oldest first. */
    private static final ArrayList<Track> ENDED_TRACKS = new ArrayList<>();
    private static final ArrayDeque<Track> POOL = new ArrayDeque<>();
    private static boolean enabled;
    private static boolean observing;

    private RoundDebug() {
    }

    static final class Track {
        ClientRounds.Round round;
        final double[] path = new double[PATH * 3];
        /** Next write slot. */
        int head;
        int count;
        double ox, oy, oz;
        final double[] arc = new double[ARC_TICKS * 3];
        int arcCount;
        /** Per mark: a, b. */
        final double[] marks = new double[MARKS * 6];
        final byte[] kinds = new byte[MARKS];
        int markHead;
        int markCount;
        boolean ended;
        double ex, ey, ez;
        RoundEnd reason;
        long endMs;

        void reset(ClientRounds.Round r) {
            this.round = r;
            this.head = this.count = this.arcCount = this.markHead = this.markCount = 0;
            this.ended = false;
            this.reason = null;
            this.ox = r.ox;
            this.oy = r.oy;
            this.oz = r.oz;
        }

        void push(double x, double y, double z) {
            this.path[3 * this.head] = x;
            this.path[3 * this.head + 1] = y;
            this.path[3 * this.head + 2] = z;
            this.head = (this.head + 1) % PATH;
            this.count = Math.min(this.count + 1, PATH);
        }

        void mark(byte kind, double ax, double ay, double az, double bx, double by, double bz) {
            int m = this.markHead;
            this.kinds[m] = kind;
            this.marks[6 * m] = ax;
            this.marks[6 * m + 1] = ay;
            this.marks[6 * m + 2] = az;
            this.marks[6 * m + 3] = bx;
            this.marks[6 * m + 4] = by;
            this.marks[6 * m + 5] = bz;
            this.markHead = (m + 1) % MARKS;
            this.markCount = Math.min(this.markCount + 1, MARKS);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        if (on && !observing) {
            ClientRounds.addObserver(INSTANCE);
            observing = true;
        }
        enabled = on;
        if (!on) {
            POOL.addAll(LIVE_TRACKS);
            LIVE_TRACKS.clear();
            TRACKS.clear();
            POOL.addAll(ENDED_TRACKS);
            ENDED_TRACKS.clear();
        }
    }

    /** One line per live round: key, preset, tracer, local, speed, flown points. */
    public static String status() {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "overlay=%s live=%d ended=%d",
                enabled, ClientRounds.live().size(), ENDED_TRACKS.size()));
        for (ClientRounds.Round r : ClientRounds.live()) {
            Track t = TRACKS.get(r);
            sb.append(String.format(Locale.ROOT, "%n  %d %s tracer=#%06x local=%s v=%.2f pts=%d", r.key,
                    r.preset.id(), r.preset.tracerColor(), r.local,
                    Math.sqrt(r.vx * r.vx + r.vy * r.vy + r.vz * r.vz), t == null ? 0 : t.count));
        }
        return sb.toString();
    }

    private static Track track(ClientRounds.Round r) {
        Track t = TRACKS.get(r);
        if (t == null) {
            t = POOL.isEmpty() ? new Track() : POOL.poll();
            t.reset(r);
            t.push(r.ox, r.oy, r.oz);
            TRACKS.put(r, t);
            LIVE_TRACKS.add(t);
        }
        return t;
    }

    @Override
    public void tick(ClientRounds.Round r) {
        if (!enabled) {
            return;
        }
        Track t = track(r);
        t.push(r.x, r.y, r.z);
        ClientLevel level = Minecraft.getInstance().level;
        t.arcCount = r.resting || level == null ? 0 : RoundArc.predict(r.preset, r.x, r.y, r.z, r.vx, r.vy, r.vz,
                r.life, level.getMinBuildHeight() - Rounds.VOID_DEPTH, ARC_TICKS, t.arc);
    }

    @Override
    public void pierced(RoundNetwork.Pierce p) {
        ClientRounds.Round r = enabled ? ClientRounds.get(p.key()) : null;
        if (r != null) {
            track(r).mark(p.exited() ? PIERCE_EXIT : PIERCE_STOP, p.x(), p.y(), p.z(), p.px(), p.py(), p.pz());
        }
    }

    @Override
    public void resynced(ClientRounds.Round r, Vec3 before) {
        if (enabled) {
            track(r).mark(RESYNC, before.x, before.y, before.z, r.x, r.y, r.z);
        }
    }

    @Override
    public void ended(ClientRounds.Round r, Vec3 at, RoundEnd reason) {
        if (!enabled) {
            return;
        }
        Track t = TRACKS.remove(r);
        if (t != null) {
            LIVE_TRACKS.remove(t);
        } else {
            t = POOL.isEmpty() ? new Track() : POOL.poll();
            t.reset(r);
            t.push(r.ox, r.oy, r.oz);
        }
        t.push(at.x, at.y, at.z);
        t.ended = true;
        t.ex = at.x;
        t.ey = at.y;
        t.ez = at.z;
        t.reason = reason;
        t.endMs = Util.getMillis();
        t.arcCount = 0;
        ENDED_TRACKS.add(t);
        if (ENDED_TRACKS.size() > ENDED) {
            POOL.add(ENDED_TRACKS.remove(0));
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled) {
            return;
        }
        // Rounds dropped without an end (none expected): recycled.
        LIVE_TRACKS.removeIf(t -> {
            if (ClientRounds.get(t.round.key) == t.round) {
                return false;
            }
            TRACKS.remove(t.round);
            POOL.add(t);
            return true;
        });
        long now = Util.getMillis();
        while (!ENDED_TRACKS.isEmpty() && now - ENDED_TRACKS.get(0).endMs > FADE_MS) {
            POOL.add(ENDED_TRACKS.remove(0));
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (!enabled || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || LIVE_TRACKS.isEmpty() && ENDED_TRACKS.isEmpty()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        for (int i = 0; i < LIVE_TRACKS.size(); i++) {
            draw(pose, lines, LIVE_TRACKS.get(i), cam, pt, 1.0f);
        }
        long now = Util.getMillis();
        for (int i = 0; i < ENDED_TRACKS.size(); i++) {
            Track t = ENDED_TRACKS.get(i);
            draw(pose, lines, t, cam, pt, Math.max(0.0f, 1.0f - (now - t.endMs) / (float) FADE_MS));
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    /** World coordinates minus the camera in double: float world coordinates lose the path at range. */
    private static void draw(PoseStack pose, VertexConsumer out, Track t, Vec3 cam, float pt, float fade) {
        ClientRounds.Round r = t.round;
        double cx = cam.x, cy = cam.y, cz = cam.z;
        float pr = r.local ? 0.2f : 1.0f, pg = r.local ? 0.9f : 1.0f, pb = 1.0f;
        int first = (t.head - t.count + PATH) % PATH;
        double lx = t.ox - cx, ly = t.oy - cy, lz = t.oz - cz;
        DebugLines.cross(pose, out, lx, ly, lz, 0.15, pr, pg, pb, fade);
        // Live: last point = current position, ahead of the interpolated head.
        int drawn = t.ended ? t.count : t.count - 1;
        for (int n = 0; n < drawn; n++) {
            int s = 3 * ((first + n) % PATH);
            double x = t.path[s] - cx, y = t.path[s + 1] - cy, z = t.path[s + 2] - cz;
            DebugLines.line(pose, out, lx, ly, lz, x, y, z, pr, pg, pb, 0.9f * fade);
            lx = x;
            ly = y;
            lz = z;
        }
        if (!t.ended) {
            double hx = r.ix(pt) - cx, hy = r.iy(pt) - cy, hz = r.iz(pt) - cz;
            DebugLines.line(pose, out, lx, ly, lz, hx, hy, hz, pr, pg, pb, 0.9f);
            int rgb = r.preset.tracerColor();
            float tr = rgb == 0 ? 1.0f : ((rgb >> 16) & 0xFF) / 255f;
            float tg = rgb == 0 ? 0.0f : ((rgb >> 8) & 0xFF) / 255f;
            float tb = rgb == 0 ? 1.0f : (rgb & 0xFF) / 255f;
            DebugLines.cross(pose, out, hx, hy, hz, 0.3, tr, tg, tb, 1.0f);
            // Dashed: even steps only, alpha falling to 0 at the arc's end.
            double ax = r.x - cx, ay = r.y - cy, az = r.z - cz;
            for (int k = 0; k < t.arcCount; k++) {
                double x = t.arc[3 * k] - cx, y = t.arc[3 * k + 1] - cy, z = t.arc[3 * k + 2] - cz;
                if ((k & 1) == 0) {
                    DebugLines.line(pose, out, ax, ay, az, x, y, z, 1.0f, 0.9f, 0.1f,
                            0.8f * (1.0f - k / (float) t.arcCount));
                }
                ax = x;
                ay = y;
                az = z;
            }
        } else {
            float[] c = endColour(t.reason);
            DebugLines.cross(pose, out, t.ex - cx, t.ey - cy, t.ez - cz, 0.4, c[0], c[1], c[2], fade);
        }
        for (int m = 0; m < t.markCount; m++) {
            double ax = t.marks[6 * m] - cx, ay = t.marks[6 * m + 1] - cy, az = t.marks[6 * m + 2] - cz;
            double bx = t.marks[6 * m + 3] - cx, by = t.marks[6 * m + 4] - cy, bz = t.marks[6 * m + 5] - cz;
            switch (t.kinds[m]) {
                case RESYNC -> {
                    DebugLines.line(pose, out, ax, ay, az, bx, by, bz, 1.0f, 0.5f, 0.0f, fade);
                    DebugLines.cross(pose, out, ax, ay, az, 0.2, 1.0f, 0.5f, 0.0f, fade);
                }
                case PIERCE_EXIT -> {
                    DebugLines.line(pose, out, ax, ay, az, bx, by, bz, 0.3f, 0.5f, 1.0f, fade);
                    DebugLines.cross(pose, out, ax, ay, az, 0.2, 0.3f, 0.5f, 1.0f, fade);
                    DebugLines.cross(pose, out, bx, by, bz, 0.2, 0.6f, 0.9f, 1.0f, fade);
                }
                default -> {
                    DebugLines.line(pose, out, ax, ay, az, bx, by, bz, 0.3f, 0.5f, 1.0f, fade);
                    DebugLines.cross(pose, out, bx, by, bz, 0.2, 0.6f, 0.2f, 1.0f, fade);
                }
            }
        }
    }

    private static final float[] RED = {1.0f, 0.15f, 0.15f};
    private static final float[] GREEN = {0.2f, 1.0f, 0.2f};
    private static final float[] AMBER = {1.0f, 0.8f, 0.0f};
    private static final float[] GREY = {0.55f, 0.55f, 0.55f};
    private static final float[] BROWN = {0.6f, 0.35f, 0.1f};

    private static float[] endColour(RoundEnd reason) {
        return switch (reason) {
            case BLOCK -> RED;
            case ENTITY -> GREEN;
            case FUSE -> AMBER;
            case DESTROYED, DUD -> BROWN;
            case EXPIRED, DEFERRED, CLEARED -> GREY;
        };
    }
}
