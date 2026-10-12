package com.wf.wflib.audio;

import com.wf.wflib.mixin.AccessorChunkMap;
import com.wf.wflib.network.SoundAtPacket;
import com.wf.wflib.stream.StreamTracked;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/** Server: one-shot layers at a point for every player within the widest layer's range. */
public final class WfAudio {
    private WfAudio() {
    }

    public static void at(ServerLevel level, Vec3 at, AudioCategory category, List<SoundSpec> layers) {
        at(level, at, category, null, layers);
    }

    /** Players {@code e} is paired to: they hear its sounds from their own copy of it. */
    public static Predicate<ServerPlayer> trackers(Entity e) {
        Object tracked = ((AccessorChunkMap) ((ServerLevel) e.level()).getChunkSource().chunkMap).wfEntityMap()
                .get(e.getId());
        return tracked instanceof StreamTracked t ? t::wfSeenBy : p -> false;
    }

    /** {@code skip}: players that play it themselves (shooter prediction, entity trackers). */
    public static void at(ServerLevel level, Vec3 at, AudioCategory category, @Nullable Predicate<ServerPlayer> skip,
                          List<SoundSpec> layers) {
        double reach = 0;
        for (SoundSpec s : layers) {
            reach = Math.max(reach, s.range());
        }
        double r2 = reach * reach;
        SoundAtPacket packet = new SoundAtPacket(layers, at.x, at.y, at.z, category, level.random.nextLong());
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at) < r2 && (skip == null || !skip.test(p))) {
                PacketDistributor.sendToPlayer(p, packet);
            }
        }
    }
}
