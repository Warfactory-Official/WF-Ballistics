package com.wf.wflib.client.cam;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wf.wflib.config.WFClientConfig;
import com.wf.wflib.drone.cam.CameraFeed;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Every feed this client knows about, one picture per feed, and the governor that decides which one gets the next
 * frame.
 */
public final class CameraFeedCache {

    /** Frames a feed may go unrequested before its framebuffer is released. Roughly a fifth of a second. */
    private static final int STALE_FRAMES = 12;
    /** Blocks at which a monitor stops drawing, and therefore where the frame rate falls to its floor. */
    public static final double FALLOFF_DISTANCE = 64.0;

    private static final Map<Integer, CameraFeed> FEEDS = new HashMap<>();
    private static final Map<Integer, CameraTarget> TARGETS = new HashMap<>();
    private static final Map<Integer, Request> REQUESTS = new HashMap<>();
    private static long frame;
    private static long rendersThisSecond;
    private static long rendersReported;
    private static long reportedAtNanos;

    private CameraFeedCache() {
    }

    // --- inbound --------------------------------------------------------------------------------------

    public static void accept(CameraFeed feed) {
        FEEDS.put(feed.feedId(), feed);
        // Before anything reads the angle: this is the sample the local slew simulation reconciles against.
        FeedGimbal.accept(feed);
        FeedMotion.accept(feed);
    }

    @Nullable
    public static CameraFeed feed(int feedId) {
        return FEEDS.get(feedId);
    }

    /** Every feed this client has heard of, live or not. Used to decide which streamed chunks are ours. */
    public static Collection<CameraFeed> feeds() {
        return FEEDS.values();
    }

    /**
     * @return true if this feed has arrived recently enough to be worth drawing. A feed that has stopped
     *      updating is signal loss, not a frozen picture: showing the last frame forever is the single most
     *      misleading thing a camera display can do.
     */
    public static boolean live(int feedId) {
        CameraFeed feed = FEEDS.get(feedId);
        Minecraft mc = Minecraft.getInstance();
        if (feed == null || mc.level == null) {
            return false;
        }
        return feed.usable() && mc.level.getGameTime() - feed.gameTime() <= CameraFeed.STALE_TICKS;
    }

    /** Whether this client holds any terrain where the drone is. */
    public static boolean hasTerrain(@Nullable CameraFeed feed) {
        Minecraft mc = Minecraft.getInstance();
        if (feed == null || mc.level == null) {
            return false;
        }
        return mc.level.getChunkSource().getChunk(
                SectionPos.blockToSectionCoord(Mth.floor(feed.x())),
                SectionPos.blockToSectionCoord(Mth.floor(feed.z())), false) != null;
    }

    /**
     * @return true if there is a picture worth putting on a screen. Consumers draw their own card otherwise,
     *      so "no feed", "no power", "out of range" and "out of terrain" stay four different messages.
     */
    public static boolean drawable(int feedId) {
        return live(feedId) && hasTerrain(FEEDS.get(feedId));
    }

    // --- consumer side --------------------------------------------------------------------------------

    /**
     * Tell the governor that this feed is being drawn.
     *
     * @param pixelWidth how wide, in physical pixels, on screen right now
     * @param distance how far the viewer is from the display. Zero for an open GUI: the operator is
     *      looking straight at it
     */
    public static void request(int feedId, int pixelWidth, double distance) {
        Request request = REQUESTS.computeIfAbsent(feedId, id -> new Request());
        if (request.frame != frame) {
            request.frame = frame;
            request.width = 0;
            request.distance = Double.MAX_VALUE;
        }
        request.width = Math.max(request.width, pixelWidth);
        request.distance = Math.min(request.distance, distance);
    }

    /**
     * @return the texture holding this feed's most recent picture, or null if there is none to show. Callers
     *      draw their own card in that case.
     */
    @Nullable
    public static ResourceLocation texture(int feedId) {
        CameraTarget target = TARGETS.get(feedId);
        return target == null ? null : target.textureOrNull();
    }

    // --- the governor ---------------------------------------------------------------------------------

