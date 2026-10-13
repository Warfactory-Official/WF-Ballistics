package com.wf.wflib.nvg.client;

import com.wf.gemrender.light.LightSink;
import com.wf.gemrender.light.Lights;
import com.wf.wflib.client.cam.FeedPass;
import com.wf.wflib.client.cam.ScreenSensor;
import com.wf.wflib.nvg.NvgState;
import com.wf.wflib.nvg.NvgWorn;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * IR illuminators: every goggle down with {@code ir} on (brow); the camera of an IR feed pass or IR
 * {@link ScreenSensor} (vehicle sight).
 */
public final class NvgIllumination {
    static final float RANGE = 48.0f, INNER = 6.0f, OUTER = 24.0f, INTENSITY = 60.0f;
    static final int RGB = 0xFFFFFF;
    /** Brow above the eye, toward the look direction. */
    static final double UP = 0.12, FORWARD = 0.1;
    static final double REACH_SQ = 96.0 * 96.0;
    /** IR camera's own illuminator (LED ring): wide, ahead of the lens. */
    static final float CAMERA_RANGE = 64.0f, CAMERA_OUTER = 35.0f, CAMERA_INTENSITY = 120.0f;
    static final double CAMERA_AHEAD = 0.4;

    private static final Vector3f DIR = new Vector3f();
    private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    private NvgIllumination() {
    }

    public static void register() {
        Lights.register(NvgIllumination::collect);
    }

    /** IR sight / camera illuminator at lens {@code at}, unit {@code look}. Remote sights: caller syncs. */
    public static void sight(LightSink sink, Vec3 at, Vec3 look) {
        DIR.set((float) look.x, (float) look.y, (float) look.z);
        sink.spot(at.x + look.x * CAMERA_AHEAD, at.y + look.y * CAMERA_AHEAD, at.z + look.z * CAMERA_AHEAD, DIR,
                WORLD_UP, CAMERA_RANGE, INNER, CAMERA_OUTER, RGB, CAMERA_INTENSITY, null, true, true);
    }

    private static void collect(LightSink sink, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (FeedPass.active() && FeedPass.ir()) {
            sight(sink, FeedPass.eye(), FeedPass.look());
        } else if (!FeedPass.active() && ScreenSensor.ir()) {
            Camera view = mc.gameRenderer.getMainCamera();
            sight(sink, view.getPosition(), new Vec3(view.getLookVector()));
        }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        for (Player player : mc.level.players()) {
            if (player.distanceToSqr(camera) > REACH_SQ) {
                continue;
            }
            NvgState state = NvgWorn.state(player);
            if (!state.ir() || !NvgWorn.down(player)) {
                continue;
            }
            Vec3 eye = player.getEyePosition(partialTick);
            Vec3 look = player.getViewVector(partialTick);
            DIR.set((float) look.x, (float) look.y, (float) look.z);
            sink.spot(eye.x + look.x * FORWARD, eye.y + UP + look.y * FORWARD, eye.z + look.z * FORWARD, DIR,
                    WORLD_UP, RANGE, INNER, OUTER, RGB, INTENSITY, null, true, true);
        }
    }
}
