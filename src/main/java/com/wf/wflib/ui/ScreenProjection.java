package com.wf.wflib.ui;

import com.wf.wflib.WFLib;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** World point => GUI coordinates of the last main-view level frame (feed passes skipped); no allocation. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ScreenProjection {
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;

    private ScreenProjection() {
    }

    @SubscribeEvent
    static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL
                || com.wf.wflib.client.cam.FeedPass.active()) {
            return;
        }
        VIEW_PROJECTION.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        cameraX = camera.x;
        cameraY = camera.y;
        cameraZ = camera.z;
    }

    /**
     * Writes GUI x, y into {@code out[0..1]}; returns false when the point is behind the camera (out
     * untouched).
     */
    public static boolean project(double x, double y, double z, float[] out) {
        float rx = (float) (x - cameraX);
        float ry = (float) (y - cameraY);
        float rz = (float) (z - cameraZ);
        Matrix4f m = VIEW_PROJECTION;
        float cx = m.m00() * rx + m.m10() * ry + m.m20() * rz + m.m30();
        float cy = m.m01() * rx + m.m11() * ry + m.m21() * rz + m.m31();
        float cw = m.m03() * rx + m.m13() * ry + m.m23() * rz + m.m33();
        if (cw <= 0) {
            return false;
        }
        var window = Minecraft.getInstance().getWindow();
        out[0] = window.getGuiScaledWidth() * (0.5f + cx / cw * 0.5f);
        out[1] = window.getGuiScaledHeight() * (0.5f - cy / cw * 0.5f);
        return true;
    }

    public static boolean project(Vec3 point, float[] out) {
        return point != null && project(point.x, point.y, point.z, out);
    }
}
