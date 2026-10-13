package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.wf.gemrender.particle.ParticleBuffer;
import com.wf.gemrender.volume.VolumeBuffer;
import com.wf.wflib.drone.cam.CameraMode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** State of the running {@link OffscreenView} pass. */
public final class FeedPass {

    @Nullable
    private static Vec3 origin;
    private static int viewDistance;
    private static CameraMode mode = CameraMode.OPTICAL;
    private static Vec3 eye = Vec3.ZERO;
    private static Vec3 look = new Vec3(0.0, 0.0, 1.0);
    private static boolean ortho;
    @Nullable
    private static RenderTarget target;

    private FeedPass() {
    }

    /** @param at grid centre; null => main grid (shared) */
    static void begin(@Nullable Vec3 at, int distance, CameraMode sensor, boolean orthographic,
                      RenderTarget into, Vec3 camera, float yaw, float pitch) {
        eye = camera;
        look = Vec3.directionFromRotation(pitch, yaw);
        origin = at;
        ortho = orthographic;
        target = into;
        viewDistance = distance;
        mode = sensor;
        ParticleBuffer.getInstance().bind();
        VolumeBuffer.getInstance().bind();
    }

    static void end() {
        origin = null;
        target = null;
        mode = CameraMode.OPTICAL;
        ortho = false;
        ParticleBuffer.getInstance().bind();
        VolumeBuffer.getInstance().bind();
    }

    /** Thermal pass: particles hidden ({@link #seesThroughSmoke}), heat mask drawn. */
    public static boolean thermal() {
        return mode == CameraMode.THERMAL;
    }

    /** IR pass: own illuminator. */
    public static boolean ir() {
        return mode == CameraMode.IR;
    }

    /** IR or intensifier pass: IR lights + night sky in colour ({@code Lights.IrView.MERGED}). */
    public static boolean amplifying() {
        return mode == CameraMode.IR || mode == CameraMode.INTENSIFIER;
    }

    /** Thermal picture being drawn: this pass, else the main pass under {@link ScreenSensor}. */
    public static boolean drawingThermal() {
        return active() ? thermal() : ScreenSensor.thermal();
    }

    /**
     * Thermal or IR being drawn (this pass, else main under {@link ScreenSensor}): particles, gas volumes and
     * in-gas fog hidden. Thermal: smoke still dims the heat mask ({@link CameraHeatMask}).
     */
    public static boolean seesThroughSmoke() {
        return active() ? mode == CameraMode.THERMAL || mode == CameraMode.IR : ScreenSensor.thermal() || ScreenSensor.ir();
    }

    /** @return where the pass's own grid is centred; null => main view or a shared-grid pass. */
    @Nullable
    public static Vec3 origin() {
        return origin;
    }

    /** Translucent sort origin: own-grid pass camera, else main camera. */
    public static Vec3 sortOrigin() {
        Vec3 at = origin;
        return at != null ? at : Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    /**
     * @return the feed's framebuffer while a pass runs: {@code MixinMinecraftFeedTarget} answers it for
     *      {@code getMainRenderTarget}. Iris (no pack: {@code VanillaRenderingPipeline.beginLevelRendering}) binds
     *      the main target inside {@code renderLevel} => terrain drawn to the screen, feed = clear colour.
     */
    @Nullable
    public static RenderTarget target() {
        return target;
    }

    /** Orthographic pass (satellite spot): no fog, sky, clouds. */
    public static boolean ortho() {
        return ortho;
    }

    /** Pass camera; meaningful while {@link #active()}. */
    public static Vec3 eye() {
        return eye;
    }

    public static Vec3 look() {
        return look;
    }

    public static boolean active() {
        return target != null;
    }

    /** Fog distance of the current pass, in sections. Only meaningful while {@link #active()}. */
    public static int viewDistance() {
        return viewDistance;
    }
}
