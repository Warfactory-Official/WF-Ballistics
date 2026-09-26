package com.wf.wflib.stream.client;

import com.wf.wflib.api.DetachedBodyHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

public final class DetachedView {

    private DetachedView() {
    }

    /** @return the host the local player operates from afar, or null */
    @Nullable
    public static Entity host() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof DetachedBodyHost host && host.isDetachedBodyActive()
                && host.getDetachedBodyAnchor(player) != null) {
            return vehicle;
        }
        return null;
    }
}
