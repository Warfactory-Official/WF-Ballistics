package com.wf.wflib.stream;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/** What the server believes a client holds, dumped for diffing against the client's own dump. */
public final class ChunkStreamAudit {

    private ChunkStreamAudit() {
    }

    /** @return {vanilla, streamed, union} counts; union written to {@code <gamedir>/<file>} */
    public static int[] server(ServerPlayer player, String file) {
        LongOpenHashSet vanilla = new LongOpenHashSet();
        player.getChunkTrackingView().forEach(pos -> {
            if (!player.connection.chunkSender.isPending(pos.toLong())) {
                vanilla.add(pos.toLong());
            }
        });
        LongOpenHashSet streamed = ChunkStreams.delivered(player);
        LongOpenHashSet union = new LongOpenHashSet(vanilla);
        union.addAll(streamed);
        write(FMLPaths.GAMEDIR.get().resolve(file), union);
        return new int[]{vanilla.size(), streamed.size(), union.size()};
    }

    /** Sorted {@code x z} lines. */
    public static void write(Path path, LongOpenHashSet positions) {
        long[] sorted = positions.toLongArray();
        Arrays.sort(sorted);
        try {
            Files.writeString(path, Arrays.stream(sorted)
                    .mapToObj(pos -> ChunkPos.getX(pos) + " " + ChunkPos.getZ(pos))
                    .collect(Collectors.joining("\n", "", "\n")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