    /**
     * Render at most {@code framesPerTick} feeds. Called once per frame, before the main level pass.
     */
    public static void renderFrame(DeltaTracker delta) {
        frame++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        int evicted = evict();

        int budget = WFClientConfig.CAMERA_BUDGET.get();
        long now = System.nanoTime();
        List<Candidate> due = new ArrayList<>();
        for (Map.Entry<Integer, Request> entry : REQUESTS.entrySet()) {
            int feedId = entry.getKey();
            Request request = entry.getValue();
            if (frame - request.frame > STALE_FRAMES || !drawable(feedId)) {
                continue;
            }
            CameraTarget target = TARGETS.computeIfAbsent(feedId,
                    id -> new CameraTarget(id, CameraTarget.stepFor(request.width)));
            if (target.broken()) {
                continue;
            }
            long interval = 1_000_000_000L / Math.max(1, fpsFor(request.distance));
            long overdue = now - target.renderedAtNanos() - interval;
            if (overdue >= 0) {
                due.add(new Candidate(feedId, target, request, overdue));
            }
        }
        if (due.isEmpty()) {
            if (evicted > 0) {
                FeedAudit.handback(0, evicted);
            }
            return;
        }
        due.sort((a, b) -> Long.compare(b.overdue, a.overdue));
        int rendered = 0;
        for (int i = 0; i < Math.min(budget, due.size()); i++) {
            Candidate candidate = due.get(i);
            CameraFeed feed = FEEDS.get(candidate.feedId);
            if (feed == null) {
                continue;
            }
            candidate.target.resize(CameraTarget.stepFor(candidate.request.width));
            candidate.target.render(feed, delta);
            rendersThisSecond++;
            rendered++;
        }
        // Last thing before the main frame gets the context back. See FeedAudit for what it is looking for.
        FeedAudit.handback(rendered, evicted);
    }

    /** Frames per second a feed drawn at this distance is entitled to. */
    private static int fpsFor(double distance) {
        int max = WFClientConfig.CAMERA_MAX_FPS.get();
        int min = Math.min(max, WFClientConfig.CAMERA_MIN_FPS.get());
        double full = WFClientConfig.CAMERA_FULL_RATE_DISTANCE.get();
        if (distance <= full) {
            return max;
        }
        if (distance >= FALLOFF_DISTANCE) {
            return min;
        }
        double t = (distance - full) / (FALLOFF_DISTANCE - full);
        return (int) Math.round(max + (min - max) * t);
    }

    /** @return how many framebuffers were released, which is the "player turned away" transition. */
    private static int evict() {
        int closed = 0;
        for (Iterator<Map.Entry<Integer, CameraTarget>> it = TARGETS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, CameraTarget> entry = it.next();
            Request request = REQUESTS.get(entry.getKey());
            if (request == null || frame - request.frame > STALE_FRAMES) {
                entry.getValue().close();
                it.remove();
                closed++;
            }
        }
        REQUESTS.entrySet().removeIf(e -> frame - e.getValue().frame > STALE_FRAMES * 4L);
        FEEDS.entrySet().removeIf(e -> {
            Minecraft mc = Minecraft.getInstance();
            boolean gone = mc.level == null
                    || mc.level.getGameTime() - e.getValue().gameTime() > CameraFeed.STALE_TICKS * 8L;
            if (gone) {
                FeedGimbal.forget(e.getKey());
                FeedMotion.forget(e.getKey());
            }
            return gone;
        });
        return closed;
    }

    // --- diagnostics ----------------------------------------------------------------------------------

    /**
     * @return feed renders per second, averaged over the last second. The number to watch when a machine
     *      starts stuttering with monitors up: it should sit at or below maxFps however many feeds exist.
     */
    public static long renderRate() {
        long now = System.nanoTime();
        if (now - reportedAtNanos > 1_000_000_000L) {
            rendersReported = rendersThisSecond;
            rendersThisSecond = 0;
            reportedAtNanos = now;
        }
        return rendersReported;
    }

    public static int liveTargets() {
        return TARGETS.size();
    }

    public static int knownFeeds() {
        return FEEDS.size();
    }

    /** Release every framebuffer. */
    public static void clear() {
        for (CameraTarget target : TARGETS.values()) {
            target.close();
        }
        TARGETS.clear();
        REQUESTS.clear();
        FEEDS.clear();
        FeedGimbal.clear();
        FeedMotion.clear();
        FeedAudit.reset();
        FeedGraphs.forgetAll();
        // After FEEDS is empty, so nothing is judged still wanted on the way out.
        FeedChunks.clear();
    }

    private static final class Request {
        private long frame = -1;
        private int width;
        private double distance = Double.MAX_VALUE;
    }

    private record Candidate(int feedId, CameraTarget target, Request request, long overdue) {
    }
}
