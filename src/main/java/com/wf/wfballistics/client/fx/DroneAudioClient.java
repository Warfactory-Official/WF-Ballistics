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

/**
 * Keeps each drone the client can hear supplied with a rotor loop.
 *
 * <p>The client-side counterpart of {@code DroneTracker}, and kept for the same reason: a per-tick sweep of
 * every entity in the level to find the handful that are drones is work that does not need doing when
 * join/leave events already say exactly which ones those are.
 *
 * <p>Loops are not held open for a drone's whole life. A {@link DroneRotorSound} ends itself when the motors
 * wind out, and one is only started for a drone that is under power and close enough to hear, so parked
 * drones and distant ones cost no sound channels. Whichever way a loop ended (landed, shot down, flown out
 * of earshot) this starts a fresh one the moment the drone is worth hearing again.
 */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class DroneAudioClient {

    /**
     * How far off a drone gets a loop started for it. Matches the {@code attenuation_distance} the rotor
     * loop is registered with in {@code sounds.json}, past which the sound engine would render it silent
     * anyway; the margin covers a drone closing fast enough to cross the boundary mid-sweep.
     */
    private static final double AUDIBLE_RANGE = 64.0;
    private static final double START_RANGE_SQR = (AUDIBLE_RANGE + 8.0) * (AUDIBLE_RANGE + 8.0);
    /**
     * Ticks between sweeps. Starting a loop a fraction of a second late is inaudible at the range where one
     * first becomes audible at all, and this is a tenth of the sweeps.
     */
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
            // Cut it here rather than waiting for the loop to notice: an unloaded drone stops ticking, so
            // nothing would ever move it or wind it down and it would hang at the last place it was heard.
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
        // Loops that ended are free to be replaced. Asking the sound manager as well as the instance covers
        // the loop being taken off its channel without ever being told: a sound that only ever checked its
        // own flag would look alive forever and the drone would go permanently silent.
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
