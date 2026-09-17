package com.wf.wfballistics.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.config.WFClientConfig;
import com.wf.wfballistics.drone.cam.CameraFeed;
import com.wf.wfballistics.drone.cam.CameraMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;

/** One camera feed's picture: a framebuffer, the post chain that ages it, and the pass that fills it. */
public final class CameraTarget implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Fixed aspect. A feed is video; letterboxing it into whatever quad it lands on is the display's job. */
    public static final double ASPECT = 16.0 / 9.0;
    /** The resolutions a feed is allowed to be. */
    private static final int[] STEPS = {320, 480, 640, 854, 1280, 1920};
    /** How far ahead of the drone's centre the lens sits, in blocks. */
    private static final double GIMBAL_BOOM = 1.5;

    /** Link quality at which the picture starts to visibly suffer. */
    private static final float DEGRADE_ONSET = 0.75f;

    private final int feedId;
    private final FeedCamera camera = new FeedCamera();
    private final FeedTexture texture = new FeedTexture();
    private final ResourceLocation location;
    /** This feed's own visibility traversal. The single most important object here; see its class doc. */
    private final FeedRenderSwap sections = new FeedRenderSwap();

    private TextureTarget target;
    private PostChain chain;
    private CameraMode chainMode;
    private int width;
    private int height;
    /** Set once if the offscreen pass ever throws; the feed then shows a card instead of taking the game down. */
    private boolean broken;

    private long renderedAtTick = Long.MIN_VALUE;
    private long renderedAtNanos;

    CameraTarget(int feedId, int width) {
        this.feedId = feedId;
        this.location = ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "camera_feed/" + feedId);
        resize(width);
        Minecraft.getInstance().getTextureManager().register(this.location, this.texture);
    }

    // --- geometry -------------------------------------------------------------------------------------

    /**
     * @return the nearest allowed width at or above {@code requested}, capped by the client's configured
     *      maximum. Snapping up rather than down: a feed slightly softer than the quad it is on is invisible,
     *      whereas one slightly sharper is free.
     */
    static int stepFor(int requested) {
        int cap = WFClientConfig.CAMERA_MAX_WIDTH.get();
        for (int step : STEPS) {
            if (step >= requested) {
                return Math.min(step, cap);
            }
        }
        return Math.min(STEPS[STEPS.length - 1], cap);
    }

    int width() {
        return this.width;
    }

    void resize(int newWidth) {
        int w = Math.max(STEPS[0], newWidth);
        int h = (int) Math.round(w / ASPECT);
        if (this.target != null && w == this.width && h == this.height) {
            return;
        }
        this.width = w;
        this.height = h;
        if (this.target != null) {
            this.target.destroyBuffers();
        }
        this.target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        this.target.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        // The colour attachment is a fresh GL id after every resize; a stale one draws garbage or nothing.
        this.texture.adopt(this.target.getColorTextureId());
        closeChain();
    }

    // --- post chain -----------------------------------------------------------------------------------

    private void ensureChain(CameraMode mode) {
        if (!WFClientConfig.CAMERA_EFFECTS.get()) {
            closeChain();
            return;
        }
        if (this.chain != null && this.chainMode == mode) {
            return;
        }
        closeChain();
        Minecraft mc = Minecraft.getInstance();
        try {
            this.chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), this.target,
                    mode.chain());
            this.chain.resize(this.width, this.height);
            this.chainMode = mode;
        } catch (Exception e) {
            // A missing or malformed chain must not stop the feed. Log once per mode and show clean video.
            LOGGER.error("drone camera: no usable post chain for {} ({})", mode.id(), e.toString());
            this.chain = null;
            this.chainMode = mode;
        }
    }

    private void closeChain() {
        if (this.chain != null) {
            this.chain.close();
            this.chain = null;
        }
        this.chainMode = null;
    }

    // --- the pass -------------------------------------------------------------------------------------

    /** Draw the world from this feed's camera into this feed's framebuffer. */
    void render(CameraFeed feed, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (this.broken || mc.level == null || mc.player == null) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(true);
        ensureChain(feed.modeValue());

        RenderTarget main = mc.getMainRenderTarget();
        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
        VertexSorting savedSorting = RenderSystem.getVertexSorting();

        try {
            Entity anchor = mc.level.getEntity(feed.feedId());
            float yaw = FeedGimbal.yaw(feed);
            float pitch = FeedGimbal.pitch(feed);
            Vec3 forward = new Vec3(
                    -Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)),
                    -Math.sin(Math.toRadians(pitch)),
                    Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
            double boom = anchor == null ? GIMBAL_BOOM : Math.max(GIMBAL_BOOM, anchor.getBbWidth());
            Vec3 lens = FeedMotion.position(feed).add(forward.scale(boom));
            this.camera.place(mc.level, anchor != null ? anchor : mc.player,
                    lens, yaw, pitch, partial);

            Matrix4f projection = new Matrix4f().setPerspective(
                    (float) Math.toRadians(FeedGimbal.fov(feed)),
                    (float) this.width / (float) this.height,
                    0.05f, mc.gameRenderer.getDepthFar());
            Quaternionf rotation = this.camera.rotation().conjugate(new Quaternionf());
            Matrix4f frustum = new Matrix4f().rotation(rotation);

            this.target.clear(Minecraft.ON_OSX);
            this.target.bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            mc.levelRenderer.prepareCullFrustum(this.camera.getPosition(), frustum, projection);

            mc.getProfiler().push("wf_drone_camera");
            FeedPass.begin(lens, WFClientConfig.CAMERA_FEED_VIEW_DISTANCE.get(),
                    feed.modeValue() == CameraMode.THERMAL);
            try {
                this.sections.install(mc.levelRenderer);
                mc.levelRenderer.renderLevel(delta, false, this.camera, mc.gameRenderer,
                        mc.gameRenderer.lightTexture(), frustum, projection);
            } finally {
                this.sections.uninstall(mc.levelRenderer);
                FeedPass.end();
            }
            mc.getProfiler().pop();

            if (feed.modeValue() == CameraMode.THERMAL && this.chain != null) {
                CameraHeatMask.render(this.chain, this.target, this.camera, frustum, projection, partial);
            }

            if (this.chain != null) {
                float loss = Math.max(0.0f, (DEGRADE_ONSET - feed.link()) / DEGRADE_ONSET);
                float degrade = Math.min(1.0f, loss * loss * feed.modeValue().noiseMultiplier());
                this.chain.setUniform("Degrade", Math.min(1.0f, degrade));
                this.chain.setUniform("Link", feed.link());
                this.chain.setUniform("FeedTime", (feed.gameTime() % 24000L) + partial);
                this.chain.process(partial);
            }

            CameraOsd.draw(this, feed);
        } catch (Exception e) {
            this.broken = true;
            LOGGER.error("drone camera: offscreen pass failed for feed {}; showing a card instead",
                    this.feedId, e);
        } finally {
            main.bindWrite(true);
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        }

        this.renderedAtTick = feed.gameTime();
        this.renderedAtNanos = System.nanoTime();
    }

    // --- accessors ------------------------------------------------------------------------------------

    /**
     * @return the texture to sample, or null if this feed has never produced a picture. Callers draw their
     *      own signal-loss card rather than being handed a black texture, so "no video" and "video of a dark
     *      room" stay distinguishable.
     */
    public ResourceLocation textureOrNull() {
        return this.renderedAtTick == Long.MIN_VALUE || this.broken ? null : this.location;
    }

    RenderTarget target() {
        return this.target;
    }

    int height() {
        return this.height;
    }

    long renderedAtNanos() {
        return this.renderedAtNanos;
    }

    boolean broken() {
        return this.broken;
    }

    @Override
    public void close() {
        closeChain();
        this.sections.close();
        Minecraft.getInstance().getTextureManager().release(this.location);
        if (this.target != null) {
            this.target.destroyBuffers();
            this.target = null;
        }
    }
}
