package com.wf.wflib.fx;

import com.wf.wflib.WFLib;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@EventBusSubscriber(modid = WFLib.MODID)
public final class EMPStunManager {

    private static final int EMIT_INTERVAL = 12;
    private static final List<Stun> STUNS = new CopyOnWriteArrayList<>();

    private EMPStunManager() {
    }

    public static void stun(ServerLevel level, BlockPos pos, int ticks) {
        long expiry = level.getGameTime() + ticks;
        BlockPos key = pos.immutable();
        for (int i = 0; i < STUNS.size(); i++) {
            Stun s = STUNS.get(i);
            if (s.level == level && s.pos.equals(key)) {
                if (expiry > s.expiry) {
                    STUNS.set(i, new Stun(level, key, expiry));
                }
                return;
            }
        }
        STUNS.add(new Stun(level, key, expiry));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (STUNS.isEmpty()) {
            return;
        }
        Iterator<Stun> it = STUNS.iterator();
        while (it.hasNext()) {
            Stun s = it.next();
            long now = s.level.getGameTime();
            if (now >= s.expiry || !s.level.isLoaded(s.pos)) {
                STUNS.remove(s);
                continue;
            }
            if ((now + Math.floorMod(s.pos.hashCode(), EMIT_INTERVAL)) % EMIT_INTERVAL == 0) {
                EMPCreator.composeStun(s.level, s.pos.getX() + 0.5, s.pos.getY() + 0.5, s.pos.getZ() + 0.5);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        STUNS.clear();
    }

    private record Stun(ServerLevel level, BlockPos pos, long expiry) {
    }
}
