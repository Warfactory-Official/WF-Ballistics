package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.drone.DroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Keeps each drone the client can hear supplied with a rotor loop. */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class DroneAudioClient {

    /** How far off a drone gets a loop started for it. */
    private static final double AUDIBLE_RANGE = 64.0;
    private static final double START_RANGE_SQR = (AUDIBLE_RANGE + 8.0) * (AUDIBLE_RANGE + 8.0);
    /** Ticks between sweeps. */
    private static final int SWEEP_INTERVAL = 10;

    private static final Set<DroneEntity> DRONES = new HashSet<>();
    private static final Map<DroneEntity, DroneRotorSound> ACTIVE = new HashMap<>();

    private static int sweepTimer;

    private DroneAudioClient() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide && event.getEntity() instanceof DroneEntity drone) {
            DRONES.add(drone);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide || !(event.getEntity() instanceof DroneEntity drone)) {
            return;
        }
        DRONES.remove(drone);
        DroneRotorSound sound = ACTIVE.remove(drone);
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            DRONES.clear();
            ACTIVE.clear();
            return;
        }
        ACTIVE.values().removeIf(sound -> sound.isDone() || !mc.getSoundManager().isActive(sound));

        if (--sweepTimer > 0) {
            return;
        }
        sweepTimer = SWEEP_INTERVAL;

        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        Iterator<DroneEntity> it = DRONES.iterator();
        while (it.hasNext()) {
            DroneEntity drone = it.next();
            if (!drone.isAlive() || drone.level() != mc.level) {
                it.remove();
                continue;
            }
            if (ACTIVE.containsKey(drone)
                    || !drone.getDroneState().powered()
                    || drone.distanceToSqr(player) > START_RANGE_SQR) {
                continue;
            }
            DroneRotorSound sound = new DroneRotorSound(drone);
            ACTIVE.put(drone, sound);
            mc.getSoundManager().play(sound);
        }
    }
}
