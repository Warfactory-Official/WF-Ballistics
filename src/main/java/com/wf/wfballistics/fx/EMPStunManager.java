package com.wf.wfballistics.fx;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Mod.EventBusSubscriber(modid = WFBallistics.MODID)
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
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || STUNS.isEmpty()) {
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
