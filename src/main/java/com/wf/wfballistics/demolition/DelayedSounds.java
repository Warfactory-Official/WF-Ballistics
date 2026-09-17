package com.wf.wfballistics.demolition;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** A tiny server-side scheduler that plays a positional sound to one player after a tick delay. */
@EventBusSubscriber(modid = WFBallistics.MODID)
public final class DelayedSounds {

    private record Pending(int fireTick, ResourceKey<Level> dimension, UUID player, double x, double y, double z,
                           SoundEvent sound, SoundSource source, float volume, float pitch, long seed) {
    }

    private static final List<Pending> QUEUE = new ArrayList<>();

    private DelayedSounds() {
    }

    public static void schedule(ServerLevel level, int delayTicks, ServerPlayer player, double x, double y,
                                double z, SoundEvent sound, SoundSource source, float volume, float pitch,
                                long seed) {
        int fireTick = level.getServer()
                .getTickCount() + Math.max(0, delayTicks);
        QUEUE.add(new Pending(fireTick, level.dimension(), player.getUUID(), x, y, z, sound, source, volume,
                pitch, seed));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (QUEUE.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            QUEUE.clear();
            return;
        }
        int now = server.getTickCount();
        Iterator<Pending> it = QUEUE.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (now < p.fireTick()) {
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList()
                    .getPlayer(p.player());
            if (player == null || player.level()
                    .dimension() != p.dimension()) {
                continue;
            }
            player.connection.send(new ClientboundSoundPacket(Holder.direct(p.sound()), p.source(),
                    p.x(), p.y(), p.z(), p.volume(), p.pitch(), p.seed()));
        }
    }
}
