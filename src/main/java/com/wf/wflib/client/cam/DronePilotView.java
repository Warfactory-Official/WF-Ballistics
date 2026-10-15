package com.wf.wflib.client.cam;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.cam.CameraMode;
import com.wf.wflib.pda.client.PdaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Remote pilot of a {@link DroneEntity}: camera entity = the drone, aimed by the player's own look; pilot
 * {@link DroneCamHud}, link degrade. Leaving => the drone's feed full screen ({@link PdaClient#watch}).
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class DronePilotView {

    /** Entity id flown last tick, 0 = none. */
    private static int piloted;

    private DronePilotView() {
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && player.getVehicle() instanceof DroneEntity drone
                && drone.getDetachedBodyAnchor(player) != null) {
            if (mc.getCameraEntity() != drone) {
                mc.setCameraEntity(drone);
            }
            piloted = drone.getId();
            DroneCamHud.pilot(drone.link(), mc.options.keyShift, false);
            ScreenSensor.set(CameraMode.OPTICAL, drone.link());
            return;
        }
        if (piloted == 0) {
            return;
        }
        int left = piloted;
        piloted = 0;
        ScreenSensor.set(null, 1.0f);
        if (player != null) {
            mc.setCameraEntity(player);
            PdaClient.watch(left);
        }
    }

    @SubscribeEvent
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (piloted != 0 && mc.player != null && mc.getCameraEntity() != null
                && mc.getCameraEntity().getId() == piloted) {
            float partialTick = (float) event.getPartialTick();
            event.setYaw(mc.player.getViewYRot(partialTick));
            event.setPitch(mc.player.getViewXRot(partialTick));
        }
    }
}
