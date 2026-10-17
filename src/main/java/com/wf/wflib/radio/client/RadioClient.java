package com.wf.wflib.radio.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.radio.RadioCarrier;
import com.wf.wflib.radio.RadioTunePacket;
import com.wf.wflib.radio.RadioVoice;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client {@link RadioCarrier}s: one {@link RadioEmitter} each while tuned to a catalogued station and the camera is
 * within its voice range (+ {@link #HYSTERESIS}). Station / voice / aboard / catalogue change => restarted at the
 * playhead {@code gameTime x 2400 mod loop}.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class RadioClient {
    private static final double HYSTERESIS = 8;
    private static final List<Entity> CARRIERS = new ArrayList<>();
    private static final Map<Entity, Playing> PLAYING = new HashMap<>();

    private record Playing(RadioEmitter emitter, String station, RadioVoice voice, boolean aboard, int generation) {
    }

    private RadioClient() {
    }

    /** Tune {@code carrier} one station along (off between last and first); server checks the sender. */
    public static void cycle(Entity carrier, int step) {
        if (carrier instanceof RadioCarrier rc && rc.radioVoice() != null) {
            tune(carrier, RadioCatalog.next(rc.radioStation(), step));
        }
    }

    public static void tune(Entity carrier, String station) {
        PacketDistributor.sendToServer(new RadioTunePacket(carrier.getUUID(), station));
    }

    /** Harness: carriers with a live source. */
    public static int playing() {
        return PLAYING.size();
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof RadioCarrier) {
            CARRIERS.add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof RadioCarrier) {
            CARRIERS.remove(event.getEntity());
            stop(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CARRIERS.clear();
        for (Playing p : PLAYING.values()) {
            p.emitter.end();
        }
        PLAYING.clear();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        for (int i = 0; i < CARRIERS.size(); i++) {
            Entity e = CARRIERS.get(i);
            RadioCarrier rc = (RadioCarrier) e;
            RadioVoice voice = rc.radioVoice();
            String id = rc.radioStation();
            RadioCatalog.Station station = voice == null ? null : RadioCatalog.get(id);
            boolean aboard = mc.player.getVehicle() != null && mc.player.getRootVehicle() == e.getRootVehicle();
            Playing p = PLAYING.get(e);
            double reach = voice == null ? 0 : voice.range() + (p != null ? HYSTERESIS : 0);
            boolean wanted = station != null && (aboard || e.getBoundingBox().getCenter().distanceToSqr(camera)
                    <= reach * reach);
            if (p != null && (!wanted || p.emitter.isStopped() || p.voice != voice || p.aboard != aboard
                    || !p.station.equals(id) || p.generation != RadioCatalog.generation())) {
                stop(e);
                p = null;
            }
            if (wanted && p == null) {
                long sample = mc.level.getGameTime() * RadioCatalog.SAMPLES_PER_TICK;
                RadioEmitter emitter = new RadioEmitter(e, voice, aboard, station, sample);
                PLAYING.put(e, new Playing(emitter, id, voice, aboard, RadioCatalog.generation()));
                mc.getSoundManager().play(emitter);
            }
        }
    }

    private static void stop(Entity e) {
        @Nullable Playing p = PLAYING.remove(e);
        if (p != null) {
            p.emitter.end();
        }
    }
}
