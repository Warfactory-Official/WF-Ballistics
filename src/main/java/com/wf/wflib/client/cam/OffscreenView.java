package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The level drawn from an arbitrary camera into a caller-owned framebuffer, with its own section-visibility graph.
 *
 * <p>Call only from {@code RenderFrameEvent.Pre} (main target bound, profiler stack empty, no level pass running).
 * One instance per independent view; instances hold GPU/graph state until {@link #close()}.
 *
 * <p>Grid modes:
 * <ul>
 * <li>{@link #sharedGrid()}: main view's section grid (centred on the player), own graph. Camera outside the
 * player's render distance => nothing drawn there. Translucent geometry keeps the main view's sort.</li>
 * <li>{@link #ownGrid(int)}: second grid centred on the camera; duplicates section buffers and compiles.
 * Chunks outside the client's ring need streaming (drone feeds).</li>
 * </ul>
 * Entities: vanilla render-distance cull is measured from this camera; the anchor entity is hidden.
 */
public final class OffscreenView implements AutoCloseable {

    private static final float NEAR = 0.05f;

    private final FeedCamera camera = new FeedCamera();
    private final FeedRenderSwap swap;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f frustum = new Matrix4f();

    private OffscreenView(int viewDistance) {
        this.swap = new FeedRenderSwap(viewDistance);
    }

    public static OffscreenView sharedGrid() {
        return new OffscreenView(FeedRenderSwap.SHARED);
    }

    /** @param viewDistance sections either side of the camera; also the fog distance */
    public static OffscreenView ownGrid(int viewDistance) {
        if (viewDistance < 2) {
            throw new IllegalArgumentException("viewDistance " + viewDistance);
        }
        return new OffscreenView(viewDistance);
    }

    /** {@link #render(Entity, Vec3, float, float, float, float, RenderTarget, DeltaTracker)} anchored on the camera entity. */
    public boolean render(Vec3 pos, float yaw, float pitch, float roll, float fovDeg,
                          RenderTarget target, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Entity anchor = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        return render(anchor, pos, yaw, pitch, roll, fovDeg, target, delta);
    }

    /**
     * @param anchor hidden from the picture; supplies fog/fluid state. Flywheel-drawn entities ignore the hiding.
     * @param yaw vanilla convention (degrees, 0 = +Z); {@code pitch} positive = down; {@code roll} degrees
     * @param target needs a depth attachment; aspect = its width/height. Cleared, then drawn.
     * @return false => nothing drawn this frame (no level, or the main grid is about to be rebuilt)
     */
    public boolean render(Entity anchor, Vec3 pos, float yaw, float pitch, float roll, float fovDeg,
                          RenderTarget target, DeltaTracker delta) {
        return render(anchor, pos, yaw, pitch, roll, fovDeg, target, delta, false);
    }

    boolean render(Entity anchor, Vec3 pos, float yaw, float pitch, float roll, float fovDeg,
                   RenderTarget target, DeltaTracker delta, boolean thermal) {
        RenderSystem.assertOnRenderThread();
        if (FeedPass.active()) {
            throw new IllegalStateException("offscreen pass inside an offscreen pass");
        }
        if (!target.useDepth) {
            throw new IllegalArgumentException("offscreen target without depth");
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || FeedRenderSwap.gridStale(mc.levelRenderer)) {
            return false;
        }
        float partial = delta.getGameTimeDeltaPartialTick(true);
        RenderTarget main = mc.getMainRenderTarget();
        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        try {
            this.camera.place(mc.level, anchor, pos, yaw, pitch, roll, partial);
            this.projection.setPerspective((float) Math.toRadians(fovDeg),
                    (float) target.width / (float) target.height, NEAR, mc.gameRenderer.getDepthFar());
            this.frustum.rotation(this.camera.rotation().conjugate(new Quaternionf()));

            // clear() ends in unbindWrite => binds the main target; clear first, bind after.
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(this.projection, VertexSorting.DISTANCE_TO_ORIGIN);
            mc.levelRenderer.prepareCullFrustum(pos, this.frustum, this.projection);

            // renderLevel opens with popPush.
            mc.getProfiler().push("wf_offscreen_view");
            FeedPass.begin(this.swap.shared() ? null : pos,
                    this.swap.shared() ? mc.options.getEffectiveRenderDistance() : this.swap.viewDistance(),
                    thermal, target);
            try {
                this.swap.install(mc.levelRenderer, pos);
                mc.levelRenderer.renderLevel(delta, false, this.camera, mc.gameRenderer,
                        mc.gameRenderer.lightTexture(), new Matrix4f(this.frustum), new Matrix4f(this.projection));
            } finally {
                this.swap.uninstall(mc.levelRenderer);
                FeedPass.end();
                mc.getProfiler().pop();
            }
        } finally {
            main.bindWrite(true);
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        }
        return true;
    }

    Camera camera() {
        return this.camera;
    }

    Matrix4f frustum() {
        return this.frustum;
    }

    Matrix4f projection() {
        return this.projection;
    }

    @Override
    public void close() {
        this.swap.close();
    }
}
