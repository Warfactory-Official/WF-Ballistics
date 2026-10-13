package com.wf.wflib.pda;

import com.wf.wflib.drone.DroneRecall;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.item.ModDataComponents;
import com.wf.wflib.network.CameraPanelPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Cameras app, server end: the held PDA's channels resolved to feed ids; unresolved drones recalled. */
public final class PdaCameras {
    private static final int REFRESH_INTERVAL = 20;
    private static final Map<UUID, Long> REFRESHED = new HashMap<>();

    private PdaCameras() {
    }

    /** {@code force} = app asked; else at most once per {@link #REFRESH_INTERVAL} (feed control traffic). */
    public static void refresh(ServerPlayer player, boolean force) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack stack = Pda.heldStack(player);
        if (stack.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Long last = REFRESHED.get(player.getUUID());
        if (!force && last != null && now - last < REFRESH_INTERVAL) {
            return;
        }
        REFRESHED.put(player.getUUID(), now);
        CameraChannels channels = stack.getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY);
        for (FeedTarget target : channels.targets()) {
            if (target.drone().isPresent() && target.resolve(level) == 0) {
                DroneRecall.recall(level, target.drone().get());
            }
        }
        PacketDistributor.sendToPlayer(player, new CameraPanelPacket(channels.resolveAll(level), channels.selected()));
    }
}
