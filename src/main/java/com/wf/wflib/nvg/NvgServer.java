package com.wf.wflib.nvg;

import com.wf.wflib.WFLib;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Flip = hand reaches the mount, state toggles at {@link #SWAP_TICK}, hand returns by {@link #FLIP_TICKS}. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class NvgServer {
    public static final int FLIP_TICKS = 12;
    public static final int SWAP_TICK = 6;

    private static final Map<UUID, Integer> FLIPS = new HashMap<>();

    private NvgServer() {
    }

    public static void act(ServerPlayer player, NvgActionPacket.Action action) {
        if (NvgWorn.nvg(player).isEmpty()) {
            return;
        }
        NvgState state = NvgWorn.state(player);
        switch (action) {
            case FLIP -> FLIPS.putIfAbsent(player.getUUID(), 0);
            case IR -> {
                NvgWorn.set(player, state.withIr(!state.ir()));
                player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(),
                        SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.25f, 1.8f);
            }
            case GAIN_UP -> NvgWorn.set(player, state.stepGain(1));
            case GAIN_DOWN -> NvgWorn.set(player, state.stepGain(-1));
        }
    }

    public static boolean flipping(ServerPlayer player) {
        return FLIPS.containsKey(player.getUUID());
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Integer tick = FLIPS.get(player.getUUID());
        if (tick == null) {
            return;
        }
        tick++;
        if (tick == SWAP_TICK && !NvgWorn.nvg(player).isEmpty()) {
            NvgState state = NvgWorn.state(player);
            NvgWorn.set(player, state.withDown(!state.down()));
            player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(),
                    SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.35f, 1.6f);
        }
        if (tick >= FLIP_TICKS) {
            FLIPS.remove(player.getUUID());
        } else {
            FLIPS.put(player.getUUID(), tick);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        FLIPS.remove(event.getEntity().getUUID());
    }
}
