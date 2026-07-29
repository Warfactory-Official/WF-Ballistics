package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.WFSounds;
import com.wf.wfballistics.network.MissileFlightAudioPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;


@Mod.EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class MissileAudioClient {

    private static final Map<UUID, RemoteMissileFlightSound> ACTIVE = new java.util.HashMap<>();

    private MissileAudioClient() {
    }

    /** Start or refresh the loop for the missile in this heartbeat. Runs on the client main thread. */
    public static void upsert(MissileFlightAudioPacket pkt) {
        RemoteMissileFlightSound existing = ACTIVE.get(pkt.id());
        if (existing != null && !existing.isDone()) {
            existing.update(pkt);
            return;
        }
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(pkt.sound());
        if (sound == null) {
            sound = WFSounds.MISSILE_FLIGHT.get();
        }
        RemoteMissileFlightSound instance = new RemoteMissileFlightSound(sound, pkt);
        ACTIVE.put(pkt.id(), instance);
        Minecraft.getInstance().getSoundManager().play(instance);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            ACTIVE.clear();
            return;
        }
        // Drop loops that have stopped (timed out on their own tick, or were culled by the sound engine).
        Iterator<Map.Entry<UUID, RemoteMissileFlightSound>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isDone()) {
                it.remove();
            }
        }
    }
}
