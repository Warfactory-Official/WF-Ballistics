package com.wf.wflib.client.cam;

import com.wf.wflib.drone.cam.CameraMode;

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
 * Level drawn from an arbitrary camera into a caller-owned framebuffer, own section-visibility graph.
 * Call only from {@code RenderFrameEvent.Pre} (main target bound, profiler stack empty, no level pass running).
 * One instance per view; holds GPU/graph state until {@link #close()}. Entity render-distance cull measured
 * from this camera.
 */
public final class OffscreenView implements AutoCloseable {

    private static final float NEAR = 0.05f;
    private static final float ORTHO_NEAR = 0.5f;

    private final FeedCamera camera = new FeedCamera();
    private final FeedRenderSwap swap;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f frustum = new Matrix4f();
    private final Matrix4f cull = new Matrix4f();
    /** > 0 => orthographic, half-height in blocks. */
    private float orthoHalf;
    /** Lens height over the ground, blocks (ortho only). */
    private float orthoDepth;

    private OffscreenView(int viewDistance) {
        this.swap = new FeedRenderSwap(viewDistance);
    }

    /**
     * Main view's section grid, centred on the player; own graph. Outside the player's render distance =>
     * nothing drawn here. Translucent sort follows the main view's.
     */
    public static OffscreenView sharedGrid() {
        return new OffscreenView(FeedRenderSwap.SHARED);
    }

    /**
     * Second grid centred on the camera; duplicates section buffers and compiles. Drone feeds: chunks outside
     * the client's ring need streaming.
     * @param viewDistance sections either side of the camera; also the fog distance
     */
    public static OffscreenView ownGrid(int viewDistance) {
        if (viewDistance < 2) {
            throw new IllegalArgumentException("viewDistance " + viewDistance);
        }
        return new OffscreenView(viewDistance);
    }

    /**
     * Following passes orthographic ({@code halfHeight} blocks, lens {@code depth} over the ground; fov ignored) or,
     * {@code halfHeight <= 0}, perspective. Ortho: far plane at the world floor; culled with a perspective cone
     * holding the box down to half the lens height (vanilla {@code offsetToFullyIncludeCameraCube} steps ~far/2 per
     * loop under ortho => hang); no fog, sky, clouds; section graph not height-capped ({@link FeedGraphColumns}).
     */
    public void ortho(float halfHeight, float depth) {
        this.orthoHalf = halfHeight;
        this.orthoDepth = Math.max(depth, 8.0f);
    }

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
        return render(anchor, pos, yaw, pitch, roll, fovDeg, target, delta, CameraMode.OPTICAL);
    }

    boolean render(Entity anchor, Vec3 pos, float yaw, float pitch, float roll, float fovDeg,
                   RenderTarget target, DeltaTracker delta, CameraMode mode) {
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
            float aspect = (float) target.width / (float) target.height;
            boolean ortho = this.orthoHalf > 0;
            if (ortho) {
                float far = (float) (pos.y - mc.level.getMinBuildHeight()) + 1.0f;
                this.projection.setOrtho(-this.orthoHalf * aspect, this.orthoHalf * aspect, -this.orthoHalf,
                        this.orthoHalf, ORTHO_NEAR, far);
                this.cull.setPerspective((float) (2.0 * Math.atan(2.0 * this.orthoHalf / this.orthoDepth)), aspect,
                        NEAR, far);
            } else {
                this.projection.setPerspective((float) Math.toRadians(fovDeg), aspect, NEAR,
                        mc.gameRenderer.getDepthFar());
                this.cull.set(this.projection);
            }
            this.frustum.rotation(this.camera.rotation().conjugate(new Quaternionf()));

            // clear() ends in unbindWrite => binds the main target; clear first, bind after.
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(this.projection,
                    ortho ? VertexSorting.ORTHOGRAPHIC_Z : VertexSorting.DISTANCE_TO_ORIGIN);
            mc.levelRenderer.prepareCullFrustum(pos, this.frustum, this.cull);

            // renderLevel opens with popPush.
            mc.getProfiler().push("wf_offscreen_view");
            FeedPass.begin(this.swap.shared() ? null : pos,
                    this.swap.shared() ? mc.options.getEffectiveRenderDistance() : this.swap.viewDistance(),
                    mode, ortho, target, pos, yaw, pitch);
            try {
                this.swap.columns(ortho);
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
