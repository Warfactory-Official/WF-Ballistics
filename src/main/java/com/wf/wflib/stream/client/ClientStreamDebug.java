package com.wf.wflib.stream.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.stream.StreamDebug;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** CLIENT category state/heartbeat, and the {@code /wfstream map} dump. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ClientStreamDebug {

    private static long ticks;

    private ClientStreamDebug() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ticks++;
        if (ticks % 20 == 0) {
            StreamDebug.refresh();
        }
        if (!StreamDebug.on(StreamDebug.Category.CLIENT)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Entity host = DetachedView.host();
        if (minecraft.level == null) {
            StreamDebug.state(StreamDebug.Category.CLIENT, "client view", "no level");
            return;
        }
        if (host == null) {
            StreamDebug.state(StreamDebug.Category.CLIENT, "client view", "body view");
            return;
        }
        ChunkPos pos = host.chunkPosition();
        boolean loaded = minecraft.level.hasChunk(pos.x, pos.z);
        StreamDebug.state(StreamDebug.Category.CLIENT, "client view", "detached view on " + pos + " chunkLoaded=" + loaded);
        if (ticks % StreamDebug.heartbeatTicks() == 0) {
            StreamDebug.log(StreamDebug.Category.CLIENT,
                    "heartbeat: host {} chunkLoaded={} ringChunks={} offViewChunks={} camera={}",
                    pos, loaded, minecraft.level.getChunkSource().getLoadedChunksCount(),
                    ((OffViewChunks) minecraft.level.getChunkSource()).wfOffViewPositions().size(),
                    minecraft.options.getCameraType());
        }
    }

    /**
     * Held chunks around the view origin (host if detached, else body) to the log.
     *
     * @return summary line
     */
    public static String map(int radius) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        ChunkSource source = level.getChunkSource();
        LongSet offView = ((OffViewChunks) source).wfOffViewPositions();
        LongOpenHashSet ready = SodiumChunkProbe.readySet(level);
        Entity host = DetachedView.host();
        ChunkPos body = player.chunkPosition();
        ChunkPos camera = minecraft.cameraEntity == null ? body : minecraft.cameraEntity.chunkPosition();
        ChunkPos origin = host == null ? body : host.chunkPosition();

        StreamDebug.report("map | origin {} radius {} | body {} camera {} | host {} | riding {}", origin, radius, body,
                camera, host == null ? "none" : host.chunkPosition(),
                player.getVehicle() == null ? "nothing" : player.getVehicle().getType().toShortString());
        StreamDebug.report("map | ring {} off-view {} | sodium ready {}",
                source.getLoadedChunksCount(), offView.size(), ready == null ? -1 : ready.size());
        StreamDebug.report("map | # held+drawable  X held+untracked  r off-view+drawable  R off-view+untracked"
                + "  . absent  ! absent+tracked  B body  C camera");
        StreamDebug.report("map | outer ring untracked by design: Sodium draws a chunk only once all 8 neighbours arrived");
        int held = 0;
        int missing = 0;
        int untracked = 0;
        int ghost = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int x = origin.x + dx;
                int z = origin.z + dz;
                ChunkAccess chunk = source.getChunk(x, z, ChunkStatus.FULL, false);
                boolean drawable = ready == null || ready.contains(ChunkPos.asLong(x, z));
                char mark;
                if (x == body.x && z == body.z) {
                    mark = 'B';
                } else if (x == camera.x && z == camera.z) {
                    mark = 'C';
                } else if (chunk == null) {
                    mark = drawable ? '!' : '.';
                } else if (offView.contains(ChunkPos.asLong(x, z))) {
                    mark = drawable ? 'r' : 'R';
                } else {
                    mark = drawable ? '#' : 'X';
                }
                if (chunk != null) {
                    held++;
                    if (!drawable) {
                        untracked++;
                    }
                } else {
                    missing++;
                    if (drawable) {
                        ghost++;
                    }
                }
                row.append(mark);
            }
            StreamDebug.report("map | {}", row);
        }
        String summary = String.format("%d held, %d missing, %d held-but-untracked, %d tracked-but-absent",
                held, missing, untracked, ghost);
        StreamDebug.report("map | {}", summary);
        return summary;
    }
}
